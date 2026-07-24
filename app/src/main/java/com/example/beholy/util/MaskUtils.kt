package com.example.beholy.util

/**
 * 掩码工具：在悔改日记与日志中，命中的敏感词只显示首字，其余以 * 代替，
 * 避免明文暴露被检测到的内容。
 */
object MaskUtils {

    /** 对单个词做掩码：保留首字，其余字符替换为 *。空串或单字原样返回。 */
    fun maskWord(word: String): String {
        if (word.length <= 1) return word
        return word.first() + "*".repeat(word.length - 1)
    }

    /**
     * 对"理由"文案做掩码：仅对形如「敏感文字：a、b」中冒号后的词列表逐词掩码，
     * 前缀标签本身保持不动；无冒号（如「成人画面」）则原样返回。
     * 幂等：对已掩码文本再次调用结果不变，可安全用于历史明文记录。
     */
    fun maskReason(reason: String): String {
        val idx = reason.indexOf("：")
        if (idx < 0) return reason
        val label = reason.substring(0, idx + 1) // 含中文冒号
        val rest = reason.substring(idx + 1)
        val masked = rest.split("、").joinToString("、") { maskWord(it) }
        return label + masked
    }
}
