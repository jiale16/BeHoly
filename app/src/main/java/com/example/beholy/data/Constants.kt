package com.example.beholy.data

import android.graphics.PixelFormat

/**
 * 全局常量定义。集中管理阈值、尺寸、模型参数，方便统一调整与排查。
 */
object Constants {
    /** 图像 NSFW 判定阈值：模型输出概率 >= 该值即判定为成人内容 */
    const val IMAGE_NSFW_THRESHOLD: Float = 0.7f

    /** 前台通知渠道 ID */
    const val NOTIFICATION_CHANNEL_ID = "nsfw_guard_channel"

    /** 常驻前台通知 ID */
    const val NOTIFICATION_ID = 1001

    /** 命中提示通知 ID（与常驻通知区分） */
    const val HIT_NOTIFICATION_ID = 1002

    /** TFLite 模型文件名（位于 assets 目录） */
    const val NSFW_MODEL_FILE = "open_nsfw_quant.tflite"

    /** 敏感词库文件名（位于 assets 目录） */
    const val SENSITIVE_WORDS_FILE = "sensitive_words.txt"

    /** 图像模型输入尺寸（open_nsfw 标准输入为 224x224） */
    const val MODEL_INPUT_SIZE = 224

    /**
     * 图像预处理均值（BGR 顺序）。
     *
     * open_nsfw 的典型预处理为：将图片转为 BGR 顺序，并对每个通道减去均值。
     * - 索引 0 -> B 通道均值
     * - 索引 1 -> G 通道均值
     * - 索引 2 -> R 通道均值
     *
     * 注意：不同 .tflite 导出方式可能采用不同的预处理（例如 ImageNet 均值、或 0~1 归一化）。
     * 这里封装为常量，便于按你的实际模型一行调整，无需改动逻辑代码。
     */
    val IMAGE_MEAN_BGR = floatArrayOf(104f, 117f, 123f)

    /** ImageReader 像素格式（RGBA_8888） */
    const val CAPTURE_PIXEL_FORMAT: Int = PixelFormat.RGBA_8888

    /** Logcat 统一 TAG */
    const val LOG_TAG = "BeHoly"
}
