package com.example.beholy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.beholy.R
import com.example.beholy.service.MonitoringService
import com.example.beholy.util.HitLogger
import com.example.beholy.util.InAppLogger

/**
 * 主界面。
 *
 * 本增量交互流程（相比旧版去掉了截屏录屏授权）：
 * - 点击「开启监控」→ 检查无障碍服务是否已开启；未开启则引导去设置，已开启则启动前台常驻服务；
 * - 实时展示「无障碍服务」「设备所有者（Device Owner）」两类权限状态；
 * - 提供按钮：去开启无障碍、查看 Device Owner 的 adb 配置命令；
 * - 保留查看记录 / 清空日志；连续点击标题 5 次显示「停止监控」。
 *
 * 检测能力由 BeHolyAccessibilityService（需在系统设置中手动开启）提供；
 * 本 Activity 仅负责权限自检与引导，以及拉起常驻前台服务。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var permissionHelper: PermissionHelper
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvDeviceOwnerStatus: TextView
    private lateinit var btnStop: Button

    /** 标题点击计数（连点 5 次显示停止按钮） */
    private var titleClickCount = 0
    private var lastTitleClickTime = 0L

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartMonitorClick()
        } else {
            InAppLogger.w("通知权限被拒绝，无法显示常驻通知")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionHelper = PermissionHelper(this)

        val tvTitle = findViewById<TextView>(R.id.tv_title)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        tvDeviceOwnerStatus = findViewById(R.id.tv_device_owner_status)
        val btnStart = findViewById<Button>(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        val btnViewRecords = findViewById<Button>(R.id.btn_view_records)
        val btnClearLog = findViewById<Button>(R.id.btn_clear_log)
        val btnOpenAccessibility = findViewById<Button>(R.id.btn_open_accessibility)
        val btnConfigureDeviceOwner = findViewById<Button>(R.id.btn_configure_device_owner)
        tvLog = findViewById(R.id.tv_log)
        logScroll = findViewById(R.id.log_scroll)

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
            InAppLogger.i("点击「开启监控」")
            onStartMonitorClick()
        }

        btnStop.setOnClickListener {
            InAppLogger.i("停止监控")
            stopMonitor()
            btnStop.visibility = View.GONE
        }

        btnViewRecords.setOnClickListener { showRecords() }

        btnClearLog.setOnClickListener { InAppLogger.clear() }

        btnOpenAccessibility.setOnClickListener {
            permissionHelper.openAccessibilitySettings(this)
        }

        btnConfigureDeviceOwner.setOnClickListener { showDeviceOwnerDialog() }

        InAppLogger.onUpdate = { runOnUiThread { refreshLog() } }
        refreshLog()
        checkCrashLog()
        InAppLogger.i("应用启动，等待操作…")
    }

    override fun onResume() {
        super.onResume()
        // 每次返回前台刷新权限状态（无障碍/DO 可能已在设置页变更）
        refreshPermissionStatus()
    }

    /** 开启监控：先校验无障碍服务，再启动前台常驻服务。 */
    private fun onStartMonitorClick() {
        if (!permissionHelper.isAccessibilityEnabled(this)) {
            InAppLogger.w("尚未开启无障碍服务，请先开启")
            permissionHelper.openAccessibilitySettings(this)
            return
        }
        if (!permissionHelper.hasNotificationPermission()) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        MonitoringService.start(this)
        InAppLogger.i("已请求启动监控（前台常驻 + 无障碍检测）")
    }

    /** 停止监控：停止前台常驻服务（无障碍检测仍由系统按其生命周期管理）。 */
    private fun stopMonitor() {
        stopService(Intent(this, MonitoringService::class.java))
    }

    /** 刷新无障碍/设备所有者状态指示。 */
    private fun refreshPermissionStatus() {
        val a11y = permissionHelper.isAccessibilityEnabled(this)
        tvAccessibilityStatus.text =
            if (a11y) getString(R.string.status_accessibility_on)
            else getString(R.string.status_accessibility_off)
        tvAccessibilityStatus.setTextColor(
            if (a11y) Color.parseColor("#27AE60") else Color.parseColor("#E74C3C")
        )

        val doEnabled = permissionHelper.isDeviceOwner(this)
        tvDeviceOwnerStatus.text =
            if (doEnabled) getString(R.string.status_device_owner_on)
            else getString(R.string.status_device_owner_off)
        tvDeviceOwnerStatus.setTextColor(
            if (doEnabled) Color.parseColor("#27AE60") else Color.parseColor("#E67E22")
        )
    }

    /** 弹出 Device Owner 配置提示（含 adb 命令，可复制）。 */
    private fun showDeviceOwnerDialog() {
        val cmd = permissionHelper.deviceOwnerAdbCommand
        AlertDialog.Builder(this)
            .setTitle(R.string.do_setup_hint)
            .setMessage("${getString(R.string.do_adb_command)}\n\n$cmd")
            .setPositiveButton("复制并关闭") { _, _ -> copyToClipboard(cmd) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("BeHoly DO command", text))
            InAppLogger.i("已复制 Device Owner 配置命令")
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
        } catch (_: Exception) {
        }
    }
}
