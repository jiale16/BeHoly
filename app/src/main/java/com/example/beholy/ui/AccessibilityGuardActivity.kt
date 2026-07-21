package com.example.beholy.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.beholy.R
import com.example.beholy.util.InAppLogger

/**
 * 无障碍关闭劝诫守卫。
 *
 * 当用户已开启无障碍服务、却打开系统设置的无障碍页面（意图关闭本服务）时，
 * 由 [com.example.beholy.service.BeHolyAccessibilityService] 启动本界面，
 * 在用户「动手前」给一个转向神的停顿。
 *
 * 基调：恩典而非律法——非 Device Owner 下本就无法拦截系统开关，
 * 因此不强制阻止，而是呈现一段经文与一个诚实的提问，把这一刻变成与神对话的机会。
 *
 * 两个出口：
 * - 「我再想想，回到 BeHoly」：回到主界面，离开设置页（给一个转身离开的出路）；
 * - 「我仍要关闭」：仅关闭本页，回到设置页，尊重用户的自由。
 * 拦截返回键，要求用户做出明确选择，而非顺手关掉。
 */
class AccessibilityGuardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏时也能显示（点亮屏幕 + 解锁），确保劝诫及时可见
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

        setContentView(R.layout.activity_a11y_guard)

        val btnKeep = findViewById<Button>(R.id.btn_a11y_guard_keep)
        val btnClose = findViewById<Button>(R.id.btn_a11y_guard_close)

        btnKeep.setOnClickListener {
            InAppLogger.i("用户在劝诫页选择「我再想想」，返回主界面")
            finishAndGoHome()
        }

        btnClose.setOnClickListener {
            InAppLogger.i("用户在劝诫页选择「我仍要关闭」，关闭本页")
            finish() // 回到设置页，由用户自行决定
        }

        InAppLogger.i("无障碍关闭劝诫页已显示")
    }

    /** 关闭劝诫页并回到 BeHoly 主界面，离开设置页。 */
    private fun finishAndGoHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    /** 拦截返回键：要求用户明确选择，不直接放行。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 不调用 super.onBackPressed()，阻止返回键直接关闭
        // 用户必须点击按钮才能关闭（与悔改提醒页一致的体验）
    }
}
