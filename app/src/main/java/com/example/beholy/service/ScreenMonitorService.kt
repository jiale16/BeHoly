package com.example.beholy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.beholy.R
import com.example.beholy.data.Constants
import com.example.beholy.data.DailyVerse
import com.example.beholy.data.DetectionResult
import com.example.beholy.util.HitLogger
import com.example.beholy.util.InAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 前台屏幕监控服务。
 *
 * 常驻通知显示每日金句，检测命中时弹出悔改界面。
 */
class ScreenMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var detectionLoop: DetectionLoop? = null
    private var mediaProjection: MediaProjection? = null

    /** 是否用户手动停止监控。用于区分「用户主动停止(onDestroy)」与「系统主动停止投影(如锁屏被回收)」，
     *  避免手动停止时误弹重新授权通知。生命周期回调均在主线程执行，@Volatile 仅作跨线程可见性兜底。 */
    @Volatile
    private var isManualStop = false

    @Volatile
    private var lastHitTimeMs: Long = 0L

    companion object {
        const val HIT_COOLDOWN_MS = 60_000L
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        /** 重新授权标记：由本服务发出的「重新授权」通知携带，MainActivity 据此自动拉起录屏授权 */
        const val EXTRA_REGRANT = "extra_regrant"

        /** 重新授权通知专用渠道 ID */
        const val REGRANT_CHANNEL_ID = "beholy_regrant"

        /** 重新授权通知 ID（与常驻通知 NOTIFICATION_ID=1001、命中通知 HIT_NOTIFICATION_ID=1002 区分，避免撞值） */
        const val REGRANT_NOTIFICATION_ID = 2

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenMonitorService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        InAppLogger.i("onStartCommand: resultCode=$resultCode, data=${data != null}")

        if (resultCode == Int.MIN_VALUE || data == null) {
            InAppLogger.e("缺少授权数据，停止服务")
            stopSelf()
            return START_NOT_STICKY
        }

        // ★ Android 14 强制要求顺序：
        //   1) startForeground 带 mediaProjection 类型（激活前台服务权限）
        //   2) getMediaProjection（此时系统允许创建 MediaProjection）

        // 步骤1：启动前台服务（mediaProjection 类型）
        var foregroundStarted = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Constants.NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(Constants.NOTIFICATION_ID, buildNotification())
            }
            foregroundStarted = true
            InAppLogger.i("前台服务已启动(mediaProjection 类型)")
        } catch (e: Exception) {
            InAppLogger.e("startForeground(mediaProjection) 失败: ${e.javaClass.name}: ${e.message}")
        }

        if (!foregroundStarted) {
            InAppLogger.e("前台服务启动失败，停止")
            stopSelf()
            return START_NOT_STICKY
        }

        // ★ 支持反复授权恢复：先安全释放上一次的投影与循环，避免叠加多个 MediaProjection / 检测循环。
        //   释放旧投影前临时置 isManualStop=true，避免 mediaProjection.stop() 触发 onStop 时误弹重新授权通知；
        //   待新投影与回调注册完成后再置回 false，以便后续系统再次停止时能正常提示重新授权。
        isManualStop = true
        runCatching { detectionLoop?.stop() }
        runCatching { mediaProjection?.stop() }
        detectionLoop = null
        mediaProjection = null

        // 步骤2：获取 MediaProjection
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = try {
            projectionManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            InAppLogger.e("getMediaProjection 异常: ${e.javaClass.name}: ${e.message}")
            null
        }

        if (mediaProjection == null) {
            InAppLogger.e("MediaProjection 为 null")
            stopSelf()
            return START_NOT_STICKY
        }
        InAppLogger.i("MediaProjection 创建成功")

        // ★ 步骤2.5：Android 14 强制要求在 createVirtualDisplay 之前注册 callback
        //    （检测到 MediaProjection 状态变化时收到通知，用于资源清理）
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                InAppLogger.w("MediaProjection onStop")
                // 用户撤销了录屏授权，或系统主动停止（如锁屏后被 OEM 省电/隐私策略回收），先清理检测循环
                detectionLoop?.stop()
                detectionLoop = null
                // 区分：用户手动停止（onDestroy 已通过 isManualStop=true 标记） vs 系统主动停止
                if (!isManualStop) {
                    // 系统停了投影 → 提示用户点通知重新授权，以恢复屏幕监控
                    showRegrantNotification()
                }
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        // 步骤3：启动检测循环
        detectionLoop = DetectionLoop(this, mediaProjection!!)
        detectionLoop?.start(3000L) { result -> onHit(result) }
        InAppLogger.i("监控已启动")
        HitLogger.log(this, "启动监控")
        // 新的投影与回调已就绪，恢复「系统停止可提示重新授权」的状态
        isManualStop = false
        return START_NOT_STICKY
    }

    /** 命中回调 */
    private fun onHit(result: DetectionResult) {
        // 本应用在前台时跳过
        if (result.recognizedText.contains("BeHoly", ignoreCase = true)) {
            return
        }

        // 冷却期判断
        val now = System.currentTimeMillis()
        if (lastHitTimeMs > 0 && (now - lastHitTimeMs) < HIT_COOLDOWN_MS) {
            return
        }
        lastHitTimeMs = now

        val reason = buildString {
            if (result.isImageNsfw) append("成人画面")
            if (result.isTextHit) {
                if (isNotEmpty()) append("、")
                append("敏感文字")
            }
        }
        InAppLogger.w("检测到：$reason")
        HitLogger.log(this, "检测命中", reason)
        showRepentance(reason)
    }

    /** 弹出悔改界面：直接 startActivity + FullScreenIntent 双管齐下 */
    private fun showRepentance(reason: String) {
        val repentanceIntent = Intent(this, com.example.beholy.ui.RepentanceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try { startActivity(repentanceIntent) } catch (_: Exception) {}

        try {
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, repentanceIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel("repentance_alert", "悔改警示", NotificationManager.IMPORTANCE_HIGH).apply {
                    setBypassDnd(true)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
            }

            val notification = NotificationCompat.Builder(this, "repentance_alert")
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
            InAppLogger.e("弹出悔改界面失败", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 弹出「重新授权」通知，提示用户恢复屏幕监控。
     *
     * 为何需要用户重新点？MediaProjection 的重新获取必须经由系统授权框
     * （MediaProjectionManager.createScreenCaptureIntent()），应用层无法静默恢复，
     * 因此只能提示用户点击通知 → 拉起 MainActivity → 自动弹出系统录屏授权框，
     * 用户点「立即开始」后即回到正常监控。
     *
     * 为何用 FLAG_ACTIVITY_NEW_TASK？本方法在 Service 中调用，Service 没有任务栈上下文，
     * 启动 Activity 必须带 NEW_TASK；CLEAR_TOP 用于复用已存在的 MainActivity 实例，
     * 使其走到 onNewIntent 而非重复创建。
     */
    private fun showRegrantNotification() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 建立重新授权专用渠道（已存在则忽略）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                REGRANT_CHANNEL_ID,
                "BeHoly 重新授权",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "屏幕录制已被系统停止，需重新授权以恢复监控"
                setBypassDnd(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // 点击通知 → 拉起 MainActivity，并携带 EXTRA_REGRANT 标记，让其自动进入录屏授权流程
        val regrantIntent = Intent(this, com.example.beholy.ui.MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(EXTRA_REGRANT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            REGRANT_NOTIFICATION_ID,
            regrantIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, REGRANT_CHANNEL_ID)
            .setContentTitle("BeHoly 录屏已停止")
            .setContentText("点击重新授权以恢复屏幕监控")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(REGRANT_NOTIFICATION_ID, notification)
        InAppLogger.w("已弹出重新授权通知，等待用户点按恢复监控")
    }

    override fun onDestroy() {
        // ★ 标记手动停止，确保随后 mediaProjection?.stop() 触发 onStop 时不会误弹重新授权通知
        isManualStop = true
        HitLogger.log(this, "停止监控")
        detectionLoop?.stop()
        detectionLoop = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ===================== 通知相关 =====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                "BeHoly 监控",
                NotificationManager.IMPORTANCE_DEFAULT  // 改为 DEFAULT，避免被折叠
            ).apply {
                description = "屏幕内容监控"
                setShowBadge(false)
                enableVibration(false)  // 不震动
                setSound(null, null)    // 无声音
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    /** 构造常驻通知：显示每日金句（可传入自定义文案） */
    private fun buildNotification(customText: String? = null): Notification {
        val text = customText ?: DailyVerse.today()
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("BeHoly · 每日金句")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))  // 完整显示金句
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(null)
            .setVibrate(null)
            .build()
    }
}
