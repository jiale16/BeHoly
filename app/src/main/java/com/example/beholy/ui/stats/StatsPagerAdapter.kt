package com.example.beholy.ui.stats

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.beholy.ui.stats.statsfrag.DailyStatsFragment
import com.example.beholy.ui.stats.statsfrag.WeeklyStatsFragment
import com.example.beholy.ui.stats.statsfrag.MonthlyStatsFragment

/**
 * ViewPager2 适配器：管理日/周/月三个统计 Fragment
 */
class StatsPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private var dailyStat = emptyList<DetectionStatItem>()
    private var weeklyStat = emptyList<DetectionStatItem>()
    private var monthlyStat = emptyList<DetectionStatItem>()
    private var allHits = emptyList<String>()

    fun updateData(
        dailyStat: List<DetectionStatItem>,
        weeklyStat: List<DetectionStatItem>,
        monthlyStat: List<DetectionStatItem>,
        allHits: List<String>
    ) {
        this.dailyStat = dailyStat
        this.weeklyStat = weeklyStat
        this.monthlyStat = monthlyStat
        this.allHits = allHits
    }

    override fun getItemCount(): Int = 3 // 日/周/月三个 Tab

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> DailyStatsFragment(dailyStat, allHits)
            1 -> WeeklyStatsFragment(weeklyStat, allHits)
            else -> MonthlyStatsFragment(monthlyStat, allHits)
        }
    }

}
