package com.example.beholy.ui.stats.statsfrag

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * 通用柱状图翻页适配器：每一页是一个 BarsPageFragment（一组柱状条）。
 * 用于「按周」（每屏一个月的周聚合）与「按月」（每屏一年的月聚合）视图。
 */
class BarsPagerAdapter(
    fragment: Fragment,
    private val pages: List<BarsPageData>
) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = pages.size

    override fun createFragment(position: Int): Fragment =
        BarsPageFragment.newInstance(pages[position])
}
