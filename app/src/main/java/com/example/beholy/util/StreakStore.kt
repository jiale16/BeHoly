package com.example.beholy.util

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 连胜（守护天数）存储。
 *
 * 语义：用户「连续受守护」的天数。
 * - 无障碍服务开启（[recordActiveToday]）：把今天记为受守护的一天；
 *   若昨天已记则 +1，否则（隔天/首次）记为 1。
 * - 无障碍服务关闭（[breakStreak]）：连胜清零（用户主动撤去守望）。
 *
 * 跨进程安全：无障碍服务与 MainActivity 同进程，SharedPreferences 默认即够用。
 */
object StreakStore {

    private const val PREFS = "beholy_streak"
    private const val KEY_STREAK = "streak"
    private const val KEY_LAST_DATE = "last_date"

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun todayStr(): String = dateFmt.format(Date())

    private fun yesterdayStr(): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return dateFmt.format(cal.time)
    }

    /** 记录今天为受守护的一天（幂等：同天多次调用只计一次）。 */
    fun recordActiveToday(context: Context) {
        val p = prefs(context)
        val last = p.getString(KEY_LAST_DATE, "") ?: ""
        val today = todayStr()
        if (last == today) return
        val streak = p.getInt(KEY_STREAK, 0)
        val next = if (last == yesterdayStr()) streak + 1 else 1
        p.edit().putInt(KEY_STREAK, next).putString(KEY_LAST_DATE, today).apply()
    }

    /** 连胜中断：用户关闭守护，连胜清零。 */
    fun breakStreak(context: Context) {
        prefs(context).edit().putInt(KEY_STREAK, 0).putString(KEY_LAST_DATE, "").apply()
    }

    /** 当前连胜天数。 */
    fun getStreak(context: Context): Int = prefs(context).getInt(KEY_STREAK, 0)
}
