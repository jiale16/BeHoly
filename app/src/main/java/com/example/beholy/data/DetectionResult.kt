package com.example.beholy.data

/**
 * 单次检测的结果封装。包含「图像（NSFW 画面）」与「文字（敏感词）」两类结论。
 *
 * 设计说明：每个 Detector（文字/图像）只填充自己负责的部分，
 * 由 [com.example.beholy.service.DetectionLoop] 负责合并为统一的 [DetectionResult]。
 */
data class DetectionResult(
    /** 图像（NSFW 画面）是否命中 */
    val isImageNsfw: Boolean = false,
    /** 图像 NSFW 概率（范围 [0,1]） */
    val imageScore: Float = 0f,
    /** 文字是否命中敏感词 */
    val isTextHit: Boolean = false,
    /** 命中的敏感词列表（去重） */
    val hitWords: List<String> = emptyList(),
    /** 识别出的屏幕文本（便于调试/日志，不用于上传） */
    val recognizedText: String = "",
    /** 检测时间戳（毫秒） */
    val timestamp: Long = System.currentTimeMillis()
) {
    /** 是否为「任一类型」的命中 */
    val isHit: Boolean
        get() = isImageNsfw || isTextHit
}
