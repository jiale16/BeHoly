package com.example.beholy.ui.stats

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.example.beholy.R
import com.example.beholy.util.HitLogger
import com.example.beholy.databinding.ActivityDetectionStatsBinding
import com.example.beholy.ui.stats.statsfrag.DailyStatsFragment
import com.example.beholy.ui.stats.statsfrag.MonthlyStatsFragment
import com.example.beholy.ui.stats.statsfrag.WeeklyStatsFragment
import java.text.SimpleDateFormat
import java.util.*

/**
 * 检测命中统计页面：按天/周/月查看命中次数柱状图 + 列表
 */
class DetectionStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetectionStatsBinding
    private lateinit var statsAdapter: StatsPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetectionStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 返回按钮
        binding.btnBack.setOnClickListener { finish() }

        // Tab 切换
        setupTabs()

        // 初始化 ViewPager2 和适配器
        statsAdapter = StatsPagerAdapter(this)
        binding.viewPager.adapter = statsAdapter
        binding.viewPager.offscreenPageLimit = 3

        // 加载数据
        loadAllStats()
    }

    private fun setupTabs() {
        val dailyTab = binding.tabDaily
        val weeklyTab = binding.tabWeekly
        val monthlyTab = binding.tabMonthly

        // 默认选中第一天（蓝色）
        dailyTab.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
        dailyTab.setTextColor(android.graphics.Color.WHITE)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // 重置所有按钮样式
                dailyTab.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                dailyTab.setTextColor(android.graphics.Color.BLACK)
                weeklyTab.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                weeklyTab.setTextColor(android.graphics.Color.BLACK)
                monthlyTab.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                monthlyTab.setTextColor(android.graphics.Color.BLACK)

                // 高亮当前 Tab
                when (position) {
                    0 -> {
                        dailyTab.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
                        dailyTab.setTextColor(android.graphics.Color.WHITE)
                    }
                    1 -> {
                        weeklyTab.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
                        weeklyTab.setTextColor(android.graphics.Color.WHITE)
                    }
                    2 -> {
                        monthlyTab.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
                        monthlyTab.setTextColor(android.graphics.Color.WHITE)
                    }
                }
            }
        })

        // 点击 Tab 切换
        dailyTab.setOnClickListener { binding.viewPager.currentItem = 0 }
        weeklyTab.setOnClickListener { binding.viewPager.currentItem = 1 }
        monthlyTab.setOnClickListener { binding.viewPager.currentItem = 2 }
    }

    private fun loadAllStats() {
        val hitsContent = HitLogger.readLatestFirst(this)
        if (hitsContent.isBlank()) {
            statsAdapter.updateData(
                dailyStat = emptyList(),
                weeklyStat = emptyList(),
                monthlyStat = emptyList(),
                allHits = emptyList()
            )
            return
        }

        // 解析所有命中记录的时间戳
        val hitDates = parseHitDates(hitsContent)

        // 聚合统计
        val dailyStat = aggregateByDay(hitDates)
        val weeklyStat = aggregateByWeek(hitDates)
        val monthlyStat = aggregateByMonth(hitDates)

        // 更新适配器
        statsAdapter.updateData(
            dailyStat = dailyStat,
            weeklyStat = weeklyStat,
            monthlyStat = monthlyStat,
            allHits = parseAllHits(hitsContent)
        )
    }

    /**
     * 从 HitLogger 文本中解析出每个命中的日期
     */
    private fun parseHitDates(content: String): List<String> {
        val dates = mutableListOf<String>()
        val lines = content.trim().lineSequence()
        for (line in lines) {
            if (line.contains("| 检测命中")) {
                val datePart = line.substringBefore(" ").trim()
                if (datePart.isNotEmpty()) dates.add(datePart)
            }
        }
        return dates
    }

    /**
     * 按天聚合：每天命中次数
     */
    private fun aggregateByDay(dates: List<String>): List<DetectionStatItem> {
        return dates.groupingBy { it }.eachCount()
            .map { (date, count) ->
                DetectionStatItem(
                    date = date,
                    count = count,
                    weekStart = null,
                    month = date.substring(0, 7)
                )
            }.sortedBy { it.date }
    }

    /**
     * 按周聚合（ISO周）：每周一开始的一周内命中次数
     */
    private fun aggregateByWeek(dates: List<String>): List<DetectionStatItem> {
        val calendar = Calendar.getInstance()
        val weeklyMap = mutableMapOf<String, Int>()

        for (dateStr in dates) {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            calendar.setTime(sdf.parse(dateStr)!!)
            calendar.setFirstDayOfWeek(Calendar.MONDAY)
            val year = calendar.get(Calendar.YEAR)

            // 计算该周的周一日期
            val monday = calendar.clone() as Calendar
            monday.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val weekKey = sdf.format(monday.time)

            weeklyMap[weekKey] = weeklyMap.getOrDefault(weekKey, 0) + 1
        }

        return weeklyMap.map { (date, count) ->
            DetectionStatItem(
                date = date,
                count = count,
                weekStart = date,
                month = date.substring(0, 7)
            )
        }.sortedBy { it.date }
    }

    /**
     * 按月聚合：每月命中次数
     */
    private fun aggregateByMonth(dates: List<String>): List<DetectionStatItem> {
        val monthlyMap = mutableMapOf<String, Int>()
        for (dateStr in dates) {
            val month = dateStr.substring(0, 7)
            monthlyMap[month] = monthlyMap.getOrDefault(month, 0) + 1
        }

        return monthlyMap.map { (month, count) ->
            DetectionStatItem(
                date = month,
                count = count,
                weekStart = null,
                month = month
            )
        }.sortedBy { it.date }
    }

    /**
     * 解析所有命中记录的完整详情（用于列表展示）
     */
    private fun parseAllHits(content: String): List<String> {
        val hits = mutableListOf<String>()
        val lines = content.trim().lineSequence()
        for (line in lines) {
            if (line.contains("| 检测命中")) {
                hits.add(line.trim())
            }
        }
        return hits
    }
}