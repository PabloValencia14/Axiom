package org.readera.openreadera.data.stats

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.*

data class StatsSummary(
    val currentStreakDays: Int,
    val bestStreakDays: Int,
    val todayMinutes: Int,
    val totalHours: Float,
    val totalPagesRead: Int,
    val completedBooks: Int,
    val averageWpm: Int,
    val weeklyMinutes: List<Pair<String, Int>> // e.g. ("Lun", 35), ("Mar", 45)...
)

class ReadingStatsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("openreadera_stats", Context.MODE_PRIVATE)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dayNameFormat = SimpleDateFormat("EEE", Locale("es", "ES"))

    @Synchronized
    fun recordReadingTime(elapsedMs: Long, pagesTurned: Int = 0) {
        if (elapsedMs <= 0) return
        val todayStr = dateFormat.format(Date())

        // 1. Update total minutes & today's minutes
        val elapsedSeconds = elapsedMs / 1000
        val currentTodaySecs = prefs.getLong("day_sec_$todayStr", 0L) + elapsedSeconds
        prefs.edit().putLong("day_sec_$todayStr", currentTodaySecs).apply()

        val totalSecs = prefs.getLong("total_seconds", 0L) + elapsedSeconds
        prefs.edit().putLong("total_seconds", totalSecs).apply()

        // 2. Update pages turned
        if (pagesTurned > 0) {
            val totalPages = prefs.getInt("total_pages_read", 0) + pagesTurned
            prefs.edit().putInt("total_pages_read", totalPages).apply()
        }

        // 3. Update streaks
        updateStreak(todayStr)
    }

    @Synchronized
    fun recordBookCompleted() {
        val count = prefs.getInt("completed_books", 0) + 1
        prefs.edit().putInt("completed_books", count).apply()
    }

    private fun updateStreak(todayStr: String) {
        val lastDay = prefs.getString("last_reading_date", null)
        var streak = prefs.getInt("current_streak", 0)
        var bestStreak = prefs.getInt("best_streak", 0)

        if (lastDay == null) {
            streak = 1
        } else if (lastDay != todayStr) {
            try {
                val yesterday = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, -1)
                }
                val yesterdayStr = dateFormat.format(yesterday.time)
                streak = if (lastDay == yesterdayStr) streak + 1 else 1
            } catch (_: Exception) {
                streak = 1
            }
        }

        if (streak > bestStreak) {
            bestStreak = streak
            prefs.edit().putInt("best_streak", bestStreak).apply()
        }

        prefs.edit()
            .putInt("current_streak", streak)
            .putString("last_reading_date", todayStr)
            .apply()
    }

    fun getStatsSummary(): StatsSummary {
        val todayStr = dateFormat.format(Date())
        val todaySecs = prefs.getLong("day_sec_$todayStr", 0L)
        val todayMinutes = (todaySecs / 60).toInt()

        val totalSecs = prefs.getLong("total_seconds", 0L)
        val totalHours = totalSecs / 3600f

        val currentStreak = prefs.getInt("current_streak", 0)
        val bestStreak = prefs.getInt("best_streak", 0)
        val totalPagesRead = prefs.getInt("total_pages_read", 0)
        val completedBooks = prefs.getInt("completed_books", 0)

        // Weekly breakdown (last 7 days)
        val weekly = mutableListOf<Pair<String, Int>>()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)

        for (i in 0 until 7) {
            val dStr = dateFormat.format(cal.time)
            val dName = dayNameFormat.format(cal.time).replace(".", "").replaceFirstChar { it.uppercase() }
            val secs = prefs.getLong("day_sec_$dStr", 0L)
            weekly.add(dName to (secs / 60).toInt())
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        return StatsSummary(
            currentStreakDays = currentStreak,
            bestStreakDays = maxOf(currentStreak, bestStreak),
            todayMinutes = todayMinutes,
            totalHours = totalHours,
            totalPagesRead = totalPagesRead,
            completedBooks = completedBooks,
            // The reader records pages and time, not words. Returning a
            // fabricated WPM is worse than showing that this metric is not
            // available yet.
            averageWpm = 0,
            weeklyMinutes = weekly
        )
    }
}
