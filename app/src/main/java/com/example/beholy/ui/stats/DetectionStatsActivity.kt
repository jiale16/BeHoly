package com.example.beholy.ui.stats

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.beholy.R
import com.example.beholy.data.db.AppDatabase
import com.example.beholy.data.db.DateCount
import com.example.beholy.data.db.HitRecordEntity
import com.example.beholy.databinding.ActivityDetectionStatsBinding
import com.example.beholy.ui.stats.statsfrag.DailyStatsFragment
import com.example.beholy.ui.stats.statsfrag.MonthlyStatsFragment
import com.example.beholy.ui.stats.statsfrag.WeeklyStatsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * 检测命中统计页面：按天/周/月查看命中次数柱状图 + 列表
 *
 * 数据源：Room 数据库 hit_records 表（旧版从 HitLogger 文本文件解析，已迁移）。
 * 聚合按天在 DB 层用 SQLite strftime 完成；按周/月在内存中基于按天结果二次聚合。
 */
class DetectionStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetectionStatsBinding
    private lateinit var statsAdapter: StatsPagerAdapter

    /** 命中列表行的展示格式（行首必须为 `yyyy-MM-dd HH:mm:ss`，DailyStatsFragment 按行首日期过滤当周命中） */
    private val displayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 用于按天分组计算「第N次」的日期格式 */
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

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
        // 关闭外层横向滑动：避免与「按天」内部按周翻页的横向滑动冲突，
        // 日/周/月 Tab 仍可通过顶部按钮点击切换。
        binding.viewPager.isUserInputEnabled = false

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

    /**
     * 从 Room 数据库加载命中记录并聚合统计。
     * 按天聚合在 DB 层完成（SQLite strftime），按周/月在内存中基于按天结果二次聚合。
     */
    private fun loadAllStats() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@DetectionStatsActivity).hitDao()

            // DB 层按天聚合
            val dailyRaw = withContext(Dispatchers.IO) { dao.aggregateByDay() }

            if (dailyRaw.isEmpty()) {
                statsAdapter.updateData(
                    dailyStat = emptyList(),
                    weeklyStat = emptyList(),
                    monthlyStat = emptyList(),
                    allHits = emptyList()
                )
                return@launch
            }

            // 按天 -> DetectionStatItem
            val dailyStat = dailyRaw.map {
                DetectionStatItem(
                    date = it.date,
                    count = it.count,
                    weekStart = null,
                    month = it.date.substring(0, 7)
                )
            }.sortedBy { it.date }

            // 按周/月在内存中二次聚合（避免复杂 SQL，与原实现一致）
            val weeklyStat = aggregateByWeek(dailyRaw)
            val monthlyStat = aggregateByMonth(dailyRaw)

            // 全量命中记录（按时间倒序），格式化为可读行供列表展示
            val allEntities = withContext(Dispatchers.IO) { dao.queryAll() }
            val allHits = buildHitDisplayList(allEntities)

            statsAdapter.updateData(
                dailyStat = dailyStat,
                weeklyStat = weeklyStat,
                monthlyStat = monthlyStat,
                allHits = allHits
            )
        }
    }

    /**
     * 按周聚合（ISO周）：每周一开始的一周内命中次数。
     * 基于按天聚合结果在内存中二次聚合，避免在 SQL 中处理 ISO 周复杂度。
     * date 为该周周一日期；weekStart 与 date 相同；month 为该周周一所在月份。
     */
    private fun aggregateByWeek(dailyCounts: List<DateCount>): List<DetectionStatItem> {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val calendar = Calendar.getInstance().apply { setFirstDayOfWeek(Calendar.MONDAY) }
        val weeklyMap = mutableMapOf<String, Int>()

        for (dc in dailyCounts) {
            calendar.time = sdf.parse(dc.date)!!
            calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val weekKey = sdf.format(calendar.time)
            weeklyMap[weekKey] = weeklyMap.getOrDefault(weekKey, 0) + dc.count
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
    private fun aggregateByMonth(dailyCounts: List<DateCount>): List<DetectionStatItem> {
        val monthlyMap = mutableMapOf<String, Int>()
        for (dc in dailyCounts) {
            val month = dc.date.substring(0, 7)
            monthlyMap[month] = monthlyMap.getOrDefault(month, 0) + dc.count
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
     * 为全量命中记录按「同一天」分组（跨所有应用），按时间升序计算当天第几次命中，
     * 再倒序返回展示字符串。
     *
     * 悔改次数以当天总命中计，而非按应用分别计数。
     */
    private fun buildHitDisplayList(entities: List<HitRecordEntity>): List<String> {
        val counts = mutableMapOf<String, Int>()
        return entities
            .sortedBy { it.timestamp } // 升序，保证同天按时间递增编号
            .map { entity ->
                val date = dateFmt.format(Date(entity.timestamp))
                val count = counts.getOrDefault(date, 0) + 1
                counts[date] = count
                formatHit(entity, count)
            }
            .reversed() // 恢复时间倒序展示
    }

    /**
     * 将单条命中记录格式化为可读字符串（供统计页命中列表展示）。
     * 行首必须为 `yyyy-MM-dd HH:mm:ss`，DailyStatsFragment 依赖此格式按行首日期过滤当周命中。
     *
     * @param displayHitCount 由 [buildHitDisplayList] 动态计算的当天同包累计次数（从1开始）
     */
    private fun formatHit(entity: HitRecordEntity, displayHitCount: Int): String {
        val time = displayFmt.format(Date(entity.timestamp))
        val isMigrated = entity.packageName == "(已迁移)" || entity.tier == 0
        val pkgDisplay = if (entity.packageName.isBlank() || entity.packageName == "(已迁移)") {
            ""
        } else {
            " 包=${entity.packageName}"
        }
        return if (isMigrated) {
            "$time | 检测命中 | 历史记录$pkgDisplay"
        } else {
            "$time | 检测命中 | T${entity.tier}$pkgDisplay (第${displayHitCount}次)"
        }
    }
}
