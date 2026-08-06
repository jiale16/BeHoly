package com.example.beholy.ui.stats

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.beholy.ui.stats.statsfrag.DailyStatsFragment
import com.example.beholy.ui.stats.statsfrag.MonthlyStatsFragment
import com.example.beholy.ui.stats.statsfrag.WeeklyStatsFragment

/**
 * ViewPager2 适配器：管理日/周/月三个统计 Fragment
 *
 * 数据加载是异步的（从 Room 查询），而 FragmentStateAdapter 在 onCreate 后就会预创建 Fragment。
 * 因此 [updateData] 必须调用 [notifyDataSetChanged] 触发重建，
 * 且 [getItemId] / [containsItem] 配合数据版本号让 Adapter 识别数据变化、丢弃旧 Fragment 实例。
 */
class StatsPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private var dailyStat = emptyList<DetectionStatItem>()
    private var weeklyStat = emptyList<DetectionStatItem>()
    private var monthlyStat = emptyList<DetectionStatItem>()
    private var allHits = emptyList<String>()

    /** 数据版本号：每次 updateData 自增，用于 getItemId 区分新旧数据集 */
    private var dataVersion: Long = 0L

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
        dataVersion++
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = 3 // 日/周/月三个 Tab

    /**
     * 用 (position, dataVersion) 组合作为稳定 ID。
     * dataVersion 变化时，旧 ID 不再存在 → FragmentStateAdapter 会销毁旧 Fragment 并重建。
     */
    override fun getItemId(position: Int): Long = dataVersion * 10L + position

    override fun containsItem(itemId: Long): Boolean {
        val vid = itemId / 10L
        val pos = (itemId % 10L).toInt()
        return vid == dataVersion && pos in 0 until 3
    }

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> DailyStatsFragment(dailyStat, allHits)
            1 -> WeeklyStatsFragment(weeklyStat, allHits)
            else -> MonthlyStatsFragment(monthlyStat, allHits)
        }
    }
}
