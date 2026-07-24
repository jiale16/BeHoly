package com.example.beholy.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.beholy.R
import com.example.beholy.data.Constants
import com.example.beholy.data.RepentanceRecord
import com.example.beholy.util.RepentanceStore
import com.example.beholy.service.MonitoringService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 悔改反思表单界面。
 *
 * 由 [RepentanceActivity] 在用户「我愿意悔改归向神」后启动，
 * 引导其填写本次浏览命中的结构化反思，保存到本地 JSONL 文件。
 *
 * 关键行为（对应锁定决策）：
 * - 锁屏可见（决策8）：setShowWhenLocked + setTurnScreenOn（API≥O_MR1），低版本用 WindowManager flag。
 * - 不拦截返回键（决策3）：按返回 = 视为跳过，直接 finish()，不保存。
 * - 跳过按钮（决策2）：直接 finish()，不写任何记录。
 * - 保存（决策5/7）：写入后直接 finish()，回到被监控的原 App。
 * - 双入口（决策7）：onNewIntent 也调用 handleIntent，避免 singleTop 复用丢 extra。
 *
 * 多选实现说明：工程未依赖 Material 库（build.gradle.kts 无 com.google.android.material），
 * 因此不使用 ChipGroup/Chip，改用动态生成的 CheckBox 列表实现心情/方法的多选，保证可编译。
 */
class RepentanceFormActivity : AppCompatActivity() {

    // 暂存从 RepentanceActivity 透传下来的命中信息（决策7：双入口统一暂存）
    private var pendingReason: String = ""
    private var pendingHitTime: Long = 0L

    private lateinit var tvSinceLast: TextView
    private lateinit var tvHitInfo: TextView
    private lateinit var layoutMood: LinearLayout
    private lateinit var layoutMethod: LinearLayout
    private lateinit var etMoodNote: EditText
    private lateinit var etMethodNote: EditText
    private lateinit var etReflection: EditText
    private lateinit var etAvoidance: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 决策8：锁屏下也能填写，延续 RepentanceActivity 的可见性设置
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

        setContentView(R.layout.activity_repentance_form)

        tvSinceLast = findViewById(R.id.tv_since_last)
        tvHitInfo = findViewById(R.id.tv_hit_info)
        layoutMood = findViewById(R.id.layout_mood)
        layoutMethod = findViewById(R.id.layout_method)
        etMoodNote = findViewById(R.id.et_mood_note)
        etMethodNote = findViewById(R.id.et_method_note)
        etReflection = findViewById(R.id.et_reflection)
        etAvoidance = findViewById(R.id.et_avoidance)

        // 决策7：onCreate 与 onNewIntent 共用 handleIntent 暂存 extra
        handleIntent(intent)

        // 动态生成心情 / 看的方法 的多选 CheckBox（工程未依赖 Material，以 CheckBox 列表代替 ChipGroup）
        buildCheckboxes(layoutMood, R.array.mood_options)
        buildCheckboxes(layoutMethod, R.array.method_options)

        // 顶部「距上次反思」：Store 内部切到 IO 读取并计算，回到主线程更新 UI
        lifecycleScope.launch {
            val s = RepentanceStore.formatSinceLast(this@RepentanceFormActivity, pendingHitTime)
            tvSinceLast.text = getString(R.string.form_since_last_prefix) + s
        }

        // 顶部「本次」信息：类型 + 命中时间
        val timeText = formatHitTime(pendingHitTime)
        tvHitInfo.text = getString(R.string.form_hit_info_format, pendingReason, timeText)

        // 保存 / 跳过
        findViewById<Button>(R.id.btn_save).setOnClickListener { onSave() }
        // 决策2：跳过不保存，直接关闭
        findViewById<Button>(R.id.btn_skip).setOnClickListener {
            // 跳过悔改流程也结束，恢复金句通知
            MonitoringService.restoreNotification(this)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent?) {
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

    /**
     * 动态向容器添加 CheckBox（选项来自 strings.xml 的字符串数组）。
     * 因工程未引入 Material 库，无法使用 Chip，故以 CheckBox 列表实现多选，保持可编译。
     */
    private fun buildCheckboxes(container: LinearLayout, arrayRes: Int) {
        val options = resources.getStringArray(arrayRes)
        for (opt in options) {
            val cb = CheckBox(this).apply {
                text = opt
                textSize = 15f
                setTextColor(getColor(android.R.color.white))
                isChecked = false
            }
            container.addView(cb)
        }
    }

    /** 收集某容器内所有被选中的 CheckBox 文本 */
    private fun collectChecked(container: LinearLayout): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is CheckBox && child.isChecked) {
                child.text?.toString()?.let { result.add(it) }
            }
        }
        return result
    }

    /**
     * 保存按钮：收集字段 -> 构造记录 -> 写入本地 JSONL -> finish。
     * 决策5/7：保存即交托——记录落地后直接关闭表单，交还给被监控的原 App，不强制回 BeHoly 主页。
     * 写入在 Store 内部切到 IO 线程，返回主线程后再 finish，确保数据已落盘。
     */
    private fun onSave() {
        val record = RepentanceRecord(
            hitTime = pendingHitTime,
            reason = pendingReason,
            mood = collectChecked(layoutMood),
            moodNote = etMoodNote.text.toString().trim(),
            method = collectChecked(layoutMethod),
            methodNote = etMethodNote.text.toString().trim(),
            reflection = etReflection.text.toString().trim(),
            avoidancePlan = etAvoidance.text.toString().trim()
        )
        lifecycleScope.launch {
            RepentanceStore.save(this@RepentanceFormActivity, record)
            // 保存成功后跳转恩典页，给用户一个「蒙赦免、重新起步」的安慰时刻
            val graceIntent = Intent(this@RepentanceFormActivity, GraceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            runCatching { startActivity(graceIntent) }
            // 恩典页已启动（或启动失败安全降级），关闭表单
            finish()
        }
    }

    /**
     * 决策3：不拦截返回键。用户按返回 = 视为跳过，不保存，直接 finish()。
     * 因此这里【不】override onBackPressed 来拦截，沿用系统默认行为（默认即 finish()）。
     */

    /** 命中时间格式化：MM-dd HH:mm；time<=0 时返回占位符 */
    private fun formatHitTime(time: Long): String {
        if (time <= 0L) return "—"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }
}
