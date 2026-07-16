package com.example.beholy.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.example.beholy.data.DetectionResult
import com.example.beholy.service.MonitoringService

/**
 * 分级处置执行器（object）。协调「踢回桌面 + Device Owner 动作 + 悔改提醒」。
 *
 * 职责边界（见增量设计 §1.2 / 类图）：
 * - 本类**只做决策与路由**，不接触 DevicePolicyManager 细节（封装在 [DeviceOwnerHelper]）；
 * - 具体「封禁/锁屏/重启/冷静期」由 [MonitoringService]（前台服务）落地，
 *   因为后台启动 Activity、锁屏重锁等操作必须在前台服务上下文执行。
 *
 * 降级策略（离线铁律下的优雅降级）：
 * - 非 Device Owner 时，Tier2/3 退化为 Tier1 行为（仅 HOME + 悔改提示），
 *   并在 [InAppLogger] 标注「未配置设备所有者，处置降级」，引导用户在 MainActivity 配置 DO。
 */
object DisposalExecutor {

    /**
     * 执行处置。
     *
     * @param service 无障碍服务实例（用于 performGlobalAction 踢回桌面）
     * @param context 上下文（用于拉起 MonitoringService）
     * @param result 本次检测结果（含 tier / packageName / matchedWords / reason）
     */
    fun execute(
        service: AccessibilityService,
        context: Context,
        result: DetectionResult
    ) {
        // 1) 任何命中等级都先踢回桌面，打断当前浏览
        runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }

        val isDo = DeviceOwnerHelper.isDeviceOwner(context)
        if (!isDo) {
            InAppLogger.w("未配置设备所有者：仅踢回桌面 + 悔改提醒，无法强制关闭应用（Android 限制，需执行 adb dpm set-device-owner）")
            MonitoringService.startShowRepentance(context, result.reason, result.timestamp)
            return
        }
        // DO 已配置：任何命中都强制关闭（隐藏/封禁）违规应用，再按等级追加锁屏/重启 + 悔改
        MonitoringService.startDispose(context, result.tier, result.packageName, result.matchedWords, result.reason)
    }
}
