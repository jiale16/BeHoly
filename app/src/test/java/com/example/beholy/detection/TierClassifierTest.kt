package com.example.beholy.detection

import com.example.beholy.data.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TierClassifier.classify] 单元测试（纯 JVM）。
 *
 * 设计边界（见增量设计 §3 / §5 Q1/Q2）：
 * - [TierClassifier] 只负责**单次命中下的基础分级**（base tier）；
 * - 「同包多次命中自动升级」由 [com.example.beholy.util.MonitorState.classifyTier] 负责，
 *   对应测试见 [com.example.beholy.util.MonitorStateTest]。
 *
 * 当前默认策略：命中任意词 → Tier1；包名黑名单 / 高危词集当前为空，故无 Tier2/Tier3 直达。
 */
class TierClassifierTest {

    @Test
    fun classify_noMatchedWords_returnsTierNone() {
        assertEquals(Constants.TIER_NONE, TierClassifier.classify("com.any.app", emptyList()))
    }

    @Test
    fun classify_matchedWord_returnsBaseTier1() {
        // 默认策略：命中任意词即 Tier1（累计升级由 MonitorState 负责）
        assertEquals(Constants.TIER1, TierClassifier.classify("com.any.app", listOf("色情")))
        assertEquals(Constants.TIER1, TierClassifier.classify("com.any.app", listOf("赌博", "暴力")))
    }

    @Test
    fun classify_unknownPackage_stillBaseTier1() {
        // 当前黑名单为空，任何包名命中词都仅返回基础 Tier1
        assertEquals(Constants.TIER1, TierClassifier.classify("some.unknown.pkg", listOf("sex")))
    }
}
