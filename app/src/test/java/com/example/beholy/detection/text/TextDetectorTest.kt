package com.example.beholy.detection.text

import com.example.beholy.data.SensitiveWordDictionary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [TextDetector.detect] 单元测试（纯 JVM）。
 *
 * 设计说明（见增量设计 §2.1 / §3）：[TextDetector.detect] 接收聚合后的节点文本列表，
 * 复用本地 [SensitiveWordDictionary.containsAny] 做子串（ignoreCase）匹配，返回去重命中词。
 *
 * 测试可测性说明：源码中 [SensitiveWordDictionary] 为 object 单例且未抽接口，
 * [TextDetector.detect] 直接以单例作为参数类型，因此本测试通过反射向单例注入「已知词库」，
 * 避免依赖 assets / Context（更优做法是抽 `WordDictionary` 接口注入，已在报告中记录，
 * 作为可测性改进建议，但不阻塞当前测试）。
 */
class TextDetectorTest {

    // 已知词库（通过反射注入 SensitiveWordDictionary 单例，保证断言确定性）
    private val dictionary = listOf("色情", "赌博", "暴力", "porn", "sex")

    @Before
    fun setUp() = injectDictionary(dictionary)

    @After
    fun tearDown() = clearDictionary()

    @Test
    fun detect_emptyInput_returnsEmpty() {
        val result = TextDetector.detect(emptyList(), SensitiveWordDictionary)
        assertTrue(result.isEmpty())
    }

    @Test
    fun detect_noHit_returnsEmpty() {
        val result = TextDetector.detect(
            listOf("今天天气真好", "我们去看电影吧"),
            SensitiveWordDictionary
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun detect_singleHit_returnsOneWord() {
        val result = TextDetector.detect(listOf("这是一段色情内容"), SensitiveWordDictionary)
        assertEquals(listOf("色情"), result)
    }

    @Test
    fun detect_multiHit_returnsAllMatchedInDictionaryOrder() {
        val result = TextDetector.detect(
            listOf("包含色情与赌博的描述", "还有暴力场景"),
            SensitiveWordDictionary
        )
        // containsAny 按词库首次命中顺序去重返回
        assertEquals(listOf("色情", "赌博", "暴力"), result)
    }

    @Test
    fun detect_caseInsensitive() {
        val result = TextDetector.detect(listOf("This is PORN content"), SensitiveWordDictionary)
        assertEquals(listOf("porn"), result)
    }

    @Test
    fun detect_substringBoundary_matchesContainedWord() {
        // 「sex」作为子串应命中「sexual」「sexy」等（设计为子串匹配，符合预期）
        val r1 = TextDetector.detect(listOf("sexual content"), SensitiveWordDictionary)
        assertEquals(listOf("sex"), r1)
        val r2 = TextDetector.detect(listOf("sexy video"), SensitiveWordDictionary)
        assertEquals(listOf("sex"), r2)
    }

    @Test
    fun detect_acrossMultipleNodes_aggregates() {
        // 文本来自多个 AccessibilityNodeInfo 节点，合并后仍能命中
        val nodes = listOf("标题：免费", "正文：观看色情视频", "按钮：立即进入")
        val result = TextDetector.detect(nodes, SensitiveWordDictionary)
        assertEquals(listOf("色情"), result)
    }

    // ===== 反射注入已知词库（源码未抽接口，单测注入；已在报告中记录可测性建议）=====
    private fun injectDictionary(words: List<String>) {
        val clazz = SensitiveWordDictionary::class.java
        val wordsField = clazz.getDeclaredField("words").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val set = wordsField.get(SensitiveWordDictionary) as MutableSet<String>
        set.clear()
        set.addAll(words)
        clazz.getDeclaredField("isLoaded").apply { isAccessible = true }
            .set(SensitiveWordDictionary, true)
    }

    private fun clearDictionary() {
        val clazz = SensitiveWordDictionary::class.java
        clazz.getDeclaredField("words").apply { isAccessible = true }
            .let { (it.get(SensitiveWordDictionary) as MutableSet<String>).clear() }
        clazz.getDeclaredField("isLoaded").apply { isAccessible = true }
            .set(SensitiveWordDictionary, false)
    }
}
