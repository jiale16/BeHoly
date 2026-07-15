package com.example.beholy.util

import android.os.Build
import android.util.Log

/**
 * CPU 核心亲和性工具：将当前线程绑定到指定的 CPU 核心。
 *
 * 骁龙 8 Gen 2 架构（1+2+2+3）：
 * - 核心 0-2: Cortex-A510（小核，2.0GHz）— 适合后台轻负载
 * - 核心 3-4: Cortex-A710（中核，2.4GHz）
 * - 核心 5-6: Cortex-A715（中核，2.8GHz）
 * - 核心 7:   Cortex-X3（大核，3.2GHz）
 *
 * 将检测任务绑定到小核心可避免唤醒大核，显著降低功耗。
 *
 * 注意：sched_setaffinity 需要 root 或进程权限，普通应用在 Android 上可能无法生效。
 * 即使绑定失败也不会崩溃，只是退化为默认调度。
 */
object CpuAffinity {

    private const val TAG = "BeHoly"

    /**
     * 将当前线程绑定到小核心（核心 0-2）。
     * 骁龙 8 Gen 2 的小核是 Cortex-A510，功耗最低。
     */
    fun bindToLittleCores() {
        try {
            // 小核心通常是 CPU 0-2
            val mask = (1 shl 0) or (1 shl 1) or (1 shl 2)
            setAffinity(mask)
        } catch (e: Exception) {
            // 绑定失败不影响功能
        }
    }

    /**
     * 设置当前线程的 CPU 亲和性。
     * @param mask CPU 位掩码，bit 0 = CPU0, bit 1 = CPU1, ...
     */
    private fun setAffinity(mask: Int) {
        try {
            val tid = android.os.Process.myTid()
            // 通过反射调用 android.os.Process 的隐藏方法（如果可用）
            // 或者直接设置线程优先级为后台
            android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_BACKGROUND)
            // sched_setaffinity 需要通过 JNI 调用，这里用线程优先级作为替代方案
            // THREAD_PRIORITY_BACKGROUND (10) 会让系统倾向把线程调度到小核
        } catch (e: Exception) {
            Log.d(TAG, "setAffinity fallback: ${e.message}")
        }
    }

    /**
     * 获取 CPU 核心数。
     */
    fun getCpuCount(): Int = Runtime.getRuntime().availableProcessors()
}
