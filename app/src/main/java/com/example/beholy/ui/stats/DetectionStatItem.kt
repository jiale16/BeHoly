package com.example.beholy.ui.stats

/**
 * 检测命中统计项：日期 + 命中次数
 */
data class DetectionStatItem(
    val date: String, // yyyy-MM-dd
    val count: Int,
    val weekStart: String?, // 周视图时，该周的起始日期（周一）
    val month: String // 月视图时，该月的 yyyy-MM
)

/**
 * 按时间维度聚合的命中统计数据
 */
data class DetectionStats(
    val period: String, // "2026-07-31" / "2026-W31" / "2026-07"
    val totalHits: Int,
    val dailyStats: List<DetectionStatItem>
)
