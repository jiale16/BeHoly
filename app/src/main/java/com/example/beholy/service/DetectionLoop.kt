package com.example.beholy.service

import android.content.Context
import android.media.projection.MediaProjection
import com.example.beholy.capture.CaptureConfig
import com.example.beholy.capture.ScreenCapturer
import com.example.beholy.data.DetectionResult
import com.example.beholy.data.SensitiveWordDictionary
import com.example.beholy.detection.image.ImageNsfwDetector
import com.example.beholy.detection.text.TextNsfwDetector
import com.example.beholy.util.BitmapUtils
import com.example.beholy.util.CpuAffinity
import com.example.beholy.util.InAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 检测循环：截屏 → 并行双路检测 → 命中回调。
 *
 * 节能设计：
 * - 截屏间隔 3 秒（可配置），降低 CPU/GPU 占用；
 * - 帧未就绪时不忙等，delay 后重试；
 * - Bitmap 复用池减少 GC。
 */
class DetectionLoop(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    private lateinit var capturer: ScreenCapturer
    private lateinit var textDetector: TextNsfwDetector
    private lateinit var imageDetector: ImageNsfwDetector

    fun start(intervalMs: Long, onHit: (DetectionResult) -> Unit) {
        if (job?.isActive == true) return

        job = scope.launch {
            val metrics = context.resources.displayMetrics
            val config = CaptureConfig.create(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
                intervalMs = intervalMs
            )

            capturer = ScreenCapturer(mediaProjection, config)
            textDetector = TextNsfwDetector(context)
            imageDetector = ImageNsfwDetector(context)

            if (!SensitiveWordDictionary.isLoaded) {
                SensitiveWordDictionary.load(context)
            }

            capturer.init()
            InAppLogger.i("监控启动，间隔 ${config.intervalMs}ms")

            try {
                // 将检测循环线程绑定到小核心，降低功耗
                CpuAffinity.bindToLittleCores()

                while (isActive) {
                    val bitmap = capturer.captureFrame()
                    if (bitmap == null) {
                        delay(500)
                        continue
                    }

                    // 检测任务在 Default 线程池执行，设置后台优先级倾向小核
                    val textDeferred = async(Dispatchers.Default) {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                        textDetector.detect(bitmap)
                    }
                    val imageDeferred = async(Dispatchers.Default) {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                        imageDetector.detect(bitmap)
                    }

                    val txt = textDeferred.await()
                    val img = imageDeferred.await()

                    val merged = DetectionResult(
                        isImageNsfw = img.isImageNsfw,
                        imageScore = img.imageScore,
                        isTextHit = txt.isTextHit,
                        hitWords = txt.hitWords,
                        recognizedText = txt.recognizedText
                    )

                    if (merged.isHit) {
                        onHit(merged)
                    }

                    BitmapUtils.releaseReusable(bitmap)
                    delay(config.intervalMs)
                }
            } finally {
                runCatching { capturer.release() }
                runCatching { textDetector.close() }
                runCatching { imageDetector.close() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
