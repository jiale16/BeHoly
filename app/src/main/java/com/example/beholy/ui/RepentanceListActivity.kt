package com.example.beholy.ui

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.beholy.R
import com.example.beholy.data.RepentanceRecord
import com.example.beholy.databinding.ActivityRepentanceListBinding
import com.example.beholy.databinding.ItemRepentanceCardBinding
import com.example.beholy.util.RepentanceStore
import com.example.beholy.util.StreakStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回转记录查看页（替代原「悔改日记」纯文本弹窗）。
 *
 * 设计要点：
 * - 整页（非弹窗）：顶部三张概览卡（回转次数 / 连续守护天数 / 最近回转），
 *   下方按时间倒序的悔改卡片列表，空状态有专门设计。
 * - 零额外依赖：未引入 Material / RecyclerView / CardView，
 *   卡片用 ScrollView + 动态 inflate 圆角背景实现（工程刻意保持零依赖、离线）。
 * - 命名：用户可见文案为「回转记录」（避免「日记」暗示每日记录）；
 *   底层数据类/文件名仍沿用 Repentance* 标识符，不改。
 * - 概览第三卡用 StreakStore.getStreak（连续守护天数），语义稳；
 *   最近回转用 RepentanceStore.formatSinceLast(now)。
 * - 点击卡片弹 Dialog 看完整反思/避免方案，列表只显示摘要。
 * - 不新增任何权限、不联网（遵循离线铁律）。
 */
class RepentanceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRepentanceListBinding
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRepentanceListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        binding.btnBack.setOnClickListener { finish() }
        // 页面底部小版本号
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("")
        binding.tvVersion.text = "BeHoly v$versionName"
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                RepentanceStore.readAll(this@RepentanceListActivity)
            }
            val total = records.size
            val streak = StreakStore.getStreak(this@RepentanceListActivity)
            val recent = withContext(Dispatchers.IO) {
                if (records.isEmpty()) "—"
                else RepentanceStore.formatSinceLast(
                    this@RepentanceListActivity, System.currentTimeMillis()
                )
            }
            withContext(Dispatchers.Main) {
                binding.tvOverviewTotal.text = total.toString()
                binding.tvOverviewStreak.text = streak.toString()
                binding.tvOverviewRecent.text = recent
                renderCards(records)
            }
        }
    }

    private fun renderCards(records: List<RepentanceRecord>) {
        binding.cardContainer.removeAllViews()
        if (records.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            return
        }
        binding.emptyState.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        records.forEach { rec ->
            val card = ItemRepentanceCardBinding.inflate(inflater, binding.cardContainer, false)
            // 日期时间（右上角类型徽标已移除）
            card.tvDate.text = dateFmt.format(Date(rec.createdAt))

            // 心情 chips
            fillChips(card.llMoodChips, rec.mood)
            card.llMood.visibility =
                if (rec.mood.isNotEmpty()) View.VISIBLE else View.GONE
            // 途径 chips
            fillChips(card.llMethodChips, rec.method)
            card.llMethod.visibility =
                if (rec.method.isNotEmpty()) View.VISIBLE else View.GONE

            // 反思摘要（脱敏不必要，反思为用户自写）
            card.tvReflection.text = rec.reflection.ifBlank { "（未填写反思）" }
            card.tvReflection.visibility =
                if (rec.reflection.isNotBlank()) View.VISIBLE else View.GONE
            // 以后避免摘要
            card.tvAvoidance.text =
                "${getString(R.string.repentance_label_avoidance)}：${rec.avoidancePlan}"
            card.tvAvoidance.visibility =
                if (rec.avoidancePlan.isNotBlank()) View.VISIBLE else View.GONE

            // 点击卡片看全文
            card.root.setOnClickListener { showDetail(rec) }

            binding.cardContainer.addView(card.root)
        }
    }

    /** 动态填充一个水平 chip 容器（圆角背景 TextView，无 RecyclerView/CardView 依赖）。 */
    private fun fillChips(container: LinearLayout, items: List<String>) {
        container.removeAllViews()
        val padH = (8 * resources.displayMetrics.density).toInt()
        val padV = (3 * resources.displayMetrics.density).toInt()
        items.forEach { text ->
            val chip = TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(resources.getColor(R.color.beholy_chip_text, null))
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(padH, padV, padH, padV)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, (6 * resources.displayMetrics.density).toInt(), 0)
                layoutParams = lp
            }
            container.addView(chip)
        }
    }

    /**
     * 点击卡片弹 Dialog 显示完整反思 / 心语 / 说明 / 以后避免方案。
     * 注：类型徽标已在卡片列表移除（用户要求不显示），详情亦不单列类型行。
     */
    private fun showDetail(rec: RepentanceRecord) {
        val lines = mutableListOf<String>()
        if (rec.mood.isNotEmpty())
            lines.add("${getString(R.string.repentance_label_mood)}：${rec.mood.joinToString("、")}")
        if (rec.moodNote.isNotBlank())
            lines.add("${getString(R.string.repentance_label_mood_note)}：${rec.moodNote}")
        if (rec.method.isNotEmpty())
            lines.add("${getString(R.string.repentance_label_method)}：${rec.method.joinToString("、")}")
        if (rec.methodNote.isNotBlank())
            lines.add("${getString(R.string.repentance_label_method_note)}：${rec.methodNote}")
        if (rec.reflection.isNotBlank())
            lines.add("${getString(R.string.repentance_label_reflection)}：${rec.reflection}")
        if (rec.avoidancePlan.isNotBlank())
            lines.add("${getString(R.string.repentance_label_avoidance)}：${rec.avoidancePlan}")

        val tv = TextView(this).apply {
            text = lines.joinToString("\n\n")
            textSize = 15f
            setTextColor(resources.getColor(R.color.beholy_text_primary, null))
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            movementMethod = ScrollingMovementMethod()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.repentance_detail_title)
            .setView(tv)
            .setPositiveButton(R.string.repentance_btn_close, null)
            .show()
    }
}
