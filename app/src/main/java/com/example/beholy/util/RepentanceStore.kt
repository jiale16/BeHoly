package com.example.beholy.util

import android.content.Context
import com.example.beholy.data.Constants
import com.example.beholy.data.RepentanceRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File

/**
 * 悔改反思记录的本地存储（JSONL 风格）。
 *
 * 设计要点：
 * - 零依赖：仅使用 Android 内置 org.json 与标准 java.io，不引入任何第三方库，也不联网（强制离线）。
 * - JSONL：文件为「每行一个 JSONObject」的纯文本（JSON Lines）。追加写时只写一行，
 *   读取时按行解析，单行损坏不影响其它行，便于用文本编辑器或 adb 直接查看。
 * - 所有文件 IO 放在 [Dispatchers.IO]；Store 内部用 withContext 兜底，确保不在主线程做磁盘操作
 *   （调用方也可在 lifecycleScope 中直接调用这些 suspend 函数，线程安全）。
 * - 文件路径：context.filesDir / [Constants.REPENTANCE_FILE]（应用私有目录，无需存储权限）。
 */
object RepentanceStore {

    /**
     * 追加保存一条反思记录：在文件末尾写入一行 [RepentanceRecord.toJson] 的字符串（含换行）。
     * 使用追加模式，天然形成 JSONL。
     */
    suspend fun save(context: Context, record: RepentanceRecord) {
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, Constants.REPENTANCE_FILE)
                file.appendText(record.toJson().toString() + "\n")
            }
        }
    }

    /**
     * 读取最后一条已保存记录（用于计算「距上次」基准，决策4）。
     * - 文件不存在或为空 → 返回 null（表示「首次」）。
     * - 逐行解析，遇到单行解析失败则跳过该行，不影响整体。
     */
    suspend fun getLast(context: Context): RepentanceRecord? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, Constants.REPENTANCE_FILE)
                if (!file.exists() || file.length() == 0L) return@withContext null
                var last: RepentanceRecord? = null
                BufferedReader(file.bufferedReader()).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line?.trim()
                        if (text.isNullOrEmpty()) continue
                        try {
                            // 单行损坏则跳过，继续解析后续行
                            last = RepentanceRecord.fromJson(JSONObject(text))
                        } catch (_: Exception) {
                            // 忽略损坏行
                        }
                    }
                }
                last
            }.getOrNull()
        }
    }

    /**
     * 计算距上次反思的时间描述。
     * 基准：上一条已保存记录的 createdAt（决策4）；无记录 → "首次"。
     *
     * 算法（向下聚合）：
     * - diff = hitTime - last.createdAt；若 diff <= 0 → "就在刚才"。
     * - 天 = diff / 86400000；时 = (diff % 86400000) / 3600000；分 = (diff % 3600000) / 60000。
     * - 去掉为 0 的单位，至少保留最小单位；按「天/时/分」顺序拼接，例如
     *   "3天2小时"、"5小时12分钟"、"8分钟"。
     *
     * 全程 try/catch 兜底，异常时返回 "—"，保证 UI 永远有可读文案。
     */
    suspend fun formatSinceLast(context: Context, hitTime: Long): String {
        return withContext(Dispatchers.IO) {
            try {
                val last = getLast(context) ?: return@withContext "首次"
                val diff = hitTime - last.createdAt
                if (diff <= 0) return@withContext "就在刚才"

                val day = diff / 86400000L
                val hour = (diff % 86400000L) / 3600000L
                val minute = (diff % 3600000L) / 60000L

                val parts = mutableListOf<String>()
                if (day > 0) parts.add("${day}天")
                if (hour > 0) parts.add("${hour}小时")
                // 至少保留最小单位：当天=0 且 时=0 时仍要显示分钟
                if (minute > 0 || parts.isEmpty()) parts.add("${minute}分钟")

                if (parts.isEmpty()) "0分钟" else parts.joinToString("")
            } catch (_: Exception) {
                "—"
            }
        }
    }
}
