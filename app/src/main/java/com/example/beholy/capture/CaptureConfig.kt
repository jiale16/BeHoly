package com.example.beholy.capture

import com.example.beholy.data.Constants

import android.util.DisplayMetrics

/**
 * 屏幕捕获配置。集中管理截屏相关参数。
 *
 * @property intervalMs 截屏间隔（毫秒），默认 3000ms，允许范围 1000–5000
 * @property pixelFormat ImageReader 像素格式（默认 RGBA_8888）
 * @property width 屏幕真实宽度（由调用方按真实分辨率填入）
 * @property height 屏幕真实高度
 * @property densityDpi 虚拟显示密度 DPI
 */
data class CaptureConfig(
    val intervalMs: Long = 3000L,
    val pixelFormat: Int = Constants.CAPTURE_PIXEL_FORMAT,
    val width: Int = 0,
    val height: Int = 0,
    val densityDpi: Int = DisplayMetrics.DENSITY_MEDIUM
) {
    init {
        require(intervalMs in 1000L..5000L) { "捕获间隔必须在 1000–5000ms 之间" }
        require(width > 0 && height > 0) { "屏幕宽高必须大于 0" }
    }

    companion object {
        fun create(
            width: Int,
            height: Int,
            densityDpi: Int,
            intervalMs: Long = 3000L
        ): CaptureConfig = CaptureConfig(
            intervalMs = intervalMs.coerceIn(1000L, 5000L),
            width = width,
            height = height,
            densityDpi = densityDpi
        )
    }
}
