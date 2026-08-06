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

/**
 * 按月统计 Fragment：以「年」为单位翻页，每屏显示某年 12 个月的命中次数柱状图，
 * 左右滑切换年份，默认定位到最近有数据的年份；底部列表按当前年过滤。
 */
class MonthlyStatsFragment(
    private val statList: List<DetectionStatItem> = emptyList(),
    private val hits: List<String> = emptyList()
) : Fragment() {

    private lateinit var tvChartHint: TextView
    private lateinit var tvYearTitle: TextView
    private lateinit var yearPager: ViewPager2
    private lateinit var layoutList: LinearLayout
    private lateinit var tvHitsContent: TextView
    private lateinit var tvEmptyHint: TextView

    private var years: List<String> = emptyList()       // "yyyy"，按有数据的年份排序
    private var pages: List<BarsPageData> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_monthly_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvChartHint = view.findViewById(R.id.tvChartHint)
        tvYearTitle = view.findViewById(R.id.tvYearTitle)
        yearPager = view.findViewById(R.id.yearPager)
        layoutList = view.findViewById(R.id.layoutList)
        tvHitsContent = view.findViewById(R.id.tvHitsContent)
        tvEmptyHint = view.findViewById(R.id.tvEmptyHint)

        if (statList.isEmpty()) {
            tvChartHint.visibility = View.VISIBLE
            tvYearTitle.visibility = View.GONE
            yearPager.visibility = View.GONE
            layoutList.visibility = View.GONE
            tvEmptyHint.visibility = View.GONE
            return
        }

        // 按月聚合数据的 date 字段 = "yyyy-MM"，按年份分组
        val byYear = statList.groupBy { it.date.substring(0, 4) }
        years = byYear.keys.sorted()
        pages = years.map { year ->
            val countsByMonth = byYear.getValue(year).associate { it.date to it.count }
            val labels = (1..12).map { "${it}月" }
            val counts = (1..12).map { m ->
                val key = "$year-${String.format("%02d", m)}"
                countsByMonth[key] ?: 0
            }
            BarsPageData(labels = labels, counts = counts)
        }

        val defaultIndex = years.size - 1

        yearPager.adapter = BarsPagerAdapter(this, pages)
        yearPager.offscreenPageLimit = 1
        yearPager.setCurrentItem(defaultIndex, false)
        updateYearInfo(defaultIndex)

        yearPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateYearInfo(position)
            }
        })
    }

    /**
     * 更新年份标题 + 底部命中列表（按当前年过滤）
     */
    private fun updateYearInfo(position: Int) {
        val year = years.getOrNull(position) ?: return
        tvYearTitle.text = "${year}年"

        val yearHits = hits.filter { line ->
            line.substringBefore(" ").startsWith(year)
        }

        if (yearHits.isEmpty()) {
            layoutList.visibility = View.GONE
            tvEmptyHint.visibility = View.VISIBLE
        } else {
            layoutList.visibility = View.VISIBLE
            tvEmptyHint.visibility = View.GONE
            tvHitsContent.text = yearHits.joinToString("\n\n")
        }
    }
}
