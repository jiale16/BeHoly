package com.example.beholy

import android.app.Application
import android.util.Log
import com.example.beholy.util.InAppLogger
import com.example.beholy.util.JsonlToDbMigrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

        // ★ 初始化 InAppLogger 崩溃日志目录:用 cacheDir 替代不稳定的 java.io.tmpdir
        // 必须在注册未捕获异常处理器之前调用,确保早期崩溃也能落盘
        InAppLogger.init(this)

        // ★ 注册全局未捕获异常处理器:崩溃信息写入 InAppLogger
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            InAppLogger.e("★★★ 应用崩溃: ${throwable.javaClass.name}: ${throwable.message}")
            Log.e("BeHoly", "崩溃详情", throwable)
            // 调用之前的处理器，让系统正常处理崩溃
            previousHandler?.uncaughtException(thread, throwable)
        }

        // ★ 一次性迁移：将旧版 detection_log.txt 中的命中记录导入 Room 数据库
        applicationScope.launch {
            JsonlToDbMigrator.migrateIfNeeded(this@BeHolyApp)
        }
    }
}
