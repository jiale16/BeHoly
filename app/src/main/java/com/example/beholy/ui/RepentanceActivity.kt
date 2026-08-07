package com.example.beholy.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.beholy.R
import com.example.beholy.data.Constants
import com.example.beholy.service.MonitoringService
import com.example.beholy.util.InAppLogger

/**
 * 悔改归向神提醒界面。
 *
 * 当检测到成人内容时，由 [com.example.beholy.service.MonitoringService] 启动此 Activity，
 * 将用户从当前浏览中打断，引导其悔改归向神。
 *
 * 特性：
 * - 全屏显示，遮挡当前应用内容；
 * - 显示经文与悔改呼召；
 * - 点击「我愿意悔改归向神」后进入反思表单 [RepentanceFormActivity]；
 * - 拦截返回键：用户必须点击按钮才能关闭（保持原行为）。
 *
 * 强度递进（非 DO 下的核心改造，由 EXTRA_HIT_COUNT 驱动）：
 * - 第 1-2 次：标准悔改页，「返回 BeHoly」按钮可立即点击；
 * - 第 3-4 次（达 TIER2_HIT_THRESHOLD）：显示「恳切呼召」递进文案，
 *   「返回 BeHoly」按钮最小停留 30 秒倒计时，期间置灰不可点；
 * - 第 5+ 次（达 TIER3_HIT_THRESHOLD）：显示「诚实对质」递进文案，
 *   最小停留 60 秒倒计时。
 * 把「冷静期」从 DO 下的系统锁屏语义，转移到非 DO 下的 UI 约束——
 * 强迫用户停顿悔改，而非立即点掉回到原 App。
 *
 * 双入口（决策7）：onCreate 与 onNewIntent 都调用 [handleIntent]，
 * 把 EXTRA_REASON / EXTRA_HIT_TIME / EXTRA_HIT_COUNT 暂存到字段，避免 singleTop 复用时丢失 extra。
 */
class RepentanceActivity : AppCompatActivity() {

    // 暂存从 MonitoringService 透传下来的命中信息（决策7：双入口统一暂存）
    private var pendingReason: String = ""
    private var pendingHitTime: Long = 0L
    private var pendingHitCount: Int = 0

    private lateinit var btnRepent: Button
    private lateinit var btnClose: Button
    private lateinit var tvProgression: TextView

