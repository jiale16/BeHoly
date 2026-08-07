package com.example.beholy.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.beholy.data.Constants
import com.example.beholy.data.DetectionResult
import com.example.beholy.data.SensitiveWordDictionary
import com.example.beholy.data.db.AppDatabase
import com.example.beholy.data.db.HitRecordEntity
import com.example.beholy.detection.TierClassifier
import com.example.beholy.ui.AccessibilityGuardActivity
import com.example.beholy.detection.text.TextDetector
import com.example.beholy.util.DisposalExecutor
import com.example.beholy.util.HitLogger
import com.example.beholy.util.InAppLogger
import com.example.beholy.util.MaskUtils
import com.example.beholy.util.MonitorState
import com.example.beholy.util.StreakStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 无障碍检测服务:事件驱动遍历视图树 → 聚合文本+包名 → 匹配词库 → 判定 tier → 调用处置执行器。
 *
 * 相比旧版截屏检测链:
 * - 事件驱动(窗口切换/内容变更/文字变更),常驻、跨锁屏无感、零轮询、无需反复授权;
 * - 直接遍历 [AccessibilityNodeInfo] 拿到 text + contentDescription,物理上拿不到屏幕像素,
 *   因此顺势移除了图像模型与 OCR 识别依赖。
 *
 * 节流:经 [MonitorState.shouldProcess] 按包名做最小处理间隔控制,避免事件风暴误命中/耗电。
 * 自身包名(com.example.beholy)直接跳过,避免自我触发。
 *
 * 线程模型:
 * - [onAccessibilityEvent] 在主线程做轻量判断(包名过滤、节流);
 * - 重活(视图树遍历、词库匹配)post 到 [Dispatchers.IO],避免主线程卡顿掉帧;
 * - [AccessibilityNodeInfo] 跨线程安全(Android 文档保证),可在 IO 线程遍历。
 */
class BeHolyAccessibilityService : AccessibilityService() {

