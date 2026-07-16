package com.example.beholy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 常量自洽性测试（纯 JVM，无 Android 依赖）。
 *
 * 验证分级处置阈值、冷静期、扫描节流、通知与 extra key 等常量值正确，
 * 以及 action/extra 字符串非空且互不冲突。所有常量集中定义在 [Constants]，
 * 业务代码不应硬编码魔数（见增量设计 §8 跨文件共享约定）。
 */
class ConstantsTest {

    @Test
    fun tierConstants_haveExpectedValues() {
        assertEquals(0, Constants.TIER_NONE)
        assertEquals(1, Constants.TIER1)
        assertEquals(2, Constants.TIER2)
        assertEquals(3, Constants.TIER3)
    }

    @Test
    fun sourceConstants_areExpectedStrings() {
        assertEquals("text", Constants.SOURCE_TEXT)
        assertEquals("package", Constants.SOURCE_PACKAGE)
        assertTrue(Constants.SOURCE_TEXT.isNotEmpty())
        assertTrue(Constants.SOURCE_PACKAGE.isNotEmpty())
    }

    @Test
    fun cooldownAndThresholdConstants_matchDesign() {
        // 设计文档：冷静期 5 分钟、Tier2 阈值 3 次、Tier3 阈值 5 次、窗口 10 分钟、扫描节流 800ms
        assertEquals(300_000L, Constants.COOLDOWN_PERIOD_MS)
        assertEquals(3, Constants.TIER2_HIT_THRESHOLD)
        assertEquals(5, Constants.TIER3_HIT_THRESHOLD)
        assertEquals(600_000L, Constants.TIER_WINDOW_MS)
        assertEquals(800L, Constants.ACCESSIBILITY_SCAN_THROTTLE_MS)
    }

    @Test
    fun notificationConstants_areValid() {
        assertEquals(1001, Constants.NOTIFICATION_ID)
        assertEquals(1002, Constants.HIT_NOTIFICATION_ID)
        assertTrue(Constants.NOTIFICATION_CHANNEL_ID.isNotEmpty())
    }

    @Test
    fun extraKeys_areNonEmptyAndDistinct() {
        assertTrue(Constants.EXTRA_REASON.isNotEmpty())
        assertTrue(Constants.EXTRA_HIT_TIME.isNotEmpty())
        assertTrue(Constants.EXTRA_REASON != Constants.EXTRA_HIT_TIME)
    }

    @Test
    fun miscConstants_areNonEmpty() {
        assertTrue(Constants.SENSITIVE_WORDS_FILE.isNotEmpty())
        assertTrue(Constants.LOG_TAG.isNotEmpty())
        assertEquals("sensitive_words.txt", Constants.SENSITIVE_WORDS_FILE)
    }
}
