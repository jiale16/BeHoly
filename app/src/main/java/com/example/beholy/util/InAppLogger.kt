package com.example.beholy.util

import android.content.Context
import android.util.Log
import com.example.beholy.data.Constants
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 应用内日志器:同时输出到 Logcat 和内存缓冲区,供 UI 实时显示。
 *
 * - 所有日志带时间戳前缀,便于排查时序问题;
 * - 缓冲区上限 [MAX_LINES] 行,超出时丢弃最旧条目;
 * - 使用 [ConcurrentLinkedDeque] 保证多线程安全。
 *
 * 崩溃日志持久化:错误级别日志(E)会写入文件,供下次启动时回显。
 * 路径由 [init] 注入的 [Context.cacheDir] 决定(替代旧版不稳定的 java.io.tmpdir)。
 */
object InAppLogger {

    private const val TAG = "BeHoly"
    private const val MAX_LINES = 300
    private const val CRASH_LOG_FILE = "beholly_crash.log"
    private const val MAX_CRASH_LINES = 50

    private val deque = ConcurrentLinkedDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /** 崩溃日志目录,由 [init] 注入。null 时回退到 java.io.tmpdir(仅 init 前的早期日志)。 */
    @Volatile
    private var crashDir: File? = null

    /** 是否记录内存日志:默认关闭,用户点击「显示日志」后才开启 */
    @Volatile
    private var enabled = false

    /**
     * 初始化崩溃日志目录。应在 [com.example.beholy.BeHolyApp.onCreate] 中调用,
     * 用 [Context.getCacheDir] 替代不稳定的 `System.getProperty("java.io.tmpdir")`。
     * 重复调用安全:后者覆盖前者。
     */
    fun init(context: Context) {
        crashDir = context.cacheDir
    }

    fun setEnabled(on: Boolean) {
        enabled = on
    }

    /** 外部可注册的回调,用于 UI 刷新通知 */
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
        // 仅当「显示日志」开启后才写入内存日志(启动默认不记录)
        if (enabled) {
            deque.addLast(line)
            while (deque.size > MAX_LINES) {
                deque.pollFirst()
            }
            onUpdate?.invoke()
        }

        // ★ 如果是错误级别,同时写入文件持久化(崩溃后可查,不受显示开关影响)
        if (level == "E") {
            try {
                val dir = crashDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
                val logFile = File(dir, CRASH_LOG_FILE)
                logFile.appendText("$line\n")
                // 只保留最后 [MAX_CRASH_LINES] 行
                val lines = logFile.readLines()
                if (lines.size > MAX_CRASH_LINES) {
                    logFile.writeText(lines.takeLast(MAX_CRASH_LINES).joinToString("\n") + "\n")
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 获取所有日志(旧→新) */
    fun getLog(): String = deque.joinToString("\n")

    /** 清空日志 */
    fun clear() {
        deque.clear()
        onUpdate?.invoke()
    }

    /**
     * 读取并删除崩溃日志文件(供 MainActivity 启动时检查上次崩溃)。
     * 返回空串表示无崩溃日志或读取失败。
     */
    fun consumeCrashLog(): String {
        val dir = crashDir ?: return ""
        val logFile = File(dir, CRASH_LOG_FILE)
        if (!logFile.exists()) return ""
        val content = runCatching { logFile.readText() }.getOrDefault("")
        runCatching { logFile.delete() }
        return content
    }
}
