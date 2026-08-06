package com.example.beholy.util

import android.content.Context
import com.example.beholy.data.db.AppDatabase
import com.example.beholy.data.db.HitRecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 一次性迁移器：将旧版 detection_log.txt 中的"检测命中"记录迁移到 Room 数据库。
 *
 * 旧版 [HitLogger] 把"启动监控/停止监控/检测命中/无障碍开启/关闭/处置完成"全部混存于
 * detection_log.txt，且格式为 `时间 | 类型 | 详情`，不含包名/等级/命中词等结构化字段。
 * 迁移时只能还原时间戳，其余字段填占位值（packageName="(已迁移)"、tier=0、matchedWords=""）。
 *
 * 幂等：用 SharedPreferences 标记是否已迁移，避免重复执行。
 * 已有数据库记录时跳过迁移（防止覆盖用户在使用 DB 版本后产生的新数据）。
 *
 * 迁移完成后，旧 detection_log.txt 保留不删除（仍含操作日志，主界面日志展示仍读取它）。
 */
object JsonlToDbMigrator {

    private const val PREFS = "beholy_migration"
    private const val KEY_HITS_MIGRATED = "hits_migrated_to_db"

    /**
     * 若尚未迁移，将旧版 detection_log.txt 中的"检测命中"记录导入 hit_records 表。
     * 安全可多次调用：已迁移则直接返回。
     */
    suspend fun migrateIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_HITS_MIGRATED, false)) return

        withContext(Dispatchers.IO) {
            runCatching {
                val dao = AppDatabase.get(context).hitDao()
                // 已有数据则跳过（DB 版本使用后产生的新数据，不应被旧日志覆盖）
                if (dao.count() > 0) {
                    prefs.edit().putBoolean(KEY_HITS_MIGRATED, true).apply()
                    return@runCatching
                }

                val file = File(context.filesDir, "detection_log.txt")
                if (!file.exists()) {
                    prefs.edit().putBoolean(KEY_HITS_MIGRATED, true).apply()
                    return@runCatching
                }

                val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                var migrated = 0
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (!trimmed.contains("| 检测命中")) continue

                        // 旧格式：`yyyy-MM-dd HH:mm:ss | 检测命中`
                        val tsPart = trimmed.substringBefore(" |").trim()
                        val ts = runCatching { timeFmt.parse(tsPart)?.time ?: 0L }.getOrDefault(0L)
                        if (ts == 0L) continue

                        dao.insert(
                            HitRecordEntity(
                                timestamp = ts,
                                packageName = "(已迁移)",
                                tier = 0,
                                matchedWords = "",
                                hitCount = 0
                            )
                        )
                        migrated++
                    }
                }

                InAppLogger.i("命中记录迁移完成：从 detection_log.txt 导入 $migrated 条到数据库")
                prefs.edit().putBoolean(KEY_HITS_MIGRATED, true).apply()
            }.onFailure { t ->
                InAppLogger.e("命中记录迁移失败", t)
                // 失败不标记已迁移，下次启动重试
            }
        }
    }
}
