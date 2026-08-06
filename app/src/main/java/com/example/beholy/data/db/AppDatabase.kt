package com.example.beholy.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 应用主数据库（Room）。
 *
 * 当前仅包含命中记录表 [HitRecordEntity]；悔改记录仍使用 JSONL 存储
 * （见 [com.example.beholy.util.RepentanceStore]），数据量小且已稳定。
 *
 * 单例化：进程内共享一个实例，避免多实例锁竞争。
 * 迁移：从旧版 detection_log.txt 迁移历史命中记录的逻辑见
 * [com.example.beholy.util.JsonlToDbMigrator]，在 [com.example.beholy.BeHolyApp.onCreate] 触发。
 */
@Database(
    entities = [HitRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun hitDao(): HitDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "beholy.db"
                ).build().also { INSTANCE = it }
            }
    }
}
