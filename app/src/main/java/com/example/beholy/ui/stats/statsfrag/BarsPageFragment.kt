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

/**
 * 单屏柱状图页数据：labels 为 X 轴标签（与 counts 一一对应）
 */
data class BarsPageData(
    val labels: List<String>,
    val counts: List<Int>
)

/**
 * 通用柱状图页：显示一组（标签, 次数）柱状条，供「按周/按月」翻页视图的每一屏复用。
 */
class BarsPageFragment : Fragment() {

    private var labels: List<String> = emptyList()
    private var counts: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labels = arguments?.getStringArrayList(ARG_LABELS) ?: emptyList()
        counts = arguments?.getIntegerArrayList(ARG_COUNTS) ?: emptyList()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bars_page, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderBars(view)
    }

    private fun renderBars(view: View) {
        val content = view.findViewById<LinearLayout>(R.id.barsContent)
        val tvMax = view.findViewById<TextView>(R.id.tvBarsMax)
        content.removeAllViews()

        val maxCount = counts.maxOrNull() ?: 0
        val maxHeight = 150
        for (i in labels.indices) {
            val h = if (maxCount == 0) 4 else {
                (maxHeight.toFloat() * counts[i] / maxCount).toInt().coerceAtLeast(4)
            }
            content.addView(buildColumn(labels[i], counts[i], h))
        }

        tvMax.text = if (maxCount == 0) "暂无命中" else "最高 $maxCount 次"
    }

    private fun buildColumn(label: String, count: Int, height: Int): View {
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(2, 0, 2, 6)
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
            )
            setBackgroundColor(Color.parseColor("#27AE60"))
        }

        val tvLabel = TextView(requireContext()).apply {
            text = label
            textSize = 9f
            maxLines = 1
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        column.addView(tvCount)
        column.addView(bar)
        column.addView(tvLabel)
        return column
    }

    companion object {
        private const val ARG_LABELS = "labels"
        private const val ARG_COUNTS = "counts"

        fun newInstance(page: BarsPageData): BarsPageFragment {
            val f = BarsPageFragment()
            f.arguments = Bundle().apply {
                putStringArrayList(ARG_LABELS, ArrayList(page.labels))
                putIntegerArrayList(ARG_COUNTS, ArrayList(page.counts))
            }
            return f
        }
    }
}
