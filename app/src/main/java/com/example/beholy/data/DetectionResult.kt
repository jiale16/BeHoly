package com.example.beholy.data

/**
 * 单次检测的结果封装。
 *
 * 本增量由 AccessibilityService 遍历视图树得到节点文本，匹配 [SensitiveWordDictionary]
 * 后产出本对象。相比旧版，已移除图像画面相关字段，
 * 改为以 [tier] 表达分级处置强度。
 *
 * 字段约定（见增量设计 §8.2）：
 * - [tier]：TIER_NONE(0) / TIER1..TIER3，来自 [Constants]；[isHit] = tier in 1..3。
 * - [packageName]：命中时前台包名。
 * - [matchedWords]：命中的敏感词列表（去重）。
 * - [source]：命中来源，当前恒为 [Constants.SOURCE_TEXT]。
 * - [recognizedText]：聚合的节点文本（仅用于日志/调试，不离开设备，遵循离线铁律）。
 * - [reason]：供悔改页展示的命中说明文案。
 * - [timestamp]：System.currentTimeMillis()。
 */
data class DetectionResult(
    /** 检测时间戳（毫秒） */
    val timestamp: Long = System.currentTimeMillis(),
    /** 分级处置强度：0=未命中，1/2/3 对应 Tier1/Tier2/Tier3 */
    val tier: Int = Constants.TIER_NONE,
    /** 命中时前台包名 */
    val packageName: String = "",
    /** 命中的敏感词列表（去重） */
    val matchedWords: List<String> = emptyList(),
    /** 命中来源（当前仅 SOURCE_TEXT） */
    val source: String = Constants.SOURCE_TEXT,
    /** 聚合的节点文本（便于调试/日志，不用于上传） */
    val recognizedText: String = "",
    /** 供悔改页展示的命中说明 */
    val reason: String = ""
) {
    /** 是否为「任一等级」的命中 */
    val isHit: Boolean
        get() = tier in Constants.TIER1..Constants.TIER3
}