    /** 最小停留剩余毫秒。>0 时「返回 BeHoly」按钮置灰倒计时。 */
    private var minStayMs: Long = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val countdownRunnable = object : Runnable {
        override fun run() {
            minStayMs -= 1000L
            if (minStayMs > 0L) {
                val secs = minStayMs / 1000L
                btnClose.text = "请停留 ${secs}s 后再返回"
                btnClose.postDelayed(this, 1000L)
            } else {
                // 倒计时结束：恢复按钮可点击
                btnClose.text = "返回 BeHoly"
                btnClose.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏时也能显示（点亮屏幕 + 解锁）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        setContentView(R.layout.activity_repentance)

        // 悔改页已在前台：取消可能残留的警示通知（含兜底 FullScreenIntent 通知），避免与弹窗重复停留在通知栏
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(Constants.HIT_NOTIFICATION_ID)
            nm.cancel(Constants.NOTIFICATION_ALERT_ID)
        }

        btnRepent = findViewById(R.id.btn_repent)
        btnClose = findViewById(R.id.btn_close)
        tvProgression = findViewById(R.id.tv_progression)

        // 决策7：setContentView + findViewById 之后，从 intent 暂存命中信息
        handleIntent(intent)

        // ★ 强度递进：按命中次数展示递进文案 + 设置最小停留时长
        applyProgression()

        btnRepent.setOnClickListener {
            InAppLogger.i("用户点击「我愿意悔改归向神」，进入反思表单")
            val formIntent = Intent(this, RepentanceFormActivity::class.java).apply {
                putExtra(Constants.EXTRA_REASON, pendingReason)
                putExtra(Constants.EXTRA_HIT_TIME, pendingHitTime)
            }
            startActivity(formIntent)
            // 决策5：表单 finish() 后直接回到被监控的原 App。此处一并结束本提醒界面，
            // 避免表单关闭后回落到悔改提示页造成「闪一下又回到提示页」的体验。
            finish()
        }

        btnClose.setOnClickListener {
            // 最小停留期内按钮被置灰，此回调不会触发（isEnabled=false）。双保险：再判一次。
            if (minStayMs > 0L) return@setOnClickListener
            InAppLogger.i("用户点击「返回 BeHoly」")
            // 直接取消所有悔改相关通知（兜底）：
            // restoreNotification 是异步的（通过 startForegroundService 发送 intent），
            // 依赖服务进程存活与 dailyNotificationSuppressed 状态一致。若服务被杀重启，
            // 状态会重置为 false 导致 restoreDailyNotification 提前 return 不取消通知。
            // 此处由 Activity 直接 cancel，确保用户离开悔改页时通知立即消失。
            runCatching {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(Constants.HIT_NOTIFICATION_ID)
                nm.cancel(Constants.NOTIFICATION_ALERT_ID)
            }
            // 悔改流程结束（用户选择关闭）：恢复金句通知
            MonitoringService.restoreNotification(this)
            finishAndGoHome()
        }

        InAppLogger.i("悔改提醒界面已显示（第 ${pendingHitCount} 次命中）")
    }

    /**
     * 按 hitCount 应用悔改页强度递进：
     * - 递进文案：第 1-2 次无；第 3-4 次恳切呼召；第 5+ 次诚实对质。
     * - 最小停留：第 1-2 次 0 秒；第 3-4 次 30 秒；第 5+ 次 60 秒。
     * 最小停留期内「返回 BeHoly」按钮置灰 + 倒计时显示。
     */
    private fun applyProgression() {
        val count = pendingHitCount.coerceAtLeast(0)

        // 先清理可能正在跑的倒计时：onNewIntent 在悔改页已前台时再次命中会重复进入本方法，
        // 若不移除旧 runnable，队列中将累积多个 countdownRunnable 并行执行，
        // 导致 minStayMs 每秒被减多次，倒计时跑得比实际快（命中次数越多越快）。
        handler.removeCallbacks(countdownRunnable)

        // 递进文案
        when {
            count >= Constants.TIER3_HIT_THRESHOLD -> {
                tvProgression.text = "你已经第 $count 次跌倒在这件事上。\n" +
                    "每一次你说『我愿意悔改』，\n" +
                    "却仍回到原地。\n" +
                    "现在，诚实地面对自己——\n" +
                    "你愿意让神真正得胜吗？"
                tvProgression.visibility = android.view.View.VISIBLE
            }
            count >= Constants.TIER2_HIT_THRESHOLD -> {
                tvProgression.text = "这是你第 $count 次在这件事上跌倒。\n" +
                    "罪总是在你最软弱时偷袭。\n" +
                    "不要只停留在外面的拦阻，\n" +
                    "让神进入你内心的深处。"
                tvProgression.visibility = android.view.View.VISIBLE
            }
            else -> {
                tvProgression.visibility = android.view.View.GONE
            }
        }

        // 最小停留时长
        minStayMs = Constants.resolveFromHitCount(count, Constants.MIN_STAY_BY_HIT_COUNT)
        if (minStayMs > 0L) {
            btnClose.isEnabled = false
            // 立即显示初始倒计时，再每秒刷新
            val initialSecs = minStayMs / 1000L
            btnClose.text = "请停留 ${initialSecs}s 后再返回"
            handler.postDelayed(countdownRunnable, 1000L)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 决策7：singleTop 复用时，新的 extra 走这里，需重新暂存并设为当前 intent
        setIntent(intent)
        handleIntent(intent)
        // 刷新强度递进 UI（命中次数/递进文案/最小停留时长可能已变化）
        applyProgression()
        // 悔改页已在前台：取消可能残留的警示通知（用户从通知点击重新进入时，通知应消失）
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(Constants.HIT_NOTIFICATION_ID)
            nm.cancel(Constants.NOTIFICATION_ALERT_ID)
        }
        InAppLogger.i("悔改页通过 onNewIntent 刷新（第 ${pendingHitCount} 次命中）")
    }

    /**
     * 读取并暂存 EXTRA_REASON / EXTRA_HIT_TIME / EXTRA_HIT_COUNT（决策7）。
     * 同时覆盖 onCreate 首次创建与 onNewIntent 复用两种情况，避免 extra 在 singleTop 下丢失。
     */
    private fun handleIntent(intent: Intent?) {
        pendingReason = intent?.getStringExtra(Constants.EXTRA_REASON) ?: ""
        pendingHitTime = intent?.getLongExtra(Constants.EXTRA_HIT_TIME, 0L) ?: 0L
        pendingHitCount = intent?.getIntExtra(Constants.EXTRA_HIT_COUNT, 0) ?: 0
    }

    /** 关闭悔改页并回到 BeHoly 主界面 */
    private fun finishAndGoHome() {
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    /** 拦截返回键：不允许直接跳过悔改界面 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 不调用 super.onBackPressed()，阻止返回键关闭
        // 用户必须点击按钮才能关闭
    }

    override fun onDestroy() {
        // 清理倒计时回调，避免 Activity 销毁后 Handler 持有引用导致泄漏
        handler.removeCallbacks(countdownRunnable)
        super.onDestroy()
    }
}
