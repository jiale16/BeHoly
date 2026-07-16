package com.example.beholy.util

import com.example.beholy.data.Constants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [MonitorState] 单元测试（纯 JVM）。
 *
 * 覆盖三类职责（见增量设计 §2.1 / §3）：
 * 1. 同包节流 [shouldProcess]（窗口 = [Constants.ACCESSIBILITY_SCAN_THROTTLE_MS]）；
 * 2. 冷静期 [setCooldown]/[isInCooldown]/[getCooldownPackage]/[clearCooldown]；
 * 3. 同包累计命中 [recordHit] + 升级 [classifyTier]
 *    （阈值 [Constants.TIER2_HIT_THRESHOLD]=3 → Tier2，[Constants.TIER3_HIT_THRESHOLD]=5 → Tier3）。
 *
 * [MonitorState] 为进程内单例 object，用例间通过反射在 @Before/@After 重置状态，避免串扰。
 */
class MonitorStateTest {

    @Before
    fun setUp() = resetState()

    @After
    fun tearDown() = resetState()

    // ===================== 1. 同包节流 =====================

    @Test
    fun shouldProcess_firstCallAllowed_secondImmediateCallThrottled() {
        assertTrue(MonitorState.shouldProcess("pkg.a"))
        assertFalse(MonitorState.shouldProcess("pkg.a")) // 立即再次调用被节流
        assertTrue(MonitorState.shouldProcess("pkg.b")) // 不同包独立计时
    }

    @Test
    fun shouldProcess_allowedAgainAfterThrottleWindow() {
        assertTrue(MonitorState.shouldProcess("pkg.c"))
        Thread.sleep(Constants.ACCESSIBILITY_SCAN_THROTTLE_MS + 50)
        assertTrue(MonitorState.shouldProcess("pkg.c"))
    }

    // ===================== 2. 冷静期 =====================

    @Test
    fun cooldown_zeroDuration_expiresImmediately() {
        MonitorState.setCooldown(0L, "pkg.x")
        assertFalse(MonitorState.isInCooldown())
        assertNull(MonitorState.getCooldownPackage())
    }

    @Test
    fun cooldown_activeThenExpires() {
        MonitorState.setCooldown(1000L, "pkg.y")
        assertTrue(MonitorState.isInCooldown())
        assertEquals("pkg.y", MonitorState.getCooldownPackage())
        Thread.sleep(1050)
        assertFalse(MonitorState.isInCooldown())
        assertNull(MonitorState.getCooldownPackage())
    }

    @Test
    fun clearCooldown_resetsState() {
        MonitorState.setCooldown(1000L, "pkg.z")
        MonitorState.clearCooldown()
        assertFalse(MonitorState.isInCooldown())
        assertNull(MonitorState.getCooldownPackage())
    }

    // ===================== 3. 累计升级 =====================

    @Test
    fun recordHit_andClassifyTier_upgradesByThreshold() {
        val pkg = "upgrade.pkg"
        // 第 1 次：count=1 < 3 → 保持 base(TIER1)
        MonitorState.recordHit(pkg)
        assertEquals(Constants.TIER1, MonitorState.classifyTier(pkg, Constants.TIER1))
        // 累计到 2 次：仍 < 3
        MonitorState.recordHit(pkg)
        assertEquals(Constants.TIER1, MonitorState.classifyTier(pkg, Constants.TIER1))
        // 第 3 次：count=3 >= TIER2_HIT_THRESHOLD → Tier2
        MonitorState.recordHit(pkg)
        assertEquals(Constants.TIER2, MonitorState.classifyTier(pkg, Constants.TIER1))
        // 第 4 次：3 <= count < 5 → 仍 Tier2
        MonitorState.recordHit(pkg)
        assertEquals(Constants.TIER2, MonitorState.classifyTier(pkg, Constants.TIER1))
        // 第 5 次：count=5 >= TIER3_HIT_THRESHOLD → Tier3
        MonitorState.recordHit(pkg)
        assertEquals(Constants.TIER3, MonitorState.classifyTier(pkg, Constants.TIER1))
    }

    @Test
    fun classifyTier_baseTierNeverLoweredByUpgrade() {
        val pkg = "base.pkg"
        MonitorState.recordHit(pkg) // count=1
        // base 已是 Tier3，升级逻辑不应把它拉低
        assertEquals(Constants.TIER3, MonitorState.classifyTier(pkg, Constants.TIER3))
    }

    @Test
    fun classifyTier_upgradeRespectsMaxWithBase() {
        val pkg = "max.pkg"
        repeat(5) { MonitorState.recordHit(pkg) } // count=5 → Tier3
        // base=TIER2，max(2,3)=3
        assertEquals(Constants.TIER3, MonitorState.classifyTier(pkg, Constants.TIER2))
    }

    // ===== 反射重置单例状态（避免用例间串扰）=====
    private fun resetState() {
        val instance = MonitorState
        val clazz = MonitorState::class.java
        clazz.getDeclaredField("lastProcessMs").apply { isAccessible = true }
            .let { (it.get(instance) as MutableMap<*, *>).clear() }
        clazz.getDeclaredField("hitTimestamps").apply { isAccessible = true }
            .let { (it.get(instance) as MutableMap<*, *>).clear() }
        clazz.getDeclaredField("cooldownUntil").apply { isAccessible = true }
            .set(instance, 0L)
        clazz.getDeclaredField("cooldownPackage").apply { isAccessible = true }
            .set(instance, "")
        clazz.getDeclaredField("lastForegroundPackage").apply { isAccessible = true }
            .set(instance, "")
    }
}
