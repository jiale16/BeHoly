package com.example.beholy.util

import android.util.Log
import com.example.beholy.data.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 应用内日志器：同时输出到 Logcat 和内存缓冲区，供 UI 实时显示。
 *
 * - 所有日志带时间戳前缀，便于排查时序问题；
 * - 缓冲区上限 [MAX_LINES] 行，超出时丢弃最旧条目；
 * - 使用 [ConcurrentLinkedDeque] 保证多线程安全。
 */
object InAppLogger {

    private const val MAX_LINES = 300
    private val deque = ConcurrentLinkedDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /** 外部可注册的回调，用于 UI 刷新通知 */
    @Volatile
    var onUpdate: (() -> Unit)? = null

    private fun now(): String = timeFmt.format(Date())

    fun d(msg: String) {
        Log.d(Constants.LOG_TAG, msg)
        append("D", msg)
    }

    fun i(msg: String) {
        Log.i(Constants.LOG_TAG, msg)
        append("I", msg)
    }

    fun w(msg: String) {
        Log.w(Constants.LOG_TAG, msg)
        append("W", msg)
    }

    fun w(msg: String, t: Throwable) {
        Log.w(Constants.LOG_TAG, msg, t)
        append("W", "$msg: ${t.javaClass.simpleName}: ${t.message}")
    }

    fun e(msg: String) {
        Log.e(Constants.LOG_TAG, msg)
        append("E", msg)
    }

    fun e(msg: String, t: Throwable) {
        Log.e(Constants.LOG_TAG, msg, t)
        append("E", "$msg: ${t.javaClass.simpleName}: ${t.message}")
    }

    private fun append(level: String, msg: String) {
        val line = "${now()} [$level] $msg"
        deque.addLast(line)
        while (deque.size > MAX_LINES) {
            deque.pollFirst()
        }
        onUpdate?.invoke()

        // ★ 如果是错误级别，同时写入文件持久化（崩溃后可查）
        if (level == "E") {
            try {
                val dir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")
                val logFile = java.io.File(dir, "beholly_crash.log")
                logFile.appendText("$line\n")
                // 只保留最后 50 行
                val lines = logFile.readLines()
                if (lines.size > 50) {
                    logFile.writeText(lines.takeLast(50).joinToString("\n") + "\n")
                }
            } catch (_: Exception) {}
        }
    }

    /** 获取所有日志（旧→新） */
    fun getLog(): String = deque.joinToString("\n")

    /** 清空日志 */
    fun clear() {
        deque.clear()
        onUpdate?.invoke()
    }
}
