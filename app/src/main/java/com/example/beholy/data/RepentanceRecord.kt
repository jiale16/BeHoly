package com.example.beholy.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 悔改反思记录的数据类。
 *
 * 当用户在 [com.example.beholy.ui.RepentanceFormActivity] 中愿意安静反思时，
 * 将本次浏览命中的结构化反思保存为本地 JSONL 文件（见 [com.example.beholy.util.RepentanceStore]）。
 *
 * 字段说明：
 * - hitTime：本次命中的时间戳（来自 MonitoringService 透传的 EXTRA_HIT_TIME）。
 * - reason：检测类型，如「成人画面」「敏感文字」或「成人画面、敏感文字」组合。
 * - mood：感受心情（多选）。
 * - moodNote：心情补充（可选文本）。
 * - method：看的方法（多选，决策1 明确为多选 List<String>）。
 * - methodNote：方法说明（可选文本）。
 * - reflection：反思正文。
 * - avoidancePlan：以后避免该罪的方案。
 * - createdAt：记录创建时间，默认当前时间戳；取最后一条的 createdAt 作为「距上次」基准（决策4）。
 */
data class RepentanceRecord(
    val hitTime: Long,            // 本次命中时间戳（来自 EXTRA_HIT_TIME）
    val reason: String,           // 检测类型："成人画面" / "敏感文字" / 组合
    val mood: List<String>,       // 心情选项（多选）
    val moodNote: String,         // 心情补充
    val method: List<String>,     // 看的方法（多选）
    val methodNote: String,       // 方法说明
    val reflection: String,       // 反思
    val avoidancePlan: String,    // 以后避免方案
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * 序列化为 JSONObject（使用 Android 内置 org.json，零额外依赖）。
     * 字段名采用驼峰，与 [fromJson] 保持一致。
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("hitTime", hitTime)
            put("reason", reason)
            put("mood", listToJsonArray(mood))
            put("moodNote", moodNote)
            put("method", listToJsonArray(method))
            put("methodNote", methodNote)
            put("reflection", reflection)
            put("avoidancePlan", avoidancePlan)
            put("createdAt", createdAt)
        }
    }

    /** List<String> -> JSONArray（逐条 put，避免依赖 JSONArray(Collection) 构造差异）。 */
    private fun listToJsonArray(list: List<String>): JSONArray {
        val arr = JSONArray()
        for (item in list) arr.put(item)
        return arr
    }

    companion object {
        /**
         * 从 JSONObject 反序列化。字段缺失时给予安全默认值，避免单行解析失败导致整体崩溃。
         */
        fun fromJson(obj: JSONObject): RepentanceRecord {
            val moodList = mutableListOf<String>()
            obj.optJSONArray("mood")?.let { arr ->
                for (i in 0 until arr.length()) moodList.add(arr.optString(i, ""))
            }
            val methodList = mutableListOf<String>()
            obj.optJSONArray("method")?.let { arr ->
                for (i in 0 until arr.length()) methodList.add(arr.optString(i, ""))
            }
            return RepentanceRecord(
                hitTime = obj.optLong("hitTime", 0L),
                reason = obj.optString("reason", ""),
                mood = moodList,
                moodNote = obj.optString("moodNote", ""),
                method = methodList,
                methodNote = obj.optString("methodNote", ""),
                reflection = obj.optString("reflection", ""),
                avoidancePlan = obj.optString("avoidancePlan", ""),
                createdAt = obj.optLong("createdAt", 0L)
            )
        }
    }
}
