package com.example.beholy.data

import android.content.Context
import android.util.Log
import com.example.beholy.detection.text.AhoCorasick
import com.example.beholy.util.InAppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.LinkedHashSet

/**
 * 本地敏感词库(离线)。
 *
 * 实现说明:
 * - 词库文件位于 assets/sensitive_words.txt,每行一个词,`#` 开头为注释行;
 * - 使用 [BufferedReader] 逐行读取,避免一次性载入超大文件造成内存峰值;
 * - 初始化建议在 IO 协程中执行(本类 [load] 内部已切到 Dispatchers.IO);
 * - 匹配采用 [AhoCorasick] 自动机,构建 O(总词长),扫描 O(文本长 + 命中数),
 *   替代旧版 O(词数×文本长) 暴力扫描,对长文本与大规模词库(本项目 ~871 词)显著降耗;
 * - 自动支持子串匹配与大小写不敏感(构建与扫描统一转小写)。
 *
 * 注意:这是纯本地匹配,词库与匹配结果都不会离开设备。
 *
 * 线程安全:
 * - [isLoaded] / [automaton] / [automatonWordsHash] 均为 @Volatile,保证跨线程可见性;
 * - [words] 的读写与自动机构建在 synchronized 块内,确保并发安全;
 * - 自动机构建后只读,可并发扫描 [containsAny]。
 */
object SensitiveWordDictionary {

    private val words: MutableSet<String> = LinkedHashSet()

    /** 是否已成功加载词库。@Volatile 保证由 IO 协程写、无障碍主线程读的跨线程可见性。 */
    @Volatile
    var isLoaded: Boolean = false
        private set

    /** 自动机快照(构建后只读)。@Volatile 保证跨线程可见。 */
    @Volatile
    private var automaton: AhoCorasick? = null

    /** 自动机对应的词库 hash,用于检测词库变更(如反射注入)并触发重建。 */
    @Volatile
    private var automatonWordsHash: Int = 0

    /**
     * 从 assets 加载敏感词库。
     *
     * 重复调用安全:[words] 会先 clear,避免多次加载导致词库累积重复。
     *
     * @param context 上下文
     * @param fileName 词库文件名(默认 [Constants.SENSITIVE_WORDS_FILE])
     */
    suspend fun load(context: Context, fileName: String = Constants.SENSITIVE_WORDS_FILE) {
        withContext(Dispatchers.IO) {
            runCatching {
                synchronized(words) {
                    // 防重复加载累积:每次 load 都先清空,保证幂等
                    words.clear()
                    automaton = null
                    automatonWordsHash = 0

                    context.assets.open(fileName).use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val trimmed = line!!.trim()
                                // 跳过空行与注释行(# 开头)
                                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                                    words.add(trimmed)
                                }
                            }
                        }
                    }
                    isLoaded = true
                    // 构建自动机快照
                    automaton = AhoCorasick.build(words)
                    automatonWordsHash = words.hashCode()
                    InAppLogger.i("敏感词库加载完成,共 ${words.size} 个词,自动机已构建")
                }
            }.onFailure {
                InAppLogger.e("敏感词库加载失败", it)
            }
        }
    }

    /**
     * 在给定文本中查找命中的敏感词。
     *
     * 使用 [AhoCorasick] 自动机一次扫描返回所有命中,复杂度 O(文本长 + 命中数)。
     * 词库变更(如反射注入新词)会通过 hash 检测触发自动机重建,保证语义一致。
     *
     * 安全短语豁免：若某敏感词的全部出现位置都落在 [Constants.SAFE_PHRASES] 区间内
     * （如「举报页面」的「色情低俗」分类标签），视为对成人内容的归类而非成人内容本身，
     * 不计入命中，避免子串匹配误报；但该词若出现在安全短语之外（如「色情网站」），仍正常命中。
     *
     * @param text 待检测文本(通常为无障碍节点聚合后的全屏文本)
     * @return 命中的敏感词列表(按词库首次出现顺序去重);未加载或空文本返回空列表
     */
    fun containsAny(text: String): List<String> {
        if (!isLoaded || words.isEmpty() || text.isEmpty()) return emptyList()

        val safeSpans = safePhraseSpans(text)

        // 自动机缓存命中检测:词库 hash 未变则直接复用
        val current = automaton
        val currentHash = automatonWordsHash
        val wordsHash = words.hashCode()
        if (current != null && currentHash == wordsHash) {
            return filterSafeHits(current.findAll(text), text, safeSpans)
        }

        // 自动机缺失或词库变更(如反射注入):重建
        return synchronized(words) {
            // 双检:可能其他线程已重建
            val again = automaton
            if (again != null && automatonWordsHash == words.hashCode()) {
                return@synchronized filterSafeHits(again.findAll(text), text, safeSpans)
            }
            val ac = AhoCorasick.build(words)
            automaton = ac
            automatonWordsHash = words.hashCode()
            filterSafeHits(ac.findAll(text), text, safeSpans)
        }
    }

    /**
     * 对自动机命中的词进行安全短语豁免过滤。
     * 若命中词的全部出现位置都落在安全短语区间内，则移除。
     */
    private fun filterSafeHits(hits: List<String>, text: String, safeSpans: List<IntRange>): List<String> {
        if (safeSpans.isEmpty()) return hits
        return hits.filterNot { word ->
            allOccurrencesWithinSafeSpans(text, word, safeSpans)
        }
    }

    /**
     * 计算所有安全短语在文本中的区间（[start, end)），供豁免判定使用。
     */
    private fun safePhraseSpans(text: String): List<IntRange> {
        val phrases = Constants.SAFE_PHRASES
        if (phrases.isEmpty()) return emptyList()
        val spans = mutableListOf<IntRange>()
        for (phrase in phrases) {
            if (phrase.isEmpty()) continue
            var from = 0
            while (true) {
                val idx = text.indexOf(phrase, from, ignoreCase = true)
                if (idx < 0) break
                spans.add(idx until idx + phrase.length)
                from = idx + phrase.length
            }
        }
        return spans
    }

    /**
     * 判断某敏感词在文本中的【所有】出现是否都落在安全短语区间内。
     * 只要有一处出现在安全短语之外，即为真实命中，返回 false。
     */
    private fun allOccurrencesWithinSafeSpans(
        text: String,
        word: String,
        spans: List<IntRange>
    ): Boolean {
        if (spans.isEmpty()) return false
        var from = 0
        while (true) {
            val idx = text.indexOf(word, from, ignoreCase = true)
            if (idx < 0) break
            val range = idx until idx + word.length
            val inside = spans.any { span -> range.first >= span.first && range.last <= span.last }
            if (!inside) return false
            from = idx + word.length
        }
        return true
    }
}
