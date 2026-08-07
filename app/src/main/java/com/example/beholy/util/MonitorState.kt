package com.example.beholy.util

import com.example.beholy.data.Constants
import kotlin.math.max

/**
 * 进程内共享状态（object）。桥接 AccessibilityService 与 MonitoringService，
 * 避免跨服务绑定复杂度（二者同进程，见增量设计 §1.2）。
 *
 * 负责两类状态：
 * 1. 检测节流：同包最小处理间隔 [ACCESSIBILITY_SCAN_THROTTLE_MS]，避免事件风暴；
 * 2. 同包累计命中、冷静期与阻断期：
 *    - 冷静期（DO 下）：Tier2 封禁后的循环重锁，由 [screenStateReceiver] 调 lockNow 落地；
 *    - 阻断期（非 DO 下）：命中后持续打断，由 [BeHolyAccessibilityService] 在事件回调里
 *      检测违规包回前台立刻 HOME 落地，替代 DO 下缺失的锁屏能力。
 *    两者语义不同、字段独立，同一时刻最多其一生效。
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

    /** 非 DO 下的阻断期到期时间戳。0L 表示未在阻断期。 */
    @Volatile
    private var blockUntil: Long = 0L

    /** 非 DO 下的阻断期目标包名。空串表示未在阻断期。 */
    @Volatile
    private var blockPackage: String = ""

    /** 非 DO 下的阻断期原始时长（毫秒），供 [renewBlock] 重新计时使用。 */
    @Volatile
    private var blockDurationMs: Long = 0L

    /**
     * 阻断期保护期到期时间戳：setBlock 后前 [BLOCK_PROTECT_MS] 内不 HOME。
     *
     * 为什么需要保护期：命中后 DisposalExecutor 先 startActivity(悔改页) 再 setBlock。
     * 悔改页启动过程中，系统窗口切换会让违规包短暂成为 effectivePkg（窗口动画过渡），
     * 若此时立即 HOME，会把刚启动的悔改页一并踢回桌面——表现为「悔改页弹一下就消失」。
     * 保护期给悔改页 ~800ms 时间稳定成为前台，之后阻断期 HOME 才开始生效。
     */
    @Volatile
    private var blockProtectUntil: Long = 0L

    @Volatile
    private var a11yGuardShownAt: Long = 0L

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

    /** 记录一次同包命中（带时间戳，便于窗口内统计）。返回记录后的窗口内累计次数。 */
    fun recordHit(packageName: String): Int {
        synchronized(hitTimestamps) {
            val list = hitTimestamps.getOrPut(packageName) { mutableListOf() }
            val now = System.currentTimeMillis()
            list.add(now)
            // 清理超出统计窗口的旧命中
            val cutoff = now - Constants.TIER_WINDOW_MS
            list.removeAll { it < cutoff }
            return list.size
        }
    }

    /** 查询同包窗口内累计命中次数（不修改状态）。 */
    fun getHitCount(packageName: String): Int {
        synchronized(hitTimestamps) {
            return hitTimestamps[packageName]?.size ?: 0
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

    /** 进入冷静期（DO 下）：记录封禁包与到期时间。与阻断期互斥，会清除阻断期。 */
    fun setCooldown(ms: Long, pkg: String) {
        cooldownPackage = pkg
        cooldownUntil = System.currentTimeMillis() + ms
        // 互斥：冷静期生效时清除阻断期，避免两者同时生效造成语义混乱
        blockUntil = 0L
        blockPackage = ""
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

    /**
     * 进入阻断期（非 DO 下）：记录违规包与到期时间。
     * 与冷静期互斥，会清除冷静期。命中后由 [DisposalExecutor] 调用。
     * 同时设置 [BLOCK_PROTECT_MS] 保护期，期间 [isInBlock] 返回 false，
     * 给悔改页启动留出时间，避免窗口切换动画中违规包短暂回前台被误 HOME。
     */
    fun setBlock(ms: Long, pkg: String) {
        val now = System.currentTimeMillis()
        blockPackage = pkg
        blockDurationMs = ms
        blockUntil = now + ms
        blockProtectUntil = now + BLOCK_PROTECT_MS
        // 互斥：阻断期生效时清除冷静期
        cooldownUntil = 0L
        cooldownPackage = ""
    }

    /**
     * 重新计时阻断期：用户在阻断期内再次打开违规应用被 HOME 后调用。
     * 以原始时长 [blockDurationMs] 重新计算到期时间，让用户每次试图绕过都重新等待完整阻断期。
     * 不重置保护期（此时悔改页早已稳定，无需保护）。
     */
    fun renewBlock() {
        if (blockDurationMs <= 0L) return
        blockUntil = System.currentTimeMillis() + blockDurationMs
    }

    /**
     * 当前是否处于阻断期内（且已过保护期）。
     * 保护期内返回 false，让悔改页有时间稳定成为前台。
     */
    fun isInBlock(): Boolean {
        val now = System.currentTimeMillis()
        return now in blockProtectUntil..blockUntil
    }

    /** 若处于阻断期（且已过保护期），返回被阻断的包名；否则返回 null。 */
    fun getBlockPackage(): String? = if (isInBlock()) blockPackage else null

    /** 返回阻断期剩余秒数（未在阻断期时返回 0）。 */
    fun getBlockRemainingSec(): Long {
        val now = System.currentTimeMillis()
        if (now >= blockUntil) return 0L
        return (blockUntil - now) / 1000
    }

    /** 清除阻断期状态。 */
    fun clearBlock() {
        blockUntil = 0L
        blockPackage = ""
        blockDurationMs = 0L
        blockProtectUntil = 0L
    }

    /**
     * 阻断期保护期时长（毫秒）：setBlock 后此时间内 isInBlock 返回 false，
     * 让悔改页启动时窗口切换动画中的违规包短暂回前台不被误 HOME。
     */
    private const val BLOCK_PROTECT_MS: Long = 800L

    /**
     * 无障碍关闭劝诫守卫的冷却判断：距上次弹出超过 [Constants.A11Y_GUARD_COOLDOWN_MS] 才放行。
     * 返回 true 时调用方应随后调用 [markA11yGuardShown] 刷新时间戳。
     */
    fun shouldShowA11yGuard(): Boolean =
        System.currentTimeMillis() - a11yGuardShownAt > Constants.A11Y_GUARD_COOLDOWN_MS

    /** 记录本次劝诫警告已弹出，进入冷却。 */
    fun markA11yGuardShown() {
        a11yGuardShownAt = System.currentTimeMillis()
    }
}
