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

    companion object {
        /** 当前是否前台常驻运行中（供 MainActivity 同步按钮状态）。 */
        @Volatile
        var isRunning: Boolean = false

        /** 拉起悔改页（Tier1 及所有层级的最终提醒） */
        const val ACTION_SHOW_REPENTANCE = "com.example.beholy.action.SHOW_REPENTANCE"

        /** 执行封禁/锁屏/重启处置（Tier2 / Tier3） */
        const val ACTION_DISPOSE = "com.example.beholy.action.DISPOSE"

        const val EXTRA_TIER = "extra_tier"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_WORDS = "extra_words"

        /** 静态便捷方法：由 MainActivity 启动前台服务（常驻通知）。 */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
            }
        }

        /** 静态便捷方法：请求拉起悔改页。 */
        fun startShowRepentance(context: Context, reason: String, hitTime: Long) {
            val intent = Intent(context, MonitoringService::class.java).apply {
                action = ACTION_SHOW_REPENTANCE
                putExtra(Constants.EXTRA_REASON, reason)
                putExtra(Constants.EXTRA_HIT_TIME, hitTime)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 静态便捷方法：请求执行封禁/锁屏/重启处置。 */
        fun startDispose(
            context: Context,
            tier: Int,
            pkg: String,
            words: List<String>,
            reason: String
        ) {
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
        startForegroundSafe()
        registerScreenReceiver()
        isRunning = true
        InAppLogger.i("MonitoringService 已启动（前台常驻）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_REPENTANCE -> {
                val reason = intent.getStringExtra(Constants.EXTRA_REASON) ?: ""
                val hitTime = intent.getLongExtra(Constants.EXTRA_HIT_TIME, System.currentTimeMillis())
                showRepentance(reason, hitTime)
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

    /** 启动前台服务（specialUse 子类型，Android 14+ 需带类型参数）。 */
    private fun startForegroundSafe() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Constants.NOTIFICATION_ID, notification)
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

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                repentanceIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(this, "beholy_repentance_alert")
                .setContentTitle("⚠ BeHoly 警示")
                .setContentText("请悔改归向神")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent)
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
