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
 * 按周统计 Fragment：以「月」为单位翻页，每屏固定显示该月的所有周（一般 4~5 周），
 * 无数据的周补 0，左右滑切换月份，默认定位到最近有数据的月份；底部列表按当前月过滤。
 */
class WeeklyStatsFragment(
    private val statList: List<DetectionStatItem> = emptyList(),
    private val hits: List<String> = emptyList()
) : Fragment() {

    private lateinit var tvChartHint: TextView
    private lateinit var tvMonthTitle: TextView
    private lateinit var monthPager: ViewPager2
    private lateinit var layoutList: LinearLayout
    private lateinit var tvHitsContent: TextView
    private lateinit var tvEmptyHint: TextView

    private var months: List<String> = emptyList()      // "yyyy-MM"，按有数据的月份排序
    private var pages: List<BarsPageData> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_weekly_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvChartHint = view.findViewById(R.id.tvChartHint)
        tvMonthTitle = view.findViewById(R.id.tvMonthTitle)
        monthPager = view.findViewById(R.id.monthPager)
        layoutList = view.findViewById(R.id.layoutList)
        tvHitsContent = view.findViewById(R.id.tvHitsContent)
        tvEmptyHint = view.findViewById(R.id.tvEmptyHint)

        if (statList.isEmpty()) {
            tvChartHint.visibility = View.VISIBLE
            tvMonthTitle.visibility = View.GONE
            monthPager.visibility = View.GONE
            layoutList.visibility = View.GONE
            tvEmptyHint.visibility = View.GONE
            return
        }

        // 按周聚合数据的 month 字段（= 周一所在月 "yyyy-MM"）分组
        val byMonth = statList.groupBy { it.month }
        months = byMonth.keys.sorted()
        pages = months.map { month -> buildMonthPage(month, byMonth.getValue(month)) }

        val defaultIndex = months.size - 1

        monthPager.adapter = BarsPagerAdapter(this, pages)
        monthPager.offscreenPageLimit = 1
        monthPager.setCurrentItem(defaultIndex, false)
        updateMonthInfo(defaultIndex)

        monthPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateMonthInfo(position)
            }
        })
    }

    /**
     * 构造某月固定周页面：列出该月包含的所有周一（按周一计算），
     * 有数据用实际次数，无数据补 0。
     */
    private fun buildMonthPage(month: String, items: List<DetectionStatItem>): BarsPageData {
        val countByMonday = items.associate { it.date to it.count }

        val mondays = mondaysInMonth(month)
        val labels = mondays.map { formatWeekLabel(it) }
        val counts = mondays.map { countByMonday[it] ?: 0 }
        return BarsPageData(labels = labels, counts = counts)
    }

    /**
     * 返回某个月份（"yyyy-MM"）内按周一算的所有周起始日。
     * 从该月 1 号所在周的周一开始，直到下个月 1 号所在周的周一之前。
     */
    private fun mondaysInMonth(month: String): List<String> {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance().apply { setFirstDayOfWeek(Calendar.MONDAY) }
        cal.time = sdf.parse("$month-01")!!
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val firstMondayInGrid = sdf.format(cal.time)

        val nextMonth = (month.substring(5).toInt() % 12) + 1
        val nextYear = month.substring(0, 4).toInt() + if (nextMonth == 1) 1 else 0
        val nextMonthKey = String.format("%04d-%02d", nextYear, nextMonth)
        val endExclusive = sdf.parse("$nextMonthKey-01")!!

        val result = mutableListOf<String>()
        var cur = sdf.parse(firstMondayInGrid)!!
        while (cur.before(endExclusive)) {
            result.add(sdf.format(cur))
            cal.time = cur
            cal.add(Calendar.DAY_OF_MONTH, 7)
            cur = cal.time
        }
        return result
    }

    /**
     * 周一日期 "yyyy-MM-dd" -> 标签 "M/d"
     */
    private fun formatWeekLabel(dateStr: String): String {
        val parts = dateStr.split("-")
        return if (parts.size >= 3) "${parts[1].toInt()}/${parts[2].toInt()}" else dateStr
    }

    /**
     * 更新月份标题 + 底部命中列表（按当前月过滤）
     */
    private fun updateMonthInfo(position: Int) {
        val month = months.getOrNull(position) ?: return
        val y = month.substring(0, 4)
        val m = month.substring(5).toInt()
        tvMonthTitle.text = "${y}年${m}月"

        val monthHits = hits.filter { line ->
            line.substringBefore(" ").startsWith(month)
        }

        if (monthHits.isEmpty()) {
            layoutList.visibility = View.GONE
            tvEmptyHint.visibility = View.VISIBLE
        } else {
            layoutList.visibility = View.VISIBLE
            tvEmptyHint.visibility = View.GONE
            tvHitsContent.text = monthHits.joinToString("\n\n")
        }
    }
}
