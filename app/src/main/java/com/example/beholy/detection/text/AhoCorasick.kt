package com.example.beholy.detection.text

import java.util.ArrayDeque

/**
 * Aho-Corasick 多模式串匹配自动机。
 *
 * 替代 [com.example.beholy.data.SensitiveWordDictionary.containsAny] 的 O(词数×文本长) 暴力扫描:
 * - 构建 O(总词长)
 * - 扫描 O(文本长 + 命中数)
 *
 * 对长文本(网页/聊天聚合后)与大规模词库(本项目 ~871 词)显著降耗。
 *
 * 大小写不敏感:构建与扫描统一转小写。
 * 构建后只读,可并发扫描(无共享可变状态)。
 * 零新增依赖,纯 JDK 实现(遵循离线铁律)。
 *
 * 命中结果顺序:按词库首次出现顺序去重返回(与原暴力扫描语义一致,见 TextDetectorTest)。
 */
class AhoCorasick private constructor(
    private val transitions: Array<HashMap<Char, Int>?>,
    private val fail: IntArray,
    private val outputs: Array<LinkedHashSet<String>?>,
    private val wordOrder: List<String>
) {
    companion object {
        /**
         * 从词集合构建自动机。
         * @param words 词集合(顺序决定命中结果排序;空词自动跳过;自动去重)
         */
        fun build(words: Iterable<String>): AhoCorasick {
            // 词库顺序快照(去重、过滤空串、统一小写,保留首次出现顺序)
            val orderedWords = LinkedHashSet<String>().apply {
                words.forEach { w ->
                    val lw = w.lowercase()
                    if (lw.isNotEmpty()) add(lw)
                }
            }.toList()

            val transitions = ArrayList<HashMap<Char, Int>?>()
            val outputs = ArrayList<LinkedHashSet<String>?>()
            // state 0 = root
            transitions.add(HashMap())
            outputs.add(null)

            // 1. 构建 trie (goto)
            for (word in orderedWords) {
                var state = 0
                for (ch in word) {
                    val cur = transitions[state]!!
                    val existing = cur[ch]
                    if (existing == null) {
                        val newState = transitions.size
                        transitions.add(HashMap())
                        outputs.add(null)
                        cur[ch] = newState
                        state = newState
                    } else {
                        state = existing
                    }
                }
                if (outputs[state] == null) outputs[state] = LinkedHashSet()
                outputs[state]!!.add(word)
            }

            // 2. BFS 构建 fail 函数 + 合并 fail 链输出
            val fail = IntArray(transitions.size)
            val queue = ArrayDeque<Int>()
            // 根的直接子节点:fail -> 0
            for ((_, next) in transitions[0]!!) {
                fail[next] = 0
                queue.add(next)
            }
            while (queue.isNotEmpty()) {
                // queue.poll() 返回 Int?(Java 平台类型);isNotEmpty() 已保证非空,用 !! 收窄
                val state = queue.poll()!!
                val trans = transitions[state] ?: continue
                for ((ch, next) in trans) {
                    queue.add(next)
                    // 沿 fail 链找有 ch 转移的最深状态
                    var f = fail[state]
                    while (f != 0 && transitions[f]?.get(ch) == null) {
                        f = fail[f]
                    }
                    val failTarget = transitions[f]?.get(ch)
                    // failTarget 不可能等于 next(next 深度 ≥ 2,fail 链终点深度 ≤ 1)
                    fail[next] = if (failTarget != null && failTarget != next) failTarget else 0

                    // 合并 fail 链输出:用新 set 合并,保留 cur 自身顺序在前
                    val cur = outputs[next]
                    val inherited = outputs[fail[next]]
                    if (inherited != null && inherited.isNotEmpty()) {
                        outputs[next] = if (cur == null) {
                            LinkedHashSet(inherited)
                        } else {
                            LinkedHashSet(cur).apply { addAll(inherited) }
                        }
                    }
                }
            }

            return AhoCorasick(
                transitions.toTypedArray(),
                fail,
                outputs.toTypedArray(),
                orderedWords
            )
        }
    }

    /**
     * 扫描文本,返回所有命中的词(去重,按词库首次出现顺序)。
     * 大小写不敏感(构建时已转小写)。
     */
    fun findAll(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lower = text.lowercase()
        val hits = LinkedHashSet<String>()
        var state = 0
        for (ch in lower) {
            // 沿 fail 链找有 ch 转移的状态,或回到根
            while (state != 0 && transitions[state]?.get(ch) == null) {
                state = fail[state]
            }
            val next = transitions[state]?.get(ch)
            if (next != null) state = next
            // 取当前状态输出(已合并 fail 链输出)
            outputs[state]?.let { hits.addAll(it) }
        }
        // 按词库顺序重排(与原暴力扫描语义一致)
        return wordOrder.filter { it in hits }
    }
}
