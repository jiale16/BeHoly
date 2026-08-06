package com.example.beholy.ui.stats.statsfrag

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * 按天视图内部的「按周翻页」适配器：每一页是一周的 7 天柱状图
 */
class WeekPagerAdapter(
    fragment: Fragment,
    private val weeks: List<String>,
    private val dateToCount: Map<String, Int>
) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = weeks.size

    override fun createFragment(position: Int): Fragment =
        WeekBarsFragment.newInstance(weeks[position], dateToCount)
}
