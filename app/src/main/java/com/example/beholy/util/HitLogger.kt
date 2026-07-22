package com.example.beholy.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录器：将监控操作和检测结果记录到专用文件。
 *
 * 文件位置：应用私有存储 /detection_log.txt
 * 格式：每行一条，`时间 | 类型 | 详情`
 * 类型：启动监控、停止监控、检测命中、无障碍开启、无障碍关闭
 */
object HitLogger {

    private const val TAG = "BeHoly"
    private const val FILE_NAME = "detection_log.txt"
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 记录一次事件 */
    fun log(context: Context, type: String, detail: String = "") {
        try {
            val timestamp = timeFmt.format(Date())
            val line = if (detail.isEmpty()) {
                "$timestamp | $type\n"
            } else {
                "$timestamp | $type | $detail\n"
            }

            val file = File(context.filesDir, FILE_NAME)
            FileOutputStream(file, true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入记录失败", e)
        }
    }

    /** 读取所有记录 */
    fun read(context: Context): String {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText(Charsets.UTF_8) else ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 获取命中次数（只统计"检测命中"类型的行） */
    fun hitCount(context: Context): Int {
        return try {
            val text = read(context)
            if (text.isBlank()) 0
            else text.trim().split("\n").count { it.contains("| 检测命中") }
        } catch (e: Exception) {
            0
        }
    }
}
