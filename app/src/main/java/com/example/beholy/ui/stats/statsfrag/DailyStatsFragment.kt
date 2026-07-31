package com.example.beholy.ui.stats.statsfrag

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.beholy.R
import com.example.beholy.ui.stats.DetectionStatItem

/**
 * 按天统计 Fragment：展示每日命中次数柱状图 + 命中列表
 */
class DailyStatsFragment(
    private val statList: List<DetectionStatItem> = emptyList(),
    private val hits: List<String> = emptyList()
) : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_daily_stats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderChart(view)
        renderList(view)
    }

    private fun renderChart(view: View) {
        val layoutChart = view.findViewById<LinearLayout>(R.id.layoutChart)
        val layoutChartContent = view.findViewById<LinearLayout>(R.id.layoutChartContent)
        val tvChartHint = view.findViewById<TextView>(R.id.tvChartHint)
        val chartMax = view.findViewById<TextView>(R.id.tvChartMax)

        if (statList.isEmpty()) {
            layoutChart.visibility = View.GONE
            tvChartHint.visibility = View.VISIBLE
            return
        }

        layoutChart.visibility = View.VISIBLE
        tvChartHint.visibility = View.GONE

        val maxCount = statList.maxOfOrNull { it.count } ?: 1
        val maxHeight = 160 // dp

        layoutChartContent.removeAllViews()

        for (item in statList) {
            val barHeight = (maxHeight.toFloat() * item.count / maxCount).toInt()
            val barView = createBarView(item.date, item.count, barHeight, maxCount)
            layoutChartContent.addView(barView)
        }

        chartMax.text = "最高 $maxCount 次"
    }

    private fun createBarView(date: String, count: Int, height: Int, max: Int): View {
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(8, 0, 8, 8)
        }

        val tvCount = TextView(requireContext()).apply {
            text = count.toString()
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
        }

        val bar = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(40, height)
            setBackgroundColor(android.graphics.Color.parseColor("#27AE60"))
            elevation = 2f
        }

        val tvDate = TextView(requireContext()).apply {
            text = date.substring(5)
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#999999"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL)
        }

        column.addView(tvCount)
        column.addView(bar)
        column.addView(tvDate)

        return column
    }

    private fun renderList(view: View) {
        val layoutList = view.findViewById<LinearLayout>(R.id.layoutList)
        val tvHitsContent = view.findViewById<TextView>(R.id.tvHitsContent)
        val tvEmptyHint = view.findViewById<TextView>(R.id.tvEmptyHint)

        if (hits.isEmpty()) {
            tvEmptyHint.visibility = View.VISIBLE
            layoutList.visibility = View.GONE
            return
        }

        layoutList.visibility = View.VISIBLE
        tvEmptyHint.visibility = View.GONE

        val allHitsText = hits.joinToString("\n\n") { it }
        tvHitsContent.text = allHitsText
    }
}