package com.example.beholy.detection

import com.example.beholy.data.Constants

/**
 * 分级判定器（object）。
 *
 * 职责：根据「命中敏感词」与「包名」给出**基础**处置等级（base tier）。
 * 注意：本类只做单次命中下的基线判定；「同包多次命中自动升级」由
 * [com.example.beholy.util.MonitorState.classifyTier] 负责（见增量设计 §5 Q1/Q2）。
 *
 * 升级策略（默认可运行占位实现，待产品拍板后仅调参/填黑名单）：
 * - 未命中任何词 → [Constants.TIER_NONE]
 * - 命中词，但不在任何黑名单 → [Constants.TIER1]
 * - 包名命中 Tier2 黑名单 → [Constants.TIER2]
 * - 包名命中 Tier3 黑名单，或命中预留高危词 → [Constants.TIER3]
 */
object TierClassifier {

    /** 预留高危词集：命中即 Tier3（当前为空，产品可后续填充） */
    private val SEVERE_KEYWORDS: Set<String> = emptySet()

    /** 包名硬黑名单：命中直接 Tier3（当前为空） */
    private val PACKAGE_BLACKLIST_TIER3: Set<String> = emptySet()

    /** 包名硬黑名单：命中直接 Tier2（当前为空） */
    private val PACKAGE_BLACKLIST_TIER2: Set<String> = emptySet()

    /**
     * 计算基础处置等级。
     *
     * @param packageName 前台包名
     * @param matchedWords 命中的敏感词列表
     * @return [Constants.TIER_NONE] / [Constants.TIER1]..[Constants.TIER3]
     */
    fun classify(packageName: String, matchedWords: List<String>): Int {
        if (matchedWords.isEmpty()) return Constants.TIER_NONE

        if (packageName in PACKAGE_BLACKLIST_TIER3) return Constants.TIER3
        if (packageName in PACKAGE_BLACKLIST_TIER2) return Constants.TIER2
        if (matchedWords.any { it in SEVERE_KEYWORDS }) return Constants.TIER3

        return Constants.TIER1
    }
}
