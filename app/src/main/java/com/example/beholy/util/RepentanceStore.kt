package com.example.beholy.util

import android.content.Context
import android.net.Uri
import com.example.beholy.data.Constants
import com.example.beholy.data.RepentanceRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /**
     * 读取全部反思记录（按时间倒序，最新在前）。
     * 逐行解析 JSONL，单行损坏不影响其它行，文件不存在时返回空列表。
     */
    suspend fun readAll(context: Context): List<RepentanceRecord> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, Constants.REPENTANCE_FILE)
                if (!file.exists()) return@withContext emptyList<RepentanceRecord>()
                val list = mutableListOf<RepentanceRecord>()
                BufferedReader(file.bufferedReader()).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line?.trim()
                        if (text.isNullOrEmpty()) continue
                        try {
                            list.add(RepentanceRecord.fromJson(JSONObject(text)))
                        } catch (_: Exception) {
                            // 忽略损坏行
                        }
                    }
                }
                list.sortedByDescending { it.createdAt }
            }.getOrDefault(emptyList())
        }
    }

    /** 记录条数（供 UI 计数）。 */
    suspend fun count(context: Context): Int = readAll(context).size

    /**
     * 格式化为可读文本（供 UI 弹窗展示悔改日记）。
     * 每条展示：时间、检测类型、心情、方式、反思正文、以后避免方案。
     */
    suspend fun toReadableText(context: Context): String {
        val list = readAll(context)
        if (list.isEmpty()) return "暂无悔改记录\n\n愿意在软弱时停下来的那一刻，就是恩典的开始。"
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("共 ${list.size} 次回转\n\n")
        list.forEachIndexed { idx, r ->
            val time = sdf.format(Date(r.createdAt))
            sb.append("${idx + 1}. [$time] ${r.reason}\n")
            if (r.mood.isNotEmpty()) sb.append("   心情：${r.mood.joinToString("、")}\n")
            if (r.moodNote.isNotBlank()) sb.append("   ${r.moodNote}\n")
            if (r.method.isNotEmpty()) sb.append("   途径：${r.method.joinToString("、")}\n")
            if (r.methodNote.isNotBlank()) sb.append("   ${r.methodNote}\n")
            if (r.reflection.isNotBlank()) sb.append("   反思：${r.reflection}\n")
            if (r.avoidancePlan.isNotBlank()) sb.append("   以后：${r.avoidancePlan}\n")
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * 导出全部反思记录为 JSONL 文件（用于跨签名 / 换机保留数据）。
     * [target] 通常来自 [android.content.Context.getExternalFilesDir]，无需存储权限，
     * 导出的文件可在 PC 端通过 adb / USB 直接读取（Android/data/com.example.beholy/files/）。
     * 导出时按时间正序写入，便于人工查看。
     */
    suspend fun exportTo(context: Context, target: File) {
        withContext(Dispatchers.IO) {
            val records = readAll(context).sortedBy { it.createdAt }
            target.outputStream().use { os ->
                os.writer().use { w ->
                    records.forEach { w.appendLine(it.toJson().toString()) }
                }
            }
        }
    }

    /**
     * 从 JSONL 文件导入反思记录，合并进现有存储（按 [RepentanceRecord.createdAt] 去重，避免重复导入）。
     * 返回本次实际新增的条数。文件不存在或为空 → 返回 0。
     */
    suspend fun importFrom(context: Context, source: File): Int {
        return withContext(Dispatchers.IO) {
            if (!source.exists()) return@withContext 0
            val existing = readAll(context).map { it.createdAt }.toSet()
            val toAppend = mutableListOf<String>()
            BufferedReader(source.bufferedReader()).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line?.trim()
                    if (text.isNullOrEmpty()) continue
                    try {
                        val rec = RepentanceRecord.fromJson(JSONObject(text))
                        if (!existing.contains(rec.createdAt)) {
                            toAppend.add(rec.toJson().toString())
                        }
                    } catch (_: Exception) {
                        // 忽略损坏行
                    }
                }
            }
            if (toAppend.isNotEmpty()) {
                val file = File(context.filesDir, Constants.REPENTANCE_FILE)
                file.appendText(toAppend.joinToString(separator = "\n", postfix = "\n"))
            }
            toAppend.size
        }
    }

    /**
     * 通过系统文件选择器（SAF）将悔改记录导出到用户自选位置。
     * 使用 [android.content.ContentResolver.openOutputStream] 写入，无需存储权限；
     * 文件保存在用户指定的真实路径（如「下载」目录），卸载 App 后仍保留，
     * 因此可用于「换签名重装 / 换机」后恢复数据。
     */
    suspend fun exportToUri(context: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            val records = readAll(context).sortedBy { it.createdAt }
            context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                os.writer().use { w ->
                    records.forEach { w.appendLine(it.toJson().toString()) }
                }
            }
        }
    }
}
