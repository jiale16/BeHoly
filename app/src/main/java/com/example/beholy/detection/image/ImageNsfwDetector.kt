package com.example.beholy.detection.image

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.beholy.data.Constants
import com.example.beholy.data.DetectionResult
import com.example.beholy.detection.Detector
import com.example.beholy.util.InAppLogger
import org.tensorflow.lite.Interpreter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 图像（NSFW 画面 / 视频帧）检测器。
 *
 * 使用 TensorFlow Lite 加载 open_nsfw 量化模型对屏幕截图做成人内容分类。
 *
 * ★★ 中文注释：缩放、BGR 均值减法归一化、张量形状 ★★
 *
 * 1) 缩放：模型输入固定为 1×224×224×3。将任意尺寸的截屏用 Bitmap 缩放到 224×224。
 *    缩放使用 createScaledBitmap，过滤质量使用双线性（默认 true）。
 *
 * 2) BGR 均值减法归一化：open_nsfw 原始预处理为：
 *    - 将图片由 RGB 转为 BGR（通道顺序反转）；
 *    - 每个像素的每个通道减去均值 [104, 117, 123]（分别对应 B,G,R 通道均值）；
 *    - 结果直接作为 FLOAT32 张量输入（不做 /255，因为均值减法后已近似归一）。
 *
 *    ⚠ 重要：不同的 .tflite 导出方式可能采用不同预处理
 *    （例如使用 ImageNet 均值 [123.675,116.28,103.53]、或 0~1 归一、或 UINT8 量化输入）。
 *    因此这里将均值封装为 [Constants.IMAGE_MEAN_BGR] 常量，并集中在下方 [fillInputBuffer]
 *    中处理；若你的模型不同，只需调整均值/缩放因子即可，无需改动其它逻辑。
 *
 * 3) 张量形状：输入张量 shape = [1, 224, 224, 3]，dtype FLOAT32，连续排布（NHWC）。
 *    输出张量 shape = [1, 1]（单个 NSFW 概率值），范围 [0,1]。
 *
 *    ⚠ 若你使用的是「量化版(open_nsfw_quant)」且模型 I/O 为 UINT8：
 *    则输入应按模型量化参数（scale/zeroPoint）填充 0~255 字节，输出需按 outputTensor
 *    的量化参数反量化。可在本类改为使用 org.tensorflow.lite.support.tensor.TensorBuffer
 *    （UINT8）并读取 quantizationParams 反量化，详见文末注释示例。
 *
 * 4) 调度：缩放与推理为 CPU/NPU 密集操作，统一在 Dispatchers.Default 执行。
 */
class ImageNsfwDetector(context: Context) : Detector {

    private val interpreter: Interpreter = TfLiteModelLoader.load(context)
    private val closed = AtomicBoolean(false)

    override suspend fun detect(bitmap: Bitmap): DetectionResult = withContext(Dispatchers.Default) {
        if (closed.get()) return@withContext DetectionResult()

        return@withContext try {
            // 1) 缩放至 224×224（同尺寸则直接复用，避免无谓拷贝）
            val resized = if (bitmap.width == Constants.MODEL_INPUT_SIZE &&
                bitmap.height == Constants.MODEL_INPUT_SIZE
            ) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(
                    bitmap,
                    Constants.MODEL_INPUT_SIZE,
                    Constants.MODEL_INPUT_SIZE,
                    true
                )
            }

            // 2) 构造输入张量（BGR 转换 + 均值减法）
            val input = fillInputBuffer(resized)

            // 3) 准备输出缓冲区 [1, 2] FLOAT32
            //    open_nsfw 模型输出 2 个值：[sfw_prob, nsfw_prob]
            val output = Array(1) { FloatArray(2) }

            // 4) 推理
            interpreter.run(input, output)

            // 模型输出 [1,2]：output[0][0]=SFW概率，output[0][1]=NSFW概率
            val rawSfw = output[0][0]
            val rawNsfw = output[0][1]
            // 用 softmax 归一化（防止模型输出未归一化的 logits）
            val maxVal = maxOf(rawSfw, rawNsfw)
            val expSfw = kotlin.math.exp(rawSfw - maxVal)
            val expNsfw = kotlin.math.exp(rawNsfw - maxVal)
            val sumExp = expSfw + expNsfw
            val score = (expNsfw / sumExp).coerceIn(0f, 1f)

            val isNsfw = score >= Constants.IMAGE_NSFW_THRESHOLD

            // 仅当新建了缩放图时才回收，原始 bitmap 由调用方负责
            if (resized != bitmap) resized.recycle()

            DetectionResult(isImageNsfw = isNsfw, imageScore = score)
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "图像检测异常", e)
            DetectionResult()
        }
    }

    /**
     * 将 Bitmap 像素填充为 NHWC 的 FLOAT32 输入张量，并执行 BGR 转换 + 均值减法。
     * 布局：[batch=1][height=224][width=224][channel=3]，每通道 4 字节（FLOAT32）。
     */
    private fun fillInputBuffer(bitmap: Bitmap): ByteBuffer {
        val size = Constants.MODEL_INPUT_SIZE
        val buffer = ByteBuffer.allocateDirect(size * size * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)

        for (pixel in pixels) {
            // ARGB_8888 拆解
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // open_nsfw 使用 BGR 顺序，并减均值 [104,117,123]
            // 若你的模型使用 0~1 归一，可将下面三行改为 (b - 104) / 255f 等形式
            buffer.putFloat((b - Constants.IMAGE_MEAN_BGR[0])) // B - 104
            buffer.putFloat((g - Constants.IMAGE_MEAN_BGR[1])) // G - 117
            buffer.putFloat((r - Constants.IMAGE_MEAN_BGR[2])) // R - 123
        }
        buffer.rewind()
        return buffer
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { interpreter.close() }
            Log.i(Constants.LOG_TAG, "ImageNsfwDetector 已释放")
        }
    }

    /*
     * —— 量化模型(UINT8 I/O)替代实现示例（如需要时启用）——
     *
     * 若 open_nsfw_quant.tflite 的输入输出为 UINT8，可改用 TensorBuffer：
     *
     *   val input = TensorBuffer.createFixedSize(intArrayOf(1,224,224,3), DataType.UINT8)
     *   // 将 Bitmap 像素按 BGR 顺序填入，并映射到 0~255
     *   input.loadBuffer(byteArray)              // byteArray 由像素构造
     *   val output = TensorBuffer.createFixedSize(intArrayOf(1,1), DataType.UINT8)
     *   interpreter.run(input.buffer, output.buffer)
     *   // 反量化：score = output.floatValue[0] （TensorBuffer 已按量化参数处理）
     *
     * 具体以你的模型实际量化参数为准。
     */
}
