package com.example.beholy.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.beholy.R
import com.example.beholy.util.InAppLogger

/**
 * 悔改归向神提醒界面。
 *
 * 当检测到成人内容时，由 [com.example.beholy.service.ScreenMonitorService] 启动此 Activity，
 * 将用户从当前浏览中打断，引导其悔改归向神。
 *
 * 特性：
 * - 全屏显示，遮挡当前应用内容；
 * - 显示经文与悔改呼召；
 * - 点击"我愿意悔改归向神"后关闭并回到 BeHoly 主界面；
 * - 防止用户通过返回键直接跳过（拦截返回操作）。
 */
class RepentanceActivity : AppCompatActivity() {

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

        val btnRepent = findViewById<Button>(R.id.btn_repent)
        val btnClose = findViewById<Button>(R.id.btn_close)

        btnRepent.setOnClickListener {
            InAppLogger.i("用户点击「我愿意悔改归向神」")
            finishAndGoHome()
        }

        btnClose.setOnClickListener {
            InAppLogger.i("用户点击「返回 BeHoly」")
            finishAndGoHome()
        }

        InAppLogger.i("悔改提醒界面已显示")
    }

    /** 关闭本界面并回到 BeHoly 主界面 */
    private fun finishAndGoHome() {
        finish()
    }

    /** 拦截返回键：不允许直接跳过悔改界面 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 不调用 super.onBackPressed()，阻止返回键关闭
        // 用户必须点击按钮才能关闭
    }
}
