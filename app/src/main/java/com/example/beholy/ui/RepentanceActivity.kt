package com.example.beholy.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
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
 * 双入口（决策7）：onCreate 与 onNewIntent 都调用 [handleIntent]，
 * 把 EXTRA_REASON / EXTRA_HIT_TIME 暂存到字段，避免 singleTop 复用时丢失 extra。
 */
class RepentanceActivity : AppCompatActivity() {

    // 暂存从 MonitoringService 透传下来的命中信息（决策7：双入口统一暂存）
    private var pendingReason: String = ""
    private var pendingHitTime: Long = 0L

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

        val btnRepent = findViewById<Button>(R.id.btn_repent)
        val btnClose = findViewById<Button>(R.id.btn_close)

        // 决策7：setContentView + findViewById 之后，从 intent 暂存命中信息
        handleIntent(intent)

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
            InAppLogger.i("用户点击「返回 BeHoly」")
            // 悔改流程结束（用户选择关闭）：恢复金句通知
            MonitoringService.restoreNotification(this)
            finishAndGoHome()
        }

        InAppLogger.i("悔改提醒界面已显示")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 决策7：singleTop 复用时，新的 extra 走这里，需重新暂存并设为当前 intent
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * 读取并暂存 EXTRA_REASON / EXTRA_HIT_TIME（决策7）。
     * 同时覆盖 onCreate 首次创建与 onNewIntent 复用两种情况，避免 extra 在 singleTop 下丢失。
     */
    private fun handleIntent(intent: Intent?) {
        pendingReason = intent?.getStringExtra(Constants.EXTRA_REASON) ?: ""
        pendingHitTime = intent?.getLongExtra(Constants.EXTRA_HIT_TIME, 0L) ?: 0L
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
}
