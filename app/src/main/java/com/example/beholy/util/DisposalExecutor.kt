package com.example.beholy.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import com.example.beholy.data.Constants
import com.example.beholy.data.DetectionResult
import com.example.beholy.service.MonitoringService
import com.example.beholy.ui.RepentanceActivity

/**
 * 分级处置执行器（object）。协调「踢回桌面 + Device Owner 动作 + 悔改提醒 + 持续打断」。
 *
 * 职责边界（见增量设计 §1.2 / 类图）：
 * - 本类**只做决策与路由**，不接触 DevicePolicyManager 细节（封装在 [DeviceOwnerHelper]）；
 * - 具体「封禁/锁屏/重启/冷静期」由 [MonitoringService]（前台服务）落地，
 *   因为后台启动 Activity、锁屏重锁等操作必须在前台服务上下文执行。
 *
 * 降级策略（非 DO 下的核心改造）：
 * - 非 Device Owner 时，Tier2/3 系统管控能力（封禁/锁屏/重启）全部失效。
 *   旧版仅做 HOME + 悔改提醒，HOME 形同虚设（点 Recent 即回）。
 * - 现版补充「持续打断」：命中后按累计次数设置阻断期（30秒/2分钟/5分钟），
 *   阻断期内 [BeHolyAccessibilityService] 检测到违规包回前台立刻 HOME，
 *   替代 DO 下缺失的锁屏/封禁能力——这是非 DO 下唯一有效的打断手段。
 * - 悔改页按累计次数递进呈现（强度递进），见 [RepentanceActivity]。
 */
object DisposalExecutor {

    /**
     * 执行处置。
     *
     * @param service 无障碍服务实例（用于 performGlobalAction 踢回桌面 + 直接 startActivity 拉起悔改页）
     * @param context 上下文（用于拉起 MonitoringService 兜底）
     * @param result 本次检测结果（含 tier / packageName / matchedWords / reason）
     */
    fun execute(
        service: AccessibilityService,
        context: Context,
        result: DetectionResult
    ) {
        val isDo = DeviceOwnerHelper.isDeviceOwner(context)
        val hitCount = result.hitCount

        if (!isDo) {
            // 非 DO：系统管控能力全部失效。补充「持续打断」：
            val blockMs = Constants.resolveFromHitCount(hitCount, Constants.BLOCK_DURATION_BY_HIT_COUNT)
            InAppLogger.w("未配置设备所有者：仅持续打断 + 悔改提醒，无法强制关闭应用")

            // ★ 非 DO 下不发前置 HOME，直接 startActivity 拉起悔改页：
            // 实测发现：若先 performGlobalAction(HOME) 再 startActivity，HOME 会把应用压到后台，
            // 系统随后对该后台 startActivity 限流（START 返回 result:100），
            // 导致悔改页要 4~5 秒后才显示（而无 HOME 时仅需 ~0.9 秒）。
            // 悔改页本身即打断手段（覆盖违规应用前台），阻断期内的持续 HOME 由
            // [BeHolyAccessibilityService] 的守卫负责，无需在此前置 HOME。
            val repentanceIntent = Intent(service, RepentanceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(Constants.EXTRA_REASON, result.reason)
                putExtra(Constants.EXTRA_HIT_TIME, result.timestamp)
                putExtra(Constants.EXTRA_HIT_COUNT, hitCount)
            }
            val launched = runCatching { service.startActivity(repentanceIntent) }.isSuccess
            if (launched) {
                InAppLogger.i("悔改页已通过无障碍服务直接拉起（第 $hitCount 次命中）")
                // 抑制金句/中性通知，切换为悔改警示通知（若服务正在运行）
                // 非 DO 路径不经过 MonitoringService.onCreate，需显式通知服务抑制金句通知
                MonitoringService.suppressNotification(context)
            } else {
                InAppLogger.e("无障碍服务 startActivity 失败，降级为 MonitoringService 通知兜底")
                MonitoringService.startShowRepentance(context, result.reason, result.timestamp, hitCount)
            }

            // ★ 顺序关键：先 startActivity 再 setBlock。
            // setBlock 内含 800ms 保护期（BLOCK_PROTECT_MS），期间 isInBlock 返回 false，
            // 避免悔改页启动时窗口切换动画中违规包短暂回前台被误 HOME（把悔改页一并踢掉）。
            if (blockMs > 0L) {
                MonitorState.setBlock(blockMs, result.packageName)
                InAppLogger.w("非设备所有者：已设置阻断期 ${blockMs / 1000} 秒（第 $hitCount 次命中），阻断期内违规包回前台将持续 HOME")
            }
            return
        }

        // DO 已配置：先踢回桌面，再交由 MonitoringService 落地系统管控
        runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }

        // DO 已配置：任何命中都强制关闭（隐藏/封禁）违规应用，再按等级追加锁屏/重启 + 悔改
        // DO 下也透传 hitCount（悔改页文案递进仍有价值，即便系统管控已落地）
        MonitoringService.startDispose(context, result.tier, result.packageName, result.matchedWords, result.reason, hitCount)
    }
}
