package org.readera.openreadera.data.stats

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReadingStatsManagerTest {
    @Test fun inactiveStreakExpiresWithoutErasingItsHistoricalBest() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("openreadera_stats", Context.MODE_PRIVATE)
        preferences.edit().clear().putInt("current_streak", 5).putInt("best_streak", 8)
            .putString("last_reading_date", "1970-01-01").commit()
        val summary = ReadingStatsManager(context).getStatsSummary()
        assertEquals(0, summary.currentStreakDays)
        assertEquals(8, summary.bestStreakDays)
    }
}
