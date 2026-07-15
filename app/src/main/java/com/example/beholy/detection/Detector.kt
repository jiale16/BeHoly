package com.example.beholy.detection

import android.graphics.Bitmap
import com.example.beholy.data.DetectionResult

/**
 * 检测器统一接口。所有检测实现（文字 / 图像）须实现该接口，便于 [DetectionLoop] 并行调度。
 */
interface Detector {
    /**
     * 对给定 Bitmap 执行检测，返回结构化结果。
     * @param bitmap 待检测的屏幕截图（不会在此被 recycle，由调用方负责释放）
     */
    suspend fun detect(bitmap: Bitmap): DetectionResult

    /** 释放检测器占用的资源（模型、OCR client 等）。 */
    fun close()
}
