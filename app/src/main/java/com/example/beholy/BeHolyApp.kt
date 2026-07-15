package com.example.beholy

import android.app.Application
import android.util.Log
import com.example.beholy.util.InAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级 Application。
 *
 * 主要职责：
 * 1. 持有全局协程作用域 [applicationScope]，供前台服务之外的初始化任务使用；
 * 2. 可在此集中初始化共享单例（如敏感词库、模型加载器）；
 * 3. 注册全局未捕获异常处理器，将崩溃信息写入 InAppLogger 供界面查看。
 */
class BeHolyApp : Application() {

    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()

        // ★ 注册全局未捕获异常处理器：崩溃信息写入 InAppLogger
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            InAppLogger.e("★★★ 应用崩溃: ${throwable.javaClass.name}: ${throwable.message}")
            Log.e("BeHoly", "崩溃详情", throwable)
            // 调用之前的处理器，让系统正常处理崩溃
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
