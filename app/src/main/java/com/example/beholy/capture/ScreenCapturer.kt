package com.example.beholy.capture

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Log
import com.example.beholy.data.Constants
import com.example.beholy.util.BitmapUtils
import com.example.beholy.util.InAppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 屏幕捕获器：基于 MediaProjection + ImageReader 在后台持续截屏。
 *
 * 工作流程：
 * 1. 通过 [MediaProjection] 创建 [VirtualDisplay]，将屏幕内容渲染到 [ImageReader] 的 Surface；
 * 2. 定期调用 [captureFrame] 从 ImageReader 取出最新一帧 [Image]；
 * 3. 将 Image（多 plane 像素）转换为 Bitmap，交给检测器使用。
 *
 * 注意：本类所有 I/O 与像素拷贝操作均应在 Dispatchers.IO 调度执行（见 [captureFrame]），
 * 避免阻塞主线程与协程的 Default 推理线程。
 */
class ScreenCapturer(
    private val mediaProjection: MediaProjection,
    private val config: CaptureConfig
) {
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    /**
     * 初始化 ImageReader 与 VirtualDisplay。必须在捕获前调用一次。
     */
    fun init() {
        imageReader = ImageReader.newInstance(
            config.width,
            config.height,
            config.pixelFormat, // RGBA_8888
            2 // maxImages：缓冲 2 帧，降低 acquireLatestImage 时的丢帧竞争
        )
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "BeHolyVirtualDisplay",
            config.width,
            config.height,
            config.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            null
        )
        Log.i(Constants.LOG_TAG, "ScreenCapturer 初始化完成 ${config.width}x${config.height}")
    }

    /**
     * 捕获当前屏幕的一帧，返回 RGBA_8888 的 Bitmap。
     *
     * ★★ 详细中文注释：Image → Bitmap 转换、stride 对齐陷阱、为何在 IO 调度执行 ★★
     *
     * 1) ImageReader 输出的是封装在 Image 中的像素数据。RGBA_8888 格式下通常只有一个 plane
     *    （planes[0]），其底层 buffer 采用「行优先（row-major）」存储，但【不保证】每行像素紧密排列：
     *    rowStride（一行的字节跨度）通常 >= width * pixelStride。
     *
     * 2) stride 对齐陷阱：
     *    - rowStride：一行的字节跨度 = 实际每行占用的字节数；
     *    - pixelStride：相邻像素之间的字节跨度（RGBA_8888 下通常为 4）。
     *    由于硬件/内存对齐要求，rowStride 常比 width*4 大（例如 width=1080 时 rowStride 可能是
     *    1088*4）。如果简单地按 width*4 连续拷贝，会出现「错位 / 斜条纹 / 颜色错乱」等经典 bug。
     *
     * 3) 正确做法：逐行拷贝。对每一行 y，从 buffer 的 (y*rowStride) 偏移开始，读取 width*pixelStride
     *    个字节，写入目标 Bitmap 的第 y 行。这里我们逐像素读取 4 字节并按照
     *    RGBA_8888 的字节顺序（R,G,B,A）拼成 ARGB_8888 的 int（Bitmap 内部为 ARGB 排序）。
     *    —— 注意：RGBA_8888 的 Image plane 字节顺序约定为 R,G,B,A；若个别设备/格式有差异，
     *       只需在此处调整通道取值顺序即可。
     *
     * 4) 为何在 IO 调度执行：像素拷贝是 CPU + 内存带宽密集操作，1080p 一帧约 8MB 数据。
     *    放在 Dispatchers.IO 可避免阻塞主线程，也不会占用 Default 推理线程。
     *
     * 5) acquireLatestImage() 会丢弃旧帧、只保留最新一帧，适合「尽量取最新屏幕」的场景；
     *    取到后必须调用 image.close() 释放底层 GraphicBuffer，否则缓冲耗尽后后续 acquire 返回 null。
     */
    suspend fun captureFrame(): Bitmap? = withContext(Dispatchers.IO) {
        val reader = imageReader ?: return@withContext null
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                // 帧未就绪是常态，只在首次打印避免刷屏
                return@withContext null
            }

            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = image.width
            val height = image.height

            // 从复用池获取（或新建）同尺寸 Bitmap，减少 GC 抖动
            val bitmap = BitmapUtils.acquireReusable(width, height)

            // 逐行拷贝，妥善处理 rowStride 与 pixelStride 不对齐
            val rowPixels = IntArray(width)
            for (y in 0 until height) {
                val rowBase = y * rowStride
                for (x in 0 until width) {
                    val offset = rowBase + x * pixelStride
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    val a = buffer.get(offset + 3).toInt() and 0xFF
                    rowPixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1)
            }

            bitmap
        } catch (e: Exception) {
            InAppLogger.e("captureFrame 失败", e)
            null
        } finally {
            image?.close()
        }
    }

    /** 释放所有资源：VirtualDisplay、ImageReader、MediaProjection。 */
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        runCatching { mediaProjection.stop() }
        Log.i(Constants.LOG_TAG, "ScreenCapturer 资源已释放")
    }
}
