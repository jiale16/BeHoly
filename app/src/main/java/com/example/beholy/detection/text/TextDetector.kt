package com.example.beholy.detection.text

import com.example.beholy.data.SensitiveWordDictionary

/**
 * 文本命中检测器（替换旧版文字检测器的 OCR 角色）。
 *
 * 旧方案对截屏 Bitmap 做 OCR 得到文本；本增量改为由 AccessibilityService
 * 遍历视图树聚合节点文本，因此 [detect] 直接接收已聚合的文本列表，复用同一份
 * [SensitiveWordDictionary] 做本地匹配，纯函数、零新增依赖。
 */
object TextDetector {

    /**
     * 在给定文本集合中查找命中的敏感词。
     *
     * @param texts 从 AccessibilityNodeInfo 聚合出的文本列表（text + contentDescription）
     * @param dict 本地敏感词库（[SensitiveWordDictionary]）
     * @return 命中的敏感词列表（已去重，按首次命中顺序）；空输入或未加载词库返回空列表
     */
    fun detect(texts: List<String>, dict: SensitiveWordDictionary): List<String> {
        if (texts.isEmpty()) return emptyList()
        val merged = texts.joinToString(separator = "\n")
        return dict.containsAny(merged)
    }
}
