package com.example.beholy.detection.text

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.beholy.data.Constants
import com.example.beholy.data.DetectionResult
import com.example.beholy.data.SensitiveWordDictionary
import com.example.beholy.detection.Detector
import com.example.beholy.util.InAppLogger
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 文字成人内容检测器。
 *
 * 实现思路：
 * 1. 使用 ML Kit 离线中文文字识别（ChineseTextRecognizer）对屏幕 Bitmap 做 OCR；
 * 2. 将识别出的全屏文本交给 [SensitiveWordDictionary] 做本地敏感词匹配；
 * 3. 命中任意敏感词即判定为文字命中。
 *
 * ★★ 关键中文注释要点 ★★
 * - 为何用中文模型：屏幕内容以中文为主，com.google.mlkit:text-recognition-chinese 提供离线中文识别。
 *   其基础模型在首次使用时由 ML Kit 在设备本地动态下发（属设备本地行为，不涉及我们自己的上传逻辑）。
 * - client 复用：TextRecognizer client 创建有一定开销，这里在构造时创建并复用，close() 时关闭，
 *   避免每次检测都重新创建。
 * - 调度：OCR 为 CPU 密集推理，统一在 Dispatchers.Default 执行；通过下方 [Task.await] 扩展以
 *   suspend 形式挂起等待，不阻塞线程。
 */
class TextNsfwDetector(context: Context) : Detector {

    // 复用同一个 OCR client，避免反复创建
    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val closed = AtomicBoolean(false)

    override suspend fun detect(bitmap: Bitmap): DetectionResult = withContext(Dispatchers.Default) {
        if (closed.get()) return@withContext DetectionResult()

        return@withContext try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(inputImage).await()
            val fullText = visionText.text ?: ""

            val hits = SensitiveWordDictionary.containsAny(fullText)
            DetectionResult(
                isTextHit = hits.isNotEmpty(),
                hitWords = hits,
                recognizedText = fullText
            )
        } catch (e: Exception) {
            DetectionResult()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { recognizer.close() }
            Log.i(Constants.LOG_TAG, "TextNsfwDetector 已释放")
        }
    }
}

/**
 * 将 Google Play Services 的 [Task] 转为 Kotlin 挂起函数。
 * 这样可避免额外引入 kotlinx-coroutines-play-services 依赖，同时保持非阻塞挂起语义。
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { exception -> cont.resumeWithException(exception) }
}
