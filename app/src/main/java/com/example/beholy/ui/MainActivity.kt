package com.example.beholy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.beholy.R
import com.example.beholy.service.MonitoringService
import com.example.beholy.util.HitLogger
import com.example.beholy.util.InAppLogger
import com.example.beholy.util.RepentanceStore
import com.example.beholy.util.StreakStore

/**
 * 主界面。
 *
 * 本增量交互流程（相比旧版去掉了截屏录屏授权）：
 * - 点击「显示每日金句」→ 检查无障碍服务是否已开启；未开启则引导去设置，已开启则启动前台常驻服务（常驻通知展示每日金句）；
 * - 实时展示「无障碍服务」权限状态；Device Owner 配置入口收进右上角三个点菜单（多数用户无法设置）；
 * - 主界面并排提供「显示每日金句」/「停止显示」按钮，按服务运行状态启用其一；
 *
 * 检测能力由 BeHolyAccessibilityService（需在系统设置中手动开启）提供；
 * 本 Activity 仅负责权限自检与引导，以及拉起常驻前台服务。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var permissionHelper: PermissionHelper
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvStep1Title: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnOpenAccessibility: Button
    private lateinit var tvStreak: TextView
    private lateinit var tvStreakSub: TextView
    private lateinit var logContainer: LinearLayout
    private lateinit var tvLogTitle: TextView
    private var logsVisible: Boolean = true
    private var logsExpanded: Boolean = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartVerseClick()
        } else {
            InAppLogger.w("通知权限被拒绝，无法显示常驻通知")
        }
    }

    /** 导出悔改记录：系统文件选择器（SAF）写入用户自选位置，零权限、跨卸载保留。 */
    private val exportDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            InAppLogger.w("导出已取消")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val count = RepentanceStore.count(this@MainActivity)
            val ok = runCatching { RepentanceStore.exportToUri(this@MainActivity, uri) }.isSuccess
            withContext(Dispatchers.Main) {
                val msg = if (ok) {
                    "已导出 $count 条悔改记录到您选择的位置。\n\n该文件可长期保留；" +
                            "换签名重装或换机后，进入本页点「导入悔改」并选中此文件即可恢复。"
                } else {
                    "导出失败，请重试。"
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.export_repentance_title)
                    .setMessage(msg)
                    .setPositiveButton("关闭", null)
                    .show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 隐藏系统 ActionBar 标题（主题自带一个 "BeHoly"，与布局内 tv_title 重复），只保留布局标题
        supportActionBar?.hide()

        permissionHelper = PermissionHelper(this)

        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        tvStep1Title = findViewById(R.id.tv_step1_title)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        val btnViewRecords = findViewById<Button>(R.id.btn_view_records)
        val btnViewRepentance = findViewById<Button>(R.id.btn_view_repentance)
        val btnExportRepentance = findViewById<Button>(R.id.btn_export_repentance)
        btnOpenAccessibility = findViewById(R.id.btn_open_accessibility)
        val btnOverflow = findViewById<Button>(R.id.btn_overflow)
        tvLog = findViewById(R.id.tv_log)
        logScroll = findViewById(R.id.log_scroll)
        tvStreak = findViewById(R.id.tv_streak)
        tvStreakSub = findViewById(R.id.tv_streak_sub)
        logContainer = findViewById(R.id.log_container)
        tvLogTitle = findViewById(R.id.tv_log_title)
        tvLogTitle.setOnClickListener { toggleLogCollapse() }

        // 日志显隐偏好（默认显示；「关闭日志」后持久隐藏）
        logsVisible = getSharedPreferences("beholy_ui", MODE_PRIVATE)
            .getBoolean("logs_visible", true)
        applyLogVisibility()
        // 日志默认折叠，点击标题展开；菜单「显示日志」会展开
        if (!logsExpanded) {
            logScroll.visibility = View.GONE
            tvLogTitle.text = "📜 实时日志 ▼"
        }

        btnStart.setOnClickListener {
            InAppLogger.i("点击「显示每日金句」")
            onStartVerseClick()
        }

        btnStop.setOnClickListener {
            InAppLogger.i("点击「停止显示」")
            stopMonitor()
            updateVerseButtons(running = false)
        }

        btnViewRecords.setOnClickListener { showRecords() }

        btnViewRepentance.setOnClickListener { showRepentanceRecords() }

        btnExportRepentance.setOnClickListener { exportRepentance() }

        btnOpenAccessibility.setOnClickListener {
            permissionHelper.openAccessibilitySettings(this)
        }

        btnOverflow.setOnClickListener { showOverflowMenu() }

        InAppLogger.onUpdate = { runOnUiThread { refreshLog() } }
        refreshLog()
        checkCrashLog()
        InAppLogger.i("应用启动，等待操作…")
    }

    override fun onResume() {
        super.onResume()
        // 每次返回前台刷新权限状态（无障碍/DO 可能已在设置页变更）
        refreshPermissionStatus()
        updateStreakDisplay()
        // 同步金句按钮状态（服务可能在后台仍运行）
        updateVerseButtons(MonitoringService.isRunning)
    }

    /** 显示每日金句：先校验无障碍服务，再启动前台常驻服务（常驻通知展示每日金句，并保持进程优先级）。 */
    private fun onStartVerseClick() {
        if (!permissionHelper.isAccessibilityEnabled(this)) {
            InAppLogger.w("尚未开启无障碍服务，请先开启")
            permissionHelper.openAccessibilitySettings(this)
            return
        }
        if (!permissionHelper.hasNotificationPermission()) {
            InAppLogger.i("未授予通知权限，请求授权…")
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        MonitoringService.start(this)
        updateVerseButtons(running = true)
        InAppLogger.i("已开启每日金句常驻通知（前台服务已启动）")
    }

    /** 收起每日金句：停止前台常驻服务（无障碍检测仍由系统按其生命周期管理）。 */
    private fun stopMonitor() {
        stopService(Intent(this, MonitoringService::class.java))
    }

    /** 根据前台服务运行状态，启用「显示每日金句」/「停止显示」中对应的按钮（禁用另一个）。 */
    private fun updateVerseButtons(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    /** 刷新无障碍状态指示（Device Owner 状态收进右上角三个点菜单）。 */
    private fun refreshPermissionStatus() {
        val a11y = permissionHelper.isAccessibilityEnabled(this)
        // 无障碍开启时，确保今天计入连胜（打开 App 即视为受守护的一天）
        if (a11y) StreakStore.recordActiveToday(this)
        tvAccessibilityStatus.text =
            if (a11y) getString(R.string.status_accessibility_on)
            else getString(R.string.status_accessibility_off)
        tvAccessibilityStatus.setTextColor(
            if (a11y) Color.parseColor("#27AE60") else Color.parseColor("#E74C3C")
        )
        // 已开启无障碍则隐藏「步骤1 去开启」标题与「去开启」按钮，避免冗余入口；关闭时才展示引导
        tvStep1Title.visibility = if (a11y) View.GONE else View.VISIBLE
        btnOpenAccessibility.visibility = if (a11y) View.GONE else View.VISIBLE
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

    /** 关于弹窗：展示原主界面顶部那句应用介绍，并附版本号（运行时从 PackageManager 读取）。 */
    private fun showAboutDialog() {
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("")
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage("${getString(R.string.main_hint)}\n\n版本 $versionName")
            .setPositiveButton("关闭", null)
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

    /** 显示悔改日记（用户历次回转的结构化反思，本地查看、不联网）。 */
    private fun showRepentanceRecords() {
        lifecycleScope.launch {
            val content = RepentanceStore.toReadableText(this@MainActivity)
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.repentance_records_title)
                    .setMessage(content)
                    .setPositiveButton("关闭", null)
                    .show()
            }
        }
    }

    /** 导出悔改记录：通过系统文件选择器（SAF）写入用户自选位置（无需存储权限，跨卸载保留）。 */
    private fun exportRepentance() {
        exportDocLauncher.launch("beholy_repentance_backup.jsonl")
    }

    /** 右上角三个点菜单：步骤2（Device Owner 配置）收于此，避免干扰大多数无法设置的用户。 */
    private fun showOverflowMenu() {
        val anchor = findViewById<View>(R.id.btn_overflow)
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_overflow, popup.menu)
        val item = popup.menu.findItem(R.id.action_device_owner)
        item.title = if (permissionHelper.isDeviceOwner(this)) {
            "设备所有者：已配置 ✓"
        } else {
            getString(R.string.btn_configure_device_owner)
        }
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_device_owner -> {
                    showDeviceOwnerDialog()
                    true
                }
                R.id.action_show_log -> {
                    setLogsVisible(true)
                    true
                }
                R.id.action_hide_log -> {
                    setLogsVisible(false)
                    true
                }
                R.id.action_about -> {
                    showAboutDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** 连胜展示：大幅、喜庆、鼓励。0 天引导重新开始。 */
    private fun updateStreakDisplay() {
        val s = StreakStore.getStreak(this)
        if (s <= 0) {
            tvStreak.text = "✨ 今天重新开始"
            tvStreakSub.text = "开启守望，迈出第一步"
            tvStreak.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            val emoji = when {
                s >= 30 -> "🏆"
                s >= 7 -> "🔥"
                else -> "🌱"
            }
            tvStreak.text = "$emoji 连续守护 $s 天"
            tvStreakSub.text = "你做得很好，继续向前 💪"
            tvStreak.setTextColor(Color.parseColor("#C9971B"))
        }
    }

    /** 折叠/展开日志区（仅整体可见时有效）。 */
    private fun toggleLogCollapse() {
        if (!logsVisible) return
        logsExpanded = !logsExpanded
        logScroll.visibility = if (logsExpanded) View.VISIBLE else View.GONE
        tvLogTitle.text = if (logsExpanded) "📜 实时日志 ▲" else "📜 实时日志 ▼"
    }

    private fun applyLogVisibility() {
        logContainer.visibility = if (logsVisible) View.VISIBLE else View.GONE
    }

    /** 菜单控制：显示/关闭整个日志区；显示时自动展开。 */
    private fun setLogsVisible(visible: Boolean) {
        logsVisible = visible
        getSharedPreferences("beholy_ui", MODE_PRIVATE).edit()
            .putBoolean("logs_visible", visible).apply()
        applyLogVisibility()
        if (visible) {
            logsExpanded = true
            logScroll.visibility = View.VISIBLE
            tvLogTitle.text = "📜 实时日志 ▲"
        }
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
