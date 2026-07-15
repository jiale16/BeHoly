package com.example.beholy.detection.image

import android.content.Context
import android.util.Log
import com.example.beholy.data.Constants
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TFLite 模型加载器（NSFW 分类）。
 *
 * ★★ 详细中文注释 ★★
 *
 * 一、模型加载
 * 从 assets/open_nsfw_quant.tflite 以 MappedByteBuffer 方式映射到内存，
 * 避免一次性读入 Java 堆，减少内存占用与 GC 压力。
 *
 * 二、NNAPI Delegate 如何启用
 * 通过 Interpreter.Options.addDelegate(NnApiDelegate()) 将 NNAPI（Android Neural Networks API）
 * 作为加速后端加入。NNAPI 是 Android 8.1+ 提供的统一神经网络推理接口，它会把算子分派给
 * 设备底层可用的加速器（CPU / GPU / DSP / NPU）。
 *
 * 三、为何能在骁龙 8 Gen 2 上走 NPU
 * 骁龙 8 Gen 2 内置 Hexagon NPU（高通 AI Engine），并提供了 NNAPI 的厂商驱动实现。
 * 当 TFLite 使用 NNAPI Delegate 时，NNAPI 运行时会把可加速的算子（卷积、全连接等）调度到
 * Hexagon NPU 上执行，从而获得远低于纯 CPU 的延迟与更低的发热/功耗；不可加速的算子回退到 CPU。
 * 因此我们优先使用 NNAPI，无需手写 NPU 调用即可「自动」利用 NPU 加速。
 *
 * 四、Hexagon 分支如何切换
 * 若 NNAPI 在某些算子无法走 NPU（或需要更高性能），可改用高通专属的 Hexagon Delegate：
 * 传入 useHexagon=true 时改用 org.tensorflow.lite.hexagon.HexagonDelegate。该路径需要：
 *   1) 额外引入 tensorflow-lite-hexagon AAR（已在依赖中声明）；
 *   2) 在部分设备上向 jniLibs 放入 libhexagon_nn_skel.so 等 DSP 库；
 *   3) 受厂商 DSP 库与系统版本约束。
 * 因依赖与设备环境复杂，【默认关闭】该分支，仅作为可选切换项保留。
 *
 * 五、Interpreter 选项
 * - setNumThreads：CPU 回退时的线程数（NNAPI 下基本由驱动决定，仍建议设为 4 以免回退时单线程）；
 * - setAllowBufferHandleOutput：允许使用缓冲区句柄，减少数据拷贝；
 * - setAllowFp16PrecisionForFp32：允许 FP16 精度以提升速度与降低发热；
 * - addDelegate：加入加速 Delegate（NNAPI 或 Hexagon）。
 *
 * 六、资源释放
 * 返回的 Interpreter 需在使用完毕后调用 interpreter.close()（见 ImageNsfwDetector.close）。
 * NnApiDelegate 生命周期由 Interpreter 管理，关闭 Interpreter 时一并释放。
 */
object TfLiteModelLoader {

    /**
     * 加载 NSFW 模型并返回 [Interpreter]。
     * @param context 上下文（用于读取 assets）
     */
    fun load(context: Context): Interpreter {
        val modelBuffer = loadModelFile(context, Constants.NSFW_MODEL_FILE)

        // 构造 NNAPI 加速 Delegate（try 包裹以应对个别设备不支持的情况）
        val delegate: NnApiDelegate? = try {
            NnApiDelegate()
        } catch (e: Exception) {
            Log.w(Constants.LOG_TAG, "Delegate 构造失败，将使用纯 CPU", e)
            null
        }

        val options = Interpreter.Options().apply {
            setNumThreads(4)
            setAllowBufferHandleOutput(true)
            setAllowFp16PrecisionForFp32(true)
            if (delegate != null) addDelegate(delegate)
        }

        return try {
            Interpreter(modelBuffer, options).also {
                Log.i(
                    Constants.LOG_TAG,
                    "NSFW 模型已加载（Delegate: ${if (delegate != null) "ON" else "OFF"}）"
                )
            }
        } catch (e: Exception) {
            // 极少数情况下带 Delegate 仍失败，回退到纯 CPU 解释器，保证可用性
            Log.w(Constants.LOG_TAG, "带 Delegate 加载失败，回退纯 CPU 解释器", e)
            val cpuOptions = Interpreter.Options().apply { setNumThreads(4) }
            Interpreter(modelBuffer, cpuOptions)
        }
    }

    /** 从 assets 读取模型文件为 MappedByteBuffer（只读映射）。 */
    private fun loadModelFile(context: Context, fileName: String): MappedByteBuffer {
        val afd = context.assets.openFd(fileName)
        val inputStream = FileInputStream(afd.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = afd.startOffset
        val declaredLength = afd.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
}
