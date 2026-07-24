package com.example.beholy.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.beholy.R
import com.example.beholy.service.MonitoringService
import com.example.beholy.util.StreakStore
import kotlinx.coroutines.launch

/**
 * 恩典闭环页：悔改保存后显示，闭合属灵循环。
 *
 * 设计意图：
 * - 用户完成悔改反思表单后，给予安慰与鼓励，避免「记录完就结束」的机械感。
 * - 展示当前得胜天数，强化正向反馈。
 * - 提供明确出口（「阿们，继续前行」），让用户带着盼望回到原 App。
 *
 * 启动方式：
 * - 由 RepentanceFormActivity.onSave() 在保存成功后启动。
 * - 不在 Manifest 中声明 intent-filter（仅内部启动，exported=false）。
 */
class GraceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏可见（延续悔改页的可见性设置，避免锁屏时恩典页无法显示）
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

        setContentView(R.layout.activity_grace)

        // 读取当前得胜天数
        val streak = StreakStore.getStreak(this)
        val tvStreakInfo = findViewById<TextView>(R.id.tv_streak_info)
        tvStreakInfo.text = if (streak > 0) {
            "今天是得胜的第 $streak 天"
        } else {
            "今天重新开始得胜"
        }

        // 关闭按钮：恢复金句通知 + 回到原 App
        findViewById<Button>(R.id.btn_close).setOnClickListener {
            // 恩典页关闭 = 悔改流程结束，恢复金句常驻通知
            MonitoringService.restoreNotification(this)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
