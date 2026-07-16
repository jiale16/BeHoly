package com.example.beholy.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.beholy.data.Constants
import com.example.beholy.data.DetectionResult
import com.example.beholy.data.SensitiveWordDictionary
import com.example.beholy.detection.TierClassifier
import com.example.beholy.detection.text.TextDetector
import com.example.beholy.util.DisposalExecutor
import com.example.beholy.util.HitLogger
import com.example.beholy.util.InAppLogger
import com.example.beholy.util.MonitorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 无障碍检测服务：事件驱动遍历视图树 → 聚合文本+包名 → 匹配词库 → 判定 tier → 调用处置执行器。
 *
 * 相比旧版截屏检测链：
 * - 事件驱动（窗口切换/内容变更/文字变更），常驻、跨锁屏无感、零轮询、无需反复授权；
 * - 直接遍历 [AccessibilityNodeInfo] 拿到 text + contentDescription，物理上拿不到屏幕像素，
 *   因此顺势移除了图像模型与 OCR 识别依赖。
 *
 * 节流：经 [MonitorState.shouldProcess] 按包名做最小处理间隔控制，避免事件风暴误命中/耗电。
 * 自身包名（com.example.beholy）直接跳过，避免自我触发。
 */
class BeHolyAccessibilityService : AccessibilityService() {

    companion object {
        /** 视图树递归遍历的最大深度，超过即停止收集，防止窗口树切换时异常导致 StackOverflowError。 */
        private const val MAX_TRAVERSAL_DEPTH = 200
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 从 AccessibilityService.getWindows 中获取当前真正的前台应用包名。
     * 排除输入法（TYPE_INPUT_METHOD）、悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）等非应用窗口。
     * 如果无法获取或出错，返回 null（调用方 fallback 到 event.packageName）。
     */
    private fun resolveForegroundAppPackage(): String? {
        return runCatching {
            windows
                ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.firstOrNull { it.isActive() }
                ?.root
                ?.packageName
                ?.toString()
        }.getOrNull()
    }

    override fun onServiceConnected() {
        // 设置服务信息（与 res/xml/beholy_accessibility_service.xml 保持一致）
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
            packageNames = null // 监听所有包
        }
        // 访问 rootInActiveWindow 的前提由 FLAG_RETRIEVE_INTERACTIVE_WINDOWS 提供

        // 提前异步加载敏感词库，避免首个事件因词库未就绪而漏检
        scope.launch { SensitiveWordDictionary.load(this@BeHolyAccessibilityService) }
        InAppLogger.i("BeHoly 无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (pkg.isNullOrEmpty()) return

        // 窗口定位修复：当有覆盖窗口（如输入法）时，用真实应用窗口的包名替代事件包名
        val detectionPkg = resolveForegroundAppPackage() ?: pkg
        val effectivePkg = detectionPkg.ifEmpty { pkg }

        // 跳过自身包名，避免自我触发
        if (pkg == packageName) return

        // 跳过系统应用与用户配置的白名单包名
        if (Constants.SKIP_PACKAGE_PREFIXES.any { pkg.startsWith(it) }) return
        if (pkg in Constants.SKIP_PACKAGE_NAMES) return

        // 词库未就绪：触发加载后本事件跳过（下一个事件即可命中）
        if (!SensitiveWordDictionary.isLoaded) {
            scope.launch { SensitiveWordDictionary.load(this@BeHolyAccessibilityService) }
            return
        }

        // 同包节流：未达最小处理间隔则跳过，避免事件风暴
        if (!MonitorState.shouldProcess(pkg)) return

        try {
            // 递归收集当前窗口所有节点的 text + contentDescription
            val texts = mutableListOf<String>()
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    collectText(root, texts)
                } finally {
                    // 遍历完成后释放 root 节点，避免节点泄漏
                    root.recycle()
                }
            }
            if (texts.isEmpty()) return

            MonitorState.lastForegroundPackage = effectivePkg

            // 文本匹配敏感词库
            val matched = TextDetector.detect(texts, SensitiveWordDictionary)
            if (matched.isEmpty()) return

            val baseTier = TierClassifier.classify(effectivePkg, matched)
            if (baseTier == Constants.TIER_NONE) return

            // 记录命中并基于同包累计命中数升级等级
            MonitorState.recordHit(effectivePkg)
            val tier = MonitorState.classifyTier(effectivePkg, baseTier)
            val reason = buildReason(matched)

            HitLogger.log(this, "检测命中")
            InAppLogger.w("检测到命中：包=$effectivePkg 等级=T$tier 词=${matched.joinToString("、")}")

            val result = DetectionResult(
                timestamp = System.currentTimeMillis(),
                tier = tier,
                packageName = effectivePkg,
                matchedWords = matched,
                source = Constants.SOURCE_TEXT,
                recognizedText = texts.joinToString(separator = " | "),
                reason = reason
            )

            // 交给处置执行器统一协调 HOME / 封禁 / 锁屏 / 重启 / 悔改
            DisposalExecutor.execute(this, this, result)
        } catch (t: Throwable) {
            // 顶层保护：无障碍服务与 MainActivity 同进程，任何未捕获 Throwable 都会打挂整个进程 → 闪退。
            // 此处吞掉异常、仅忽略本次事件，避免反复复现崩溃。
            InAppLogger.e("onAccessibilityEvent 异常，已忽略本次事件避免进程崩溃", t)
        }
    }

    override fun onInterrupt() {
        // 无障碍服务被系统中断时无需特殊处理
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 递归收集节点文本（text 与 contentDescription）。
     * @param node 当前节点（可为 null，调用方负责 recycle root）
     * @param out 文本收集容器
     */
    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int = 0) {
        if (node == null || depth > MAX_TRAVERSAL_DEPTH) return
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, depth + 1)
        }
    }

    /** 由命中词构造悔改页展示文案。 */
    private fun buildReason(matched: List<String>): String =
        "敏感文字：${matched.joinToString("、")}"
}
