package com.example.beholy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.beholy.R
import com.example.beholy.data.Constants
import com.example.beholy.data.DailyVerse
import com.example.beholy.ui.RepentanceActivity
import com.example.beholy.util.DeviceOwnerHelper
import com.example.beholy.util.HitLogger
import com.example.beholy.util.InAppLogger
import com.example.beholy.util.MonitorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit

/**
 * 轻量前台服务（由旧版 MonitoringService 重写而来）。
 *
 * 职责：
 * 1. 常驻通知（显示每日金句），保持进程优先级，避免被系统回收；
 * 2. **合规拉起悔改页**：以「高优通知 FullScreenIntent + 直接 startActivity」双路径拉起
 *    [RepentanceActivity]（仅本前台服务可后台启动 Activity，规避 Android 10+ 限制，见增量设计 §1.4）；
 * 3. 接收 [ACTION_DISPOSE] 指令，在 Device Owner 下执行封禁+锁屏（Tier2）或封禁+锁屏+重启（Tier3），
 *    并启动「冷静期」循环重锁。
 *
 * 该服务**不做**截屏/图像检测；检测由 [BeHolyAccessibilityService] 完成并经由本服务处置。
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false

    /** 屏幕解锁/亮屏监听：冷静期内若又落到被封应用，循环重锁。 */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
                // 仅在冷静期内生效；其余时间（未封禁）不干涉用户使用
                if (MonitorState.isInCooldown()) {
                    val pkg = MonitorState.getCooldownPackage()
                    if (pkg != null && isForegroundPackage(pkg)) {
                        DeviceOwnerHelper.lockNow(this@MonitoringService)
                        InAppLogger.w("冷静期循环重锁：$pkg")
                    }
                }
            }
        }
    }

    /** 金句通知是否被临时抑制（悔改流程期间暂停，避免与悔改警示并存） */
    @Volatile
    private var dailyNotificationSuppressed = false

    companion object {
        /** 当前是否前台常驻运行中（供 MainActivity 同步按钮状态）。 */
        @Volatile
        var isRunning: Boolean = false

        /** 拉起悔改页（Tier1 及所有层级的最终提醒） */
        const val ACTION_SHOW_REPENTANCE = "com.example.beholy.action.SHOW_REPENTANCE"

        /** 执行封禁/锁屏/重启处置（Tier2 / Tier3） */
        const val ACTION_DISPOSE = "com.example.beholy.action.DISPOSE"

        /** 恢复方句通知（悔改流程结束后调用） */
        const val ACTION_RESTORE_NOTIFICATION = "com.example.beholy.action.RESTORE_NOTIFICATION"

        /** 本次启动是否为悔改/处置动作（由 startShowRepentance / startDispose 设 true）。
         *  若为 true，onCreate 中 startForegroundSafe 用悔改警示通知而非金句通知，
         *  避免金句闪现后再 cancel 的视觉问题。 */
        @Volatile
        private var pendingRepentanceAction = false

        const val EXTRA_TIER = "extra_tier"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_WORDS = "extra_words"

        /** 静态便捷方法：由 MainActivity 启动前台服务（常驻通知），并开启金句通知。 */
        fun start(context: Context) {
            // 仅用户主动点击「显示每日金句」才允许金句通知出现
            setDailyEnabled(context, true)
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
            }
        }

        /** 持久化金句启用状态（跨进程重启保留）。 */
        private fun setDailyEnabled(context: Context, enabled: Boolean) {
            runCatching {
                context.getSharedPreferences(Constants.PREFS_MONITOR, Context.MODE_PRIVATE)
                    .edit().putBoolean(Constants.KEY_DAILY_ENABLED, enabled).apply()
            }
        }

        /** 读取金句启用状态。 */
        private fun isDailyEnabled(context: Context): Boolean =
            runCatching {
                context.getSharedPreferences(Constants.PREFS_MONITOR, Context.MODE_PRIVATE)
                    .getBoolean(Constants.KEY_DAILY_ENABLED, false)
            }.getOrDefault(false)

        /** 静态便捷方法：请求拉起悔改页。标记悔改动作，使 onCreate 用警示通知而非金句。 */
        fun startShowRepentance(context: Context, reason: String, hitTime: Long) {
            pendingRepentanceAction = true
            val intent = Intent(context, MonitoringService::class.java).apply {
                action = ACTION_SHOW_REPENTANCE
                putExtra(Constants.EXTRA_REASON, reason)
                putExtra(Constants.EXTRA_HIT_TIME, hitTime)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 静态便捷方法：恢复金句通知（悔改流程结束后调用）。 */
        fun restoreNotification(context: Context) {
            val intent = Intent(context, MonitoringService::class.java).apply {
                action = ACTION_RESTORE_NOTIFICATION
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 静态便捷方法：请求执行封禁/锁屏/重启处置。标记悔改动作，使 onCreate 用警示通知而非金句。 */
        fun startDispose(
            context: Context,
            tier: Int,
            pkg: String,
            words: List<String>,
            reason: String
        ) {
            pendingRepentanceAction = true
            val intent = Intent(context, MonitoringService::class.java).apply {
                action = ACTION_DISPOSE
                putExtra(EXTRA_TIER, tier)
                putExtra(EXTRA_PACKAGE, pkg)
                putExtra(EXTRA_WORDS, ArrayList(words))
                putExtra(Constants.EXTRA_REASON, reason)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 优先级：
        // 1) 本次启动是悔改/处置动作 → 警示通知前台（瞬时，悔改结束再决定恢复成金句/中性）
        // 2) 用户已主动开启金句 → 金句常驻通知
        // 3) 其余（检测命中拉起、系统 START_STICKY 重启等）→ 中性守护通知，绝不擅自显示金句
        when {
            pendingRepentanceAction -> {
                startForegroundWithRepentanceNotification()
                dailyNotificationSuppressed = true
                pendingRepentanceAction = false
            }
            isDailyEnabled(this) -> {
                startForegroundSafe()
            }
            else -> {
                startForegroundNeutral()
            }
        }
        registerScreenReceiver()
        isRunning = true
        InAppLogger.i("MonitoringService 已启动（前台常驻）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_REPENTANCE -> {
                val reason = intent.getStringExtra(Constants.EXTRA_REASON) ?: ""
                val hitTime = intent.getLongExtra(Constants.EXTRA_HIT_TIME, System.currentTimeMillis())
                // 金句通知抑制已在 onCreate 中完成（pendingRepentanceAction 标记），无需再调 suppressDailyNotification
                showRepentance(reason, hitTime)
            }
            ACTION_RESTORE_NOTIFICATION -> {
                // 悔改流程结束：恢复金句常驻通知
                restoreDailyNotification()
            }
            ACTION_DISPOSE -> {
                val tier = intent.getIntExtra(EXTRA_TIER, Constants.TIER1)
                val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: ""
                @Suppress("DEPRECATION")
                val words = intent.getStringArrayListExtra(EXTRA_WORDS) ?: arrayListOf()
                val reason = intent.getStringExtra(Constants.EXTRA_REASON) ?: ""
                dispose(tier, pkg, words, reason)
            }
            else -> {
                // 无 action：仅保持前台常驻（如由 MainActivity.start 拉起）
            }
        }
        return START_STICKY
    }

    /**
     * 执行分级处置：
     * - 是 Device Owner：Tier2 → 封禁+锁屏+冷静期；Tier3 → 封禁+锁屏+重启；
     * - 非 Device Owner：写操作降级跳过，仅弹悔改提醒（由调用方 DisposalExecutor 已先行降级）。
     * 无论是否 DO，均拉起悔改页（Tier3 重启除外，设备即将重启）。
     */
    private fun dispose(tier: Int, pkg: String, words: List<String>, reason: String) {
        val now = System.currentTimeMillis()

        // 金句通知抑制已在 onCreate 中完成（pendingRepentanceAction 标记），无需再调 suppressDailyNotification

        // 强制关闭（隐藏/封禁）违规应用：进程被杀、桌面不可见、无法打开（需 Device Owner）
        DeviceOwnerHelper.hideApp(this, pkg)

        if (tier >= Constants.TIER3) {
            InAppLogger.e("Tier3 严重命中：封禁 + 锁屏 + 重启设备（$pkg）")
            DeviceOwnerHelper.lockNow(this)
            DeviceOwnerHelper.reboot(this)
            return
        }
        if (tier >= Constants.TIER2) {
            DeviceOwnerHelper.lockNow(this)
            startCooldown(pkg)
        }
        showRepentance(reason, now)
        HitLogger.log(this, "处置完成 tier=$tier pkg=$pkg words=${words.size}")
    }

    /**
     * 弹出悔改界面：直接 startActivity（NEW_TASK + CLEAR_TOP）+ 高优 FullScreenIntent 通知兜底。
     * 仅由本前台服务调用，符合 Android 10+ 后台启动限制（见增量设计 §1.4）。
     *
     * 悔改页成功弹到前台后，不再额外推送冗余的警示通知（否则弹窗与通知栏通知会同时出现）；
     * 仅在 startActivity 失败（后台启动受限）时，才用 FullScreenIntent 通知作为兜底拉起。
     */
    private fun showRepentance(reason: String, hitTime: Long) {
        val repentanceIntent = Intent(this, RepentanceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Constants.EXTRA_REASON, reason)
            putExtra(Constants.EXTRA_HIT_TIME, hitTime)
        }
        val launched = runCatching { startActivity(repentanceIntent) }.isSuccess
        if (!launched) {
            // 直接启动失败：由 FullScreenIntent 通知兜底拉起悔改页
            showRepentanceNotification(repentanceIntent)
        }
    }

    /**
     * 进入冷静期：记录封禁包与到期时间，并安排到期自动清除。
     * 循环重锁由 [screenStateReceiver] + [MonitorState.isInCooldown] 控制。
     */
    private fun startCooldown(pkg: String) {
        MonitorState.setCooldown(Constants.COOLDOWN_PERIOD_MS, pkg)
        InAppLogger.i("进入冷静期 ${TimeUnit.MILLISECONDS.toSeconds(Constants.COOLDOWN_PERIOD_MS)} 秒，封禁 $pkg")
        handler.removeCallbacks(cooldownRunnable)
        handler.postDelayed(cooldownRunnable, Constants.COOLDOWN_PERIOD_MS)
    }

    private val cooldownRunnable = Runnable { stopCooldown() }

    /** 冷静期结束：清除状态、取消定时器、注销监听。 */
    private fun stopCooldown() {
        MonitorState.clearCooldown()
        handler.removeCallbacks(cooldownRunnable)
        InAppLogger.i("冷静期结束，停止循环重锁")
    }

    /** 判断指定包是否当前前台应用（需 PACKAGE_USAGE_STATS 权限；未授予时安全返回 false）。 */
    private fun isForegroundPackage(target: String): Boolean {
        return runCatching {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 5000L,
                now
            )
            val last = stats.maxByOrNull { it.lastTimeUsed }
            last?.packageName == target
        }.getOrDefault(false)
    }

    // ===================== 通知相关 =====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                "BeHoly 监护",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "离线内容监护常驻通知"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    /** 启动前台服务（金句通知，specialUse 子类型，Android 14+ 需带类型参数）。 */
    private fun startForegroundSafe() {
        // 已在悔改流程中抑制：不再更新前台通知，避免覆盖警示通知
        if (dailyNotificationSuppressed) {
            InAppLogger.i("金句通知已被抑制，跳过 startForegroundSafe")
            return
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIFICATION_DAILY_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Constants.NOTIFICATION_DAILY_ID, notification)
        }
    }

    /** 以悔改警示通知启动前台服务：命中时服务首次启动，用警示而非金句做前台通知，
     *  避免金句通知闪现后再 cancel 的视觉问题。悔改流程结束后 restoreDailyNotification 恢复金句。 */
    private fun startForegroundWithRepentanceNotification() {
        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("⚠ BeHoly 警示")
            .setContentText("请悔改归向神")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(null)
            .setVibrate(null)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIFICATION_ALERT_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Constants.NOTIFICATION_ALERT_ID, notification)
        }
    }

    /** 暂停金句常驻通知：悔改流程期间取消，避免与悔改警示通知语义冲突。 */
    private fun suppressDailyNotification() {
        if (dailyNotificationSuppressed) return
        dailyNotificationSuppressed = true
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(Constants.NOTIFICATION_DAILY_ID)
        InAppLogger.i("金句通知已暂停（悔改流程中）")
    }

    /** 恢复常驻通知：悔改流程结束后，若用户已开启金句则恢复金句，否则恢复中性守护通知。 */
    private fun restoreDailyNotification() {
        if (!dailyNotificationSuppressed) return
        dailyNotificationSuppressed = false
        if (isDailyEnabled(this)) {
            startForegroundSafe()
            InAppLogger.i("金句通知已恢复（悔改流程结束）")
        } else {
            startForegroundNeutral()
            InAppLogger.i("中性守护通知已恢复（悔改流程结束，金句未启用）")
        }
    }

    /** 中性守护前台通知：服务因检测/系统重启等原因常驻，但用户未开启金句时使用，绝不显示金句内容。 */
    private fun startForegroundNeutral() {
        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BeHoly 守护运行中")
            .setContentText("内容监护已开启")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setVibrate(null)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIFICATION_DAILY_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Constants.NOTIFICATION_DAILY_ID, notification)
        }
    }

    /** 构造常驻通知：显示每日金句。 */
    private fun buildNotification(): Notification {
        val text = DailyVerse.today()
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BeHoly · 每日金句")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(null)
            .setVibrate(null)
            // 前台时也立即显示常驻通知，避免被 Android 12+ 的 FGS 通知延迟策略压住
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** 高优 FullScreenIntent 通知兜底：即便直接 startActivity 失败也能点亮屏幕弹出悔改页。 */
    private fun showRepentanceNotification(repentanceIntent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "beholy_repentance_alert",
                    "悔改警示",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setBypassDnd(true)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }

            /**
             * 纯净 Intent：仅携带数据 extra，不携带 Activity launch flag。
             * PendingIntent 用 requestCode=1 + FLAG_ONE_SHOT 包装 launch flag，
             * 避免 Android 把 NEW_TASK/CLEAR_TOP 当成 PendingIntent 匹配条件导致复用失败。
             */
            val cleanRepentanceIntent = Intent(this, RepentanceActivity::class.java).apply {
                putExtra(Constants.EXTRA_REASON, repentanceIntent.getStringExtra(Constants.EXTRA_REASON))
                putExtra(Constants.EXTRA_HIT_TIME, repentanceIntent.getLongExtra(Constants.EXTRA_HIT_TIME, 0L))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            // 通知点击行为：一次性 PendingIntent，确保每次点击都创建新的启动任务
            val contentPendingIntent = PendingIntent.getActivity(
                this,
                1,
                cleanRepentanceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            // 全屏唤醒兜底：一次性 PendingIntent
            val fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                2,
                cleanRepentanceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, "beholy_repentance_alert")
                .setContentTitle("⚠ BeHoly 警示")
                .setContentText("请悔改归向神")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(contentPendingIntent)
                .setAutoCancel(true)
                .build()

            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(Constants.HIT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            InAppLogger.e("弹出悔改通知失败", e)
        }
    }

    // ===================== 广播接收器 =====================

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching { registerReceiver(screenStateReceiver, filter) }
        receiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(screenStateReceiver) }
        receiverRegistered = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCooldown()
        unregisterScreenReceiver()
        scope.cancel()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
