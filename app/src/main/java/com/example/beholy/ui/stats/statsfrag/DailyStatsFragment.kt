package com.example.beholy.ui.stats.statsfrag

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.example.beholy.R
import com.example.beholy.ui.stats.DetectionStatItem
import java.text.SimpleDateFormat
import java.util.*

/**
 * 按天统计 Fragment：以「周」为单位翻页，默认显示当前周 7 天，左右滑切换周
 */
class DailyStatsFragment(
    private val statList: List<DetectionStatItem> = emptyList(),
    private val hits: List<String> = emptyList()
) : Fragment() {

    private lateinit var weekPager: ViewPager2
    private lateinit var tvWeekRange: TextView
    private lateinit var layoutList: LinearLayout
    private lateinit var tvHitsContent: TextView
    private lateinit var tvEmptyHint: TextView
    private lateinit var tvChartHint: TextView

    private var weeks: List<String> = emptyList()
    private var dateToCount: Map<String, Int> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_daily_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvChartHint = view.findViewById(R.id.tvChartHint)
        tvWeekRange = view.findViewById(R.id.tvWeekRange)
        weekPager = view.findViewById(R.id.weekPager)
        layoutList = view.findViewById(R.id.layoutList)
        tvHitsContent = view.findViewById(R.id.tvHitsContent)
        tvEmptyHint = view.findViewById(R.id.tvEmptyHint)

        if (statList.isEmpty()) {
            tvChartHint.visibility = View.VISIBLE
            tvWeekRange.visibility = View.GONE
            weekPager.visibility = View.GONE
            layoutList.visibility = View.GONE
            tvEmptyHint.visibility = View.GONE
            return
        }

        dateToCount = statList.associate { it.date to it.count }
        weeks = buildWeeks()
        val defaultIndex = (weeks.size - 1).coerceAtLeast(0)

        weekPager.adapter = WeekPagerAdapter(this, weeks, dateToCount)
        weekPager.offscreenPageLimit = 1
        weekPager.setCurrentItem(defaultIndex, false)
        updateWeekInfo(defaultIndex)

        weekPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateWeekInfo(position)
            }
        })
    }

    /**
     * 生成从最早数据所在周 到 本周（含）的所有周一日期列表
     */
    private fun buildWeeks(): List<String> {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance().apply { setFirstDayOfWeek(Calendar.MONDAY) }
        val dates = statList.map { it.date }.sorted()
        if (dates.isEmpty()) return emptyList()

        val earliest = sdf.parse(dates.first())!!
        val today = sdf.parse(sdf.format(Date()))!!

        cal.time = earliest
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val firstMonday = sdf.format(cal.time)

        cal.time = today
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val currentMonday = sdf.format(cal.time)

        val list = mutableListOf<String>()
        var cur = sdf.parse(firstMonday)!!
        val end = sdf.parse(currentMonday)!!
        while (!cur.after(end)) {
            list.add(sdf.format(cur))
            cal.time = cur
            cal.add(Calendar.DAY_OF_MONTH, 7)
            cur = cal.time
        }
        return list
    }

    /**
     * 更新顶部周区间文案 + 底部命中列表（按当前周过滤）
     */
    private fun updateWeekInfo(position: Int) {
        val mondayStr = weeks.getOrNull(position) ?: return
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val rangeFmt = SimpleDateFormat("MM/dd", Locale.getDefault())
        val cal = Calendar.getInstance().apply {
            setFirstDayOfWeek(Calendar.MONDAY)
            time = sdf.parse(mondayStr)!!
        }
        val start = cal.time
        cal.add(Calendar.DAY_OF_MONTH, 6)
        val end = cal.time

        val todayStr = sdf.format(Date())
        val containsToday = mondayStr <= todayStr && sdf.format(end) >= todayStr
        val suffix = if (containsToday) "（本周）" else ""
        tvWeekRange.text = "${rangeFmt.format(start)} - ${rangeFmt.format(end)} $suffix"

        val weekDates = (0..6).map { off ->
            val c = Calendar.getInstance().apply {
                setFirstDayOfWeek(Calendar.MONDAY)
                time = sdf.parse(mondayStr)!!
                add(Calendar.DAY_OF_MONTH, off)
            }
            sdf.format(c.time)
        }.toSet()

        val weekHits = hits.filter { line ->
            weekDates.contains(line.substringBefore(" ").trim())
        }

        if (weekHits.isEmpty()) {
            layoutList.visibility = View.GONE
            tvEmptyHint.visibility = View.VISIBLE
        } else {
            layoutList.visibility = View.VISIBLE
            tvEmptyHint.visibility = View.GONE
            tvHitsContent.text = weekHits.joinToString("\n\n")
        }
    }
}
