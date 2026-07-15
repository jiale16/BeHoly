package com.example.beholy.data

import android.content.Context
import android.util.Log
import com.example.beholy.util.InAppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.LinkedHashSet

/**
 * 本地敏感词库（离线）。
 *
 * 实现说明：
 * - 词库文件位于 assets/sensitive_words.txt，每行一个词，`#` 开头为注释行；
 * - 使用 [BufferedReader] 逐行读取，避免一次性载入超大文件造成内存峰值；
 * - 初始化建议在 IO 协程中执行（本类 [load] 内部已切到 Dispatchers.IO）；
 * - 匹配采用 `String.contains` 子串匹配（ignoreCase），命中即认为文字包含成人内容。
 *
 * 注意：这是纯本地匹配，词库与匹配结果都不会离开设备。
 */
object SensitiveWordDictionary {

    private val words: MutableSet<String> = mutableSetOf()

    /** 是否已成功加载词库 */
    var isLoaded: Boolean = false
        private set

    /**
     * 从 assets 加载敏感词库。
     * @param context 上下文
     * @param fileName 词库文件名（默认 [Constants.SENSITIVE_WORDS_FILE]）
     */
    suspend fun load(context: Context, fileName: String = Constants.SENSITIVE_WORDS_FILE) {
        withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(fileName).use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val trimmed = line!!.trim()
                            // 跳过空行与注释行（# 开头）
                            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                                words.add(trimmed)
                            }
                        }
                    }
                }
                isLoaded = true
                InAppLogger.i("敏感词库加载完成，共 ${words.size} 个词")
            }.onFailure {
                InAppLogger.e("敏感词库加载失败", it)
            }
        }
    }

    /**
     * 在给定文本中查找命中的敏感词。
     * @param text 待检测文本（通常为 OCR 识别出的全屏文本）
     * @return 命中的敏感词列表（按首次命中顺序去重）；未加载或空文本返回空列表
     */
    fun containsAny(text: String): List<String> {
        if (!isLoaded || words.isEmpty() || text.isEmpty()) return emptyList()
        val hits = LinkedHashSet<String>()
        for (word in words) {
            if (text.contains(word, ignoreCase = true)) {
                hits.add(word)
            }
        }
        return hits.toList()
    }
}
