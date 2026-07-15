package com.example.beholy.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.beholy.R
import com.example.beholy.service.ScreenMonitorService
import com.example.beholy.util.HitLogger
import com.example.beholy.util.InAppLogger

/**
 * 主界面。
 *
 * 交互流程：
 * - 点击「开始监控」→ 授权录屏 → 启动监控服务
 * - 点击「查看记录」→ 显示命中记录
 * - 连续点击标题 5 次 → 显示隐藏的「停止监控」按钮
 */
class MainActivity : AppCompatActivity() {

    private lateinit var permissionHelper: PermissionHelper
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnStop: Button

    /** 标题点击计数（连点 5 次显示停止按钮） */
    private var titleClickCount = 0
    private var lastTitleClickTime = 0L

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startMonitorService(result.resultCode, result.data!!)
        } else {
            InAppLogger.w("用户拒绝了屏幕捕获授权")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionHelper.requestScreenCapture(screenCaptureLauncher)
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Settings.canDrawOverlays(this)) {
            InAppLogger.i("悬浮窗权限已授予")
        } else {
            InAppLogger.w("悬浮窗权限未授予，悔改界面可能无法自动弹出")
        }
        proceedToScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionHelper = PermissionHelper(this)

        val tvTitle = findViewById<TextView>(R.id.tv_title)
        val btnStart = findViewById<Button>(R.id.btn_start)
        btnStop = findViewById<Button>(R.id.btn_stop)
        val btnViewRecords = findViewById<Button>(R.id.btn_view_records)
        val btnClearLog = findViewById<Button>(R.id.btn_clear_log)
        tvLog = findViewById<TextView>(R.id.tv_log)
        logScroll = findViewById<ScrollView>(R.id.log_scroll)

        // ★ 隐蔽停止：连续点击标题 5 次（3秒内）才显示停止按钮
        tvTitle.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTitleClickTime > 3000) {
                titleClickCount = 0
            }
            titleClickCount++
            lastTitleClickTime = now
            if (titleClickCount >= 5) {
                btnStop.visibility = View.VISIBLE
                InAppLogger.i("隐藏功能已激活")
                titleClickCount = 0
            }
        }

        btnStart.setOnClickListener {
            InAppLogger.i("点击「开始监控」")
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            proceedToScreenCapture()
        }

        btnStop.setOnClickListener {
            InAppLogger.i("停止监控")
            stopMonitorService()
            btnStop.visibility = View.GONE
        }

        btnViewRecords.setOnClickListener {
            showRecords()
        }

        btnClearLog.setOnClickListener {
            InAppLogger.clear()
        }

        InAppLogger.onUpdate = { runOnUiThread { refreshLog() } }
        refreshLog()
        checkCrashLog()
        InAppLogger.i("应用启动，等待操作…")

        // ★ 处理「锁屏后系统停止投影 → 点重新授权通知」的冷启动入口
        handleRegrantIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 若 MainActivity 已存在，系统停止投影后用户点通知会走到这里而非 onCreate
        setIntent(intent)
        handleRegrantIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        InAppLogger.onUpdate = null
    }

    /** 继续录屏授权流程 */
    private fun proceedToScreenCapture() {
        if (permissionHelper.hasNotificationPermission()) {
            permissionHelper.requestScreenCapture(screenCaptureLauncher)
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 处理「重新授权」入口。
     *
     * 场景：锁屏后系统主动停止了 MediaProjection，ScreenMonitorService 弹出了重新授权通知。
     * 用户点击通知后 MainActivity 被拉起并携带 EXTRA_REGRANT=true，
     * 这里自动走与「开始监控」一致的权限前置逻辑（悬浮窗权限检查），
     * 最终弹起系统录屏授权框，用户点「立即开始」后即恢复正常的屏幕监控。
     */
    private fun handleRegrantIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ScreenMonitorService.EXTRA_REGRANT, false) == true) {
            // 消费标记，避免 Activity 重建（如旋转屏幕）时重复拉起授权框
            intent.removeExtra(ScreenMonitorService.EXTRA_REGRANT)
            InAppLogger.i("收到重新授权请求，自动拉起录屏授权")
            // 与 btnStart 相同的权限前置逻辑：缺少悬浮窗权限则先申请，否则直接进入录屏授权
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            } else {
                proceedToScreenCapture()
            }
        }
    }

    /** 显示命中记录 */
    private fun showRecords() {
        val hits = HitLogger.hitCount(this)
        val records = HitLogger.read(this)
        val content = if (records.isBlank()) {
            "暂无记录"
        } else {
            "命中 $hits 次\n\n$records"
        }
        AlertDialog.Builder(this)
            .setTitle("监控记录")
            .setMessage(content)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun refreshLog() {
        tvLog.text = InAppLogger.getLog()
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun checkCrashLog() {
        try {
            val dir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            val logFile = java.io.File(dir, "beholly_crash.log")
            if (logFile.exists()) {
                val content = logFile.readText()
                if (content.isNotBlank()) {
                    InAppLogger.e("★★★ 上次崩溃日志 ★★\n$content")
                    logFile.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun startMonitorService(resultCode: Int, data: Intent) {
        val intent = ScreenMonitorService.startIntent(this, resultCode, data)
        ContextCompat.startForegroundService(this, intent)
        InAppLogger.i("已请求启动监控服务")
    }

    private fun stopMonitorService() {
        stopService(Intent(this, ScreenMonitorService::class.java))
    }

    private fun requestOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage("检测到成人内容后，BeHoly 需要悬浮窗权限才能立即弹出悔改提醒界面打断当前浏览。\n\n请在接下来的设置页面中授予「显示在其他应用上层」权限。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
            .setCancelable(false)
            .show()
    }
}