    companion object {
        /** 单次扫描最多收集的节点数,防止异常视图树导致 OOM。原 MAX_TRAVERSAL_DEPTH=200 改为节点数上限。 */
        private const val MAX_NODES = 5000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 串行化「查询当天命中数 → 写入命中记录」过程，避免并发事件同时读取相同的
     * todayCount 后都写入相同的 hitCount（如两个事件同时显示「第3次」）。
     */
    private val hitMutex = Mutex()

    /**
     * 从 AccessibilityService.getWindows 中获取当前真正的前台应用包名。
     * 排除输入法(TYPE_INPUT_METHOD)、悬浮窗(TYPE_ACCESSIBILITY_OVERLAY)等非应用窗口。
     * 如果无法获取或出错,返回 null(调用方 fallback 到 event.packageName)。
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
        // 设置服务信息(与 res/xml/beholy_accessibility_service.xml 保持一致)
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

        // 提前异步加载敏感词库,避免首个事件因词库未就绪而漏检
        scope.launch { SensitiveWordDictionary.load(this@BeHolyAccessibilityService) }
        InAppLogger.i("BeHoly 无障碍服务已连接")
        // 记录无障碍权限「开启」到监控记录(用户开启本服务时触发)
        HitLogger.log(this, "无障碍开启")
        // 连胜:今天被视为受守护的一天(连续或重新开始)
        StreakStore.recordActiveToday(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (pkg.isNullOrEmpty()) return

        // 窗口定位修复:当有覆盖窗口(如输入法)时,用真实应用窗口的包名替代事件包名
        val detectionPkg = resolveForegroundAppPackage() ?: pkg
        val effectivePkg = detectionPkg.ifEmpty { pkg }

        // 跳过自身包名,避免自我触发。
        // 注意:必须同时检查 effectivePkg(解析出的真实前台窗口包名)。
        // 否则当覆盖窗口(输入法等)导致 event.packageName ≠ 前台包名时,
        // 会从本应用自身的窗口(如显示敏感词的「悔改提醒页」)误命中自己。
        if (pkg == packageName || effectivePkg == packageName) return

        // ★ 持续打断守卫(非 DO 下的核心打断手段):
        // 命中后 [DisposalExecutor] 已设置阻断期(30秒/2分钟/5分钟,按累计次数递进)。
        // 阻断期内若违规包回前台,立刻 HOME 回桌面,打断其继续浏览。
        // 这是非 DO 下唯一有效的打断手段——DO 下有封禁+锁屏,非 DO 下只能靠持续 HOME。
        // 放在「跳过自身」之后、「设置页守卫」之前——设置页仍可进入(守卫照常生效)。
        // 放在「系统/白名单跳过」之前——即便违规包是系统前缀(如浏览器),阻断期检查不依赖检测,只比包名。
        val blockPkg = MonitorState.getBlockPackage()
        if (blockPkg != null && (pkg == blockPkg || effectivePkg == blockPkg)) {
            runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
            // 重新计时阻断期：用户每次试图绕过都重新等待完整阻断期，避免「熬过剩余时间即可」
            MonitorState.renewBlock()
            val remaining = MonitorState.getBlockRemainingSec()
            InAppLogger.i("阻断期内违规包回前台,已 HOME 并重新计时:$blockPkg,当前剩余 ${remaining} 秒")
            return  // 不做检测,避免重复触发悔改链路
        }

        // ★ 无障碍关闭劝诫守卫:服务已开启时,若用户打开系统设置的无障碍页面
        // (意图关闭本服务),弹劝诫警告,在「动手前」给一个转向神的停顿。
        // 必须在下方「系统/白名单跳过」之前拦截——否则 com.android.settings
        // 会被 SKIP_PACKAGE_PREFIXES 直接跳过,无法进入检测。
        if (isSettingsPackage(pkg) || isSettingsPackage(effectivePkg)) {
            tryDetectAccessibilityOffIntent()
            return
        }

        // ★ 强制检测名单(浏览器等高危应用):即便匹配下方系统/白名单跳过规则,也仍要检测。
        // 否则 com.android.browser 会因其「com.android 系统前缀」被一并跳过,导致浏览器失守。
        val forceDetect = pkg in Constants.FORCE_DETECT_PACKAGE_NAMES
                || effectivePkg in Constants.FORCE_DETECT_PACKAGE_NAMES

        if (!forceDetect) {
            // 跳过系统应用与用户配置的白名单包名。
            // 同样需对 effectivePkg 一并判断,原因同上。
            if (Constants.SKIP_PACKAGE_PREFIXES.any { pkg.startsWith(it) || effectivePkg.startsWith(it) }) return
            if (pkg in Constants.SKIP_PACKAGE_NAMES || effectivePkg in Constants.SKIP_PACKAGE_NAMES) return
            if (Constants.SKIP_PACKAGE_CONTAINS.any { pkg.contains(it, ignoreCase = true) || effectivePkg.contains(it, ignoreCase = true) }) return
        }

        // 词库未就绪:触发加载后本事件跳过(下一个事件即可命中)
        if (!SensitiveWordDictionary.isLoaded) {
            scope.launch { SensitiveWordDictionary.load(this@BeHolyAccessibilityService) }
            return
        }

        // 同包节流:未达最小处理间隔则跳过,避免事件风暴
        if (!MonitorState.shouldProcess(pkg)) return

        // ★ 主线程只做轻量判断;重活(视图树遍历 + 词库匹配)post 到 IO 池,
        // 避免大视图树或大文本匹配卡顿主线程导致掉帧。
        // rootInActiveWindow 在主线程获取后传入 IO 线程遍历(NodeInfo 跨线程安全)。
        val root = rootInActiveWindow ?: return
        scope.launch {
            processEventOnIo(root, effectivePkg)
        }
    }

    /**
     * 在 IO 线程处理单个无障碍事件:遍历视图树 → 匹配词库 → 分级 → 处置。
     * [root] 在主线程获取后传入;本方法负责遍历完成后 recycle 所有节点(含 root)。
     */
    private suspend fun processEventOnIo(root: AccessibilityNodeInfo, effectivePkg: String) {
        try {
            val texts = mutableListOf<String>()
            // collectText 内部迭代遍历并在 finally 中 recycle 所有节点(含 root)
            collectText(root, texts)
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

            // 串行化「查询当天命中数 → 写入命中记录 → 构造 DetectionResult」，
            // 保证并发事件拿到的 hitCount 连续递增， repentance/统计 都使用正确的累计次数。
            val result = hitMutex.withLock {
                // 悔改次数以当天总命中计（跨所有应用），而非按应用分别计数
                val todayCount = runCatching {
                    AppDatabase.get(this).hitDao().countToday()
                }.getOrDefault(0)
                val hitCount = todayCount + 1
                val timestamp = System.currentTimeMillis()

                HitLogger.log(this, "检测命中")
                InAppLogger.w("检测到命中:包=$effectivePkg 等级=T$tier 第${hitCount}次 词=${matched.joinToString("、", transform = { MaskUtils.maskWord(it) })}")

                // 命中记录写入 Room 数据库（结构化存储，供统计页查询）
                runCatching {
                    AppDatabase.get(this).hitDao().insert(
                        HitRecordEntity(
                            timestamp = timestamp,
                            packageName = effectivePkg,
                            tier = tier,
                            matchedWords = matched.joinToString(","),
                            hitCount = hitCount
                        )
                    )
                }.onFailure { t ->
                    InAppLogger.e("命中记录写入数据库失败", t)
                }

                DetectionResult(
                    timestamp = timestamp,
                    tier = tier,
                    packageName = effectivePkg,
                    matchedWords = matched,
                    source = Constants.SOURCE_TEXT,
                    recognizedText = texts.joinToString(separator = " | "),
                    reason = reason,
                    hitCount = hitCount
                )
            }

            // 交给处置执行器统一协调 HOME / 封禁 / 锁屏 / 重启 / 悔改
            DisposalExecutor.execute(this, this, result)
        } catch (t: Throwable) {
            // 顶层保护:无障碍服务与 MainActivity 同进程,任何未捕获 Throwable 都会打挂整个进程 → 闪退。
            // 此处吞掉异常、仅忽略本次事件,避免反复复现崩溃。
            InAppLogger.e("onAccessibilityEvent 异常,已忽略本次事件避免进程崩溃", t)
        }
    }

    override fun onInterrupt() {
        // 无障碍服务被系统中断时无需特殊处理
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 服务被系统禁用:记录无障碍权限「关闭」到监控记录,再取消协程作用域
        HitLogger.log(this, "无障碍关闭")
        // 连胜中断:用户关闭守护,连胜清零
        StreakStore.breakStreak(this)
        InAppLogger.i("BeHoly 无障碍服务已断开(用户关闭)")
        scope.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 迭代遍历节点树收集文本(text 与 contentDescription)。
     *
     * 相比旧版递归实现:
     * - 迭代(显式栈)避免视图树过深时 StackOverflowError;
     * - 遍历过程中收集所有获得的 [AccessibilityNodeInfo](含中间子节点),统一 recycle,
     *   避免节点池耗尽导致后续事件拿不到 root(每个获得的 NodeInfo 都必须 recycle)。
     *
     * 安全阀:[MAX_NODES] 限制单次扫描收集的节点数,防止异常视图树导致 OOM。
     *
     * @param root 根节点(本方法负责 recycle,调用方无需再 recycle)
     * @param out 文本收集容器
     */
    private fun collectText(root: AccessibilityNodeInfo, out: MutableList<String>) {
        val allNodes = ArrayList<AccessibilityNodeInfo>(64)
        allNodes.add(root)
        try {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
                node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
                // 安全阀:节点数超限则停止扩展(已收集的文本仍参与本次匹配)
                if (allNodes.size >= MAX_NODES) continue
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    stack.add(child)
                    allNodes.add(child)
                }
            }
        } finally {
            // 统一 recycle 所有获得的节点(含 root),避免节点泄漏
            allNodes.forEach { runCatching { it.recycle() } }
        }
    }

