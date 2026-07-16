package com.example.beholy.util

import com.example.beholy.data.Constants
import kotlin.math.max

/**
 * 进程内共享状态（object）。桥接 AccessibilityService 与 MonitoringService，
 * 避免跨服务绑定复杂度（二者同进程，见增量设计 §1.2）。
 *
 * 负责两类状态：
 * 1. 检测节流：同包最小处理间隔 [ACCESSIBILITY_SCAN_THROTTLE_MS]，避免事件风暴；
 * 2. 同包累计命中与冷静期：用于「累计命中自动升级 Tier」与「Tier2 冷静期重锁」。
 *
 * 线程安全：写操作均为单线程（AccessibilityService 事件上下文 / MonitoringService 主线程），
 * 但为稳妥对共享 Map 加 synchronized，并对跨线程可见字段使用 @Volatile。
 */
object MonitorState {

    /** 最近一次前台包名（AccessibilityService 每次窗口事件写入） */
    @Volatile
    var lastForegroundPackage: String = ""

    private val lastProcessMs: MutableMap<String, Long> = mutableMapOf()
    private val hitTimestamps: MutableMap<String, MutableList<Long>> = mutableMapOf()

    @Volatile
    private var cooldownUntil: Long = 0L

    @Volatile
    private var cooldownPackage: String = ""

    /**
     * 同包节流判断：距上次处理该包超过 [Constants.ACCESSIBILITY_SCAN_THROTTLE_MS] 才放行。
     * 返回 true 时内部会刷新该包的时间戳。
     */
    fun shouldProcess(packageName: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(lastProcessMs) {
            val last = lastProcessMs[packageName] ?: 0L
            if (now - last < Constants.ACCESSIBILITY_SCAN_THROTTLE_MS) return false
            lastProcessMs[packageName] = now
            return true
        }
    }

    /** 记录一次同包命中（带时间戳，便于窗口内统计）。 */
    fun recordHit(packageName: String) {
        synchronized(hitTimestamps) {
            val list = hitTimestamps.getOrPut(packageName) { mutableListOf() }
            list.add(System.currentTimeMillis())
            // 清理超出统计窗口的旧命中
            val cutoff = System.currentTimeMillis() - Constants.TIER_WINDOW_MS
            list.removeAll { it < cutoff }
        }
    }

    /**
     * 在同包累计命中基础上，对基础等级做升级。
     * - 累计命中 >= [Constants.TIER3_HIT_THRESHOLD] → Tier3
     * - 累计命中 >= [Constants.TIER2_HIT_THRESHOLD] → Tier2
     * - 否则保持 [baseTier]
     * 最终取 max(baseTier, 升级后等级)，保证高危词/包名黑名单不会被累计逻辑拉低。
     */
    fun classifyTier(packageName: String, baseTier: Int): Int {
        synchronized(hitTimestamps) {
            val count = hitTimestamps[packageName]?.size ?: 0
            val upgraded = when {
                count >= Constants.TIER3_HIT_THRESHOLD -> Constants.TIER3
                count >= Constants.TIER2_HIT_THRESHOLD -> Constants.TIER2
                else -> baseTier
            }
            return max(baseTier, upgraded)
        }
    }

    /** 进入冷静期：记录封禁包与到期时间。 */
    fun setCooldown(ms: Long, pkg: String) {
        cooldownPackage = pkg
        cooldownUntil = System.currentTimeMillis() + ms
    }

    /** 当前是否处于冷静期内。 */
    fun isInCooldown(): Boolean = System.currentTimeMillis() < cooldownUntil

    /** 若处于冷静期，返回被封禁的包名；否则返回 null。 */
    fun getCooldownPackage(): String? = if (isInCooldown()) cooldownPackage else null

    /** 清除冷静期状态。 */
    fun clearCooldown() {
        cooldownUntil = 0L
        cooldownPackage = ""
    }
}
