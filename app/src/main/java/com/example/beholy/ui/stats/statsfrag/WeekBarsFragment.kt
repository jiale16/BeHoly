package com.example.beholy.ui.stats.statsfrag

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.beholy.R
import java.text.SimpleDateFormat
import java.util.*

/**
 * 单周柱状图页：显示某周（周一~周日）7 天的命中次数
 */
class WeekBarsFragment : Fragment() {

    private var weekStartStr: String = ""
    private var dateToCount: Map<String, Int> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        weekStartStr = arguments?.getString(ARG_WEEK_START) ?: ""
        @Suppress("UNCHECKED_CAST")
        dateToCount = (arguments?.getSerializable(ARG_MAP) as? HashMap<String, Int>) ?: emptyMap()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_week_bars, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderWeek(view)
    }

    private fun renderWeek(view: View) {
        val content = view.findViewById<LinearLayout>(R.id.weekBarsContent)
        val tvMax = view.findViewById<TextView>(R.id.tvWeekMax)
        content.removeAllViews()

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val monday = sdf.parse(weekStartStr) ?: Date()

        val dayLabels = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val counts = IntArray(7)
        val dates = Array(7) { "" }
        val cal = Calendar.getInstance().apply {
            setFirstDayOfWeek(Calendar.MONDAY)
            time = monday
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        for (i in 0..6) {
            val d = Calendar.getInstance().apply {
                setFirstDayOfWeek(Calendar.MONDAY)
                time = cal.time
                add(Calendar.DAY_OF_WEEK, i)
            }
            val ds = sdf.format(d.time)
            dates[i] = ds
            counts[i] = dateToCount[ds] ?: 0
        }

        val maxCount = counts.maxOrNull() ?: 0
        val maxHeight = 150
        for (i in 0..6) {
            val h = if (maxCount == 0) 4 else {
                (maxHeight.toFloat() * counts[i] / maxCount).toInt().coerceAtLeast(4)
            }
            content.addView(buildColumn(dates[i], dayLabels[i], counts[i], h))
        }

        tvMax.text = if (maxCount == 0) "本周暂无命中" else "本周最高 $maxCount 次"
    }

    private fun buildColumn(dateStr: String, weekday: String, count: Int, height: Int): View {
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(4, 0, 4, 6)
        }

        val tvCount = TextView(requireContext()).apply {
            text = if (count == 0) "" else count.toString()
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val bar = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(36, height)
            setBackgroundColor(Color.parseColor("#27AE60"))
        }

        val tvDate = TextView(requireContext()).apply {
            text = "${dateStr.substring(5)}\n$weekday"
            textSize = 9f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        column.addView(tvCount)
        column.addView(bar)
        column.addView(tvDate)
        return column
    }

    companion object {
        private const val ARG_WEEK_START = "weekStart"
        private const val ARG_MAP = "map"

        fun newInstance(weekStartStr: String, dateToCount: Map<String, Int>): WeekBarsFragment {
            val f = WeekBarsFragment()
            val b = Bundle().apply {
                putString(ARG_WEEK_START, weekStartStr)
                putSerializable(ARG_MAP, HashMap(dateToCount))
            }
            f.arguments = b
            return f
        }
    }
}