    /** 由命中词构造悔改页展示文案(命中词做掩码,只留首字)。 */
    private fun buildReason(matched: List<String>): String =
        "敏感文字:${matched.joinToString("、", transform = { MaskUtils.maskWord(it) })}"

    /**
     * 判断包名是否为系统设置(意图识别用户打开了设置页去关闭无障碍)。
     * 覆盖标准包与主流国产 ROM 设置包。
     */
    private fun isSettingsPackage(pkg: String): Boolean =
        pkg == "com.android.settings"
            || pkg.startsWith("com.android.settings.")
            || pkg.startsWith("com.samsung.android.settings")
            || pkg.startsWith("com.miui.settings")
            || pkg.startsWith("com.coloros.settings")
            || pkg.startsWith("com.oplus.settings")
            || pkg.startsWith("com.huawei.settings")

    /**
     * 无障碍关闭劝诫守卫核心:
     * 仅在设置页确实列出了本服务(页面文本含「BeHoly」)时才弹警告,
     * 避免普通设置页误触发。冷却由 [MonitorState] 控制,防止事件风暴反复弹出。
     *
     * 遍历视图树的重活 post 到 IO 池,避免主线程卡顿。
     */
    private fun tryDetectAccessibilityOffIntent() {
        if (!MonitorState.shouldShowA11yGuard()) return

        val root = rootInActiveWindow ?: return
        scope.launch {
            try {
                val texts = mutableListOf<String>()
                collectText(root, texts)

                // 仅当无障碍设置页列出了本服务(用户正准备关闭)才提醒
                if (!texts.any { it.contains("BeHoly", ignoreCase = true) }) return@launch

                MonitorState.markA11yGuardShown()
                InAppLogger.w("检测到用户打开无障碍设置,弹出关闭劝诫警告")

                val intent = Intent(this@BeHolyAccessibilityService, AccessibilityGuardActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { startActivity(intent) }
            } catch (t: Throwable) {
                InAppLogger.e("tryDetectAccessibilityOffIntent 异常", t)
            }
        }
    }
}
