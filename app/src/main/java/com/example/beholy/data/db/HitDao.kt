package com.example.beholy.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * 命中记录的数据访问对象。
 *
 * 聚合查询使用 SQLite 原生 strftime 函数，在 DB 层完成分组计数，
 * 替代旧版 DetectionStatsActivity 的全量读文件 + 内存分组。
 */
@Dao
interface HitDao {

    /** 插入一条命中记录。 */
    @Insert
    suspend fun insert(hit: HitRecordEntity): Long

    /** 命中记录总数。 */
    @Query("SELECT COUNT(*) FROM hit_records")
    suspend fun count(): Int

    /**
     * 按天聚合（本地时区）：返回每天命中次数，按日期升序。
     * timestamp 存储为毫秒，除以 1000 转秒后用 strftime 格式化为 yyyy-MM-dd。
     */
    @Query(
        "SELECT strftime('%Y-%m-%d', timestamp/1000, 'unixepoch', 'localtime') AS date, " +
            "COUNT(*) AS count " +
            "FROM hit_records GROUP BY date ORDER BY date ASC"
    )
    suspend fun aggregateByDay(): List<DateCount>

    /** 全量查询（按时间倒序），用于统计页"命中列表"展示。 */
    @Query("SELECT * FROM hit_records ORDER BY timestamp DESC")
    suspend fun queryAll(): List<HitRecordEntity>

    /**
     * 查询指定包名「今天」（本地时区）的命中次数。
     * 用 SQLite strftime 将 timestamp 毫秒转本地日期，与当前本地日期比对。
     * 供悔改页「第 N 次命中」展示与阻断期时长递进使用。
     */
    @Query(
        "SELECT COUNT(*) FROM hit_records " +
            "WHERE packageName = :pkg " +
            "AND strftime('%Y-%m-%d', timestamp/1000, 'unixepoch', 'localtime') = " +
            "strftime('%Y-%m-%d', 'now', 'localtime')"
    )
    suspend fun countTodayByPackage(pkg: String): Int

    /** 清空所有命中记录。 */
    @Query("DELETE FROM hit_records")
    suspend fun clear()
}

/** 聚合查询结果：日期 + 命中次数。字段名与 SQL 别名一致，便于 Room 自动映射。 */
data class DateCount(
    val date: String,
    val count: Int
)
