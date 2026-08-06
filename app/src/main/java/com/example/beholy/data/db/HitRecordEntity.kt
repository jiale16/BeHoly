package com.example.beholy.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 检测命中记录的数据库实体（替代旧版 HitLogger 文本日志中的"检测命中"类型行）。
 *
 * 相比旧版 [com.example.beholy.util.HitLogger] 的 `时间 | 类型 | 详情` 文本格式：
 * - 字段结构化：包名、等级、命中词、累计次数独立存储，支持按包名/等级筛选与聚合；
 * - 时间精度毫秒：解决旧版仅到秒、无法按时段统计的问题；
 * - SQLite 索引查询：替代旧版每次进统计页全量读文件 + 字符串匹配，性能更稳；
 * - 单条损坏不影响其它行：Room 事务保证写入原子性。
 *
 * 旧版 [HitLogger] 仍保留，用于记录操作日志（启动/停止监控/无障碍开启/关闭/处置完成），
 * 仅"检测命中"一类记录迁移到本表。
 */
@Entity(tableName = "hit_records")
data class HitRecordEntity(
    /** 自增主键 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 命中时间戳（毫秒，System.currentTimeMillis()） */
    val timestamp: Long,
    /** 命中时前台包名 */
    val packageName: String,
    /** 分级处置强度：1/2/3 对应 Tier1/Tier2/Tier3 */
    val tier: Int,
    /** 命中的敏感词列表（CSV 字符串，便于存储与导出；读取时按 "," 拆分） */
    val matchedWords: String,
    /** 本次命中的同包累计次数（第 N 次命中，用于强度递进回溯） */
    val hitCount: Int
)
