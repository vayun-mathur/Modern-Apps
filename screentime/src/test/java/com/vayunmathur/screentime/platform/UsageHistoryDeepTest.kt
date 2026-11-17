package com.vayunmathur.screentime.platform

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UsageHistory against a seeded UsageStatsManager shadow: real stats and event
 * streams in, bars/per-app totals/hour buckets out. This is the system-service
 * seam Robolectric provides; the production code path is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsageHistoryDeepTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val shadow: ShadowUsageStatsManager
        get() = Shadows.shadowOf(ctx.getSystemService(UsageStatsManager::class.java))

    private fun dayStart(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun addStats(pkg: String, day: LocalDate, foregroundMs: Long) {
        val stats = ShadowUsageStatsManager.UsageStatsBuilder.newBuilder()
            .setPackageName(pkg)
            // One minute into the day, not exactly at midnight: the shadow's
            // queryUsageStats connects ranges that merely touch at an endpoint,
            // so a midnight-aligned range is also returned for the previous
            // day's query and the week loop would count it twice (which day
            // that is depends on the local timezone's current weekday).
            .setFirstTimeStamp(dayStart(day) + 60_000)
            .setLastTimeStamp(dayStart(day) + 3_600_000)
            .setTotalTimeInForeground(foregroundMs)
            .build()
        // queryAndAggregateUsageStats reads INTERVAL_BEST; seed both so the
        // shadow serves whichever interval the framework layer asks for.
        shadow.addUsageStats(UsageStatsManager.INTERVAL_DAILY, stats)
        shadow.addUsageStats(UsageStatsManager.INTERVAL_BEST, stats)
    }

    @Test fun weekAggregatesPerAppAndAveragesOverElapsedDays() {
        val today = LocalDate.now()
        addStats("com.a.app", today, 3_600_000)
        addStats("com.b.app", today, 1_800_000)

        val data = UsageHistory(ctx).aggregate(UsagePeriod.Week, today)
        assertEquals(7, data.bars.size)
        assertEquals(3_600_000L, data.perApp["com.a.app"])
        assertEquals(1_800_000L, data.perApp["com.b.app"])
        assertEquals(5_400_000L, data.totalMillis)
        // Average divides by elapsed days of this week (Mon..today), not seven.
        val elapsed = today.dayOfWeek.value
        assertEquals(5_400_000L / elapsed, data.averageMillis)
        assertEquals(5_400_000L, data.bars.sumOf { it.millis })
    }

    @Test fun weekForPackageOnlyCountsThatPackage() {
        val today = LocalDate.now()
        addStats("com.a.app", today, 3_600_000)
        addStats("com.b.app", today, 1_800_000)
        val data = UsageHistory(ctx).forPackage(UsagePeriod.Week, today, "com.b.app")
        assertEquals(1_800_000L, data.totalMillis)
        assertTrue(data.perApp.isEmpty())
    }

    @Test fun futureWeekIsAllZerosWithNoElapsedDays() {
        val future = LocalDate.now().plusWeeks(2)
        val data = UsageHistory(ctx).aggregate(UsagePeriod.Week, future)
        assertEquals(7, data.bars.size)
        assertTrue(data.bars.all { it.millis == 0L })
        assertEquals(0L, data.averageMillis)
    }

    @Test fun dayBucketsForegroundEventsIntoHours() {
        // A past day is used so the window end is the day's own end, not "now":
        // every interval below is then fully deterministic.
        val day = LocalDate.now().minusDays(1)
        val start = dayStart(day)
        // 09:10 -> 09:40 foreground for com.a.app.
        shadow.addEvent("com.a.app", start + 9 * 3_600_000 + 600_000, UsageEvents.Event.MOVE_TO_FOREGROUND)
        shadow.addEvent("com.a.app", start + 9 * 3_600_000 + 2_400_000, UsageEvents.Event.MOVE_TO_BACKGROUND)
        // A session opened at midnight counts until 00:30.
        shadow.addEvent("com.b.app", start, UsageEvents.Event.ACTIVITY_RESUMED)
        shadow.addEvent("com.b.app", start + 1_800_000, UsageEvents.Event.ACTIVITY_PAUSED)
        // Still foreground at the window edge: closed at the edge (20:00 -> 24:00).
        shadow.addEvent("com.c.app", start + 20 * 3_600_000, UsageEvents.Event.MOVE_TO_FOREGROUND)
        // Out-of-window and duplicate opens must not create phantom time.
        shadow.addEvent("com.a.app", start + 9 * 3_600_000 + 900_000, UsageEvents.Event.ACTIVITY_RESUMED)

        val data = UsageHistory(ctx).aggregate(UsagePeriod.Day, day)
        assertEquals(24, data.bars.size)
        assertEquals(1_800_000L, data.bars[9].millis, "bars=${data.bars}")
        assertEquals(1_800_000L, data.bars[0].millis)
        assertEquals(4 * 3_600_000L, data.bars.drop(20).sumOf { it.millis })
        assertEquals("12 AM", data.bars[0].label)
        assertEquals("12 PM", data.bars[12].label)
        assertEquals("1 PM", data.bars[13].label)

        val forA = UsageHistory(ctx).forPackage(UsagePeriod.Day, day, "com.a.app")
        assertEquals(1_800_000L, forA.totalMillis)
        assertEquals(1_800_000L / 24, forA.averageMillis)
    }

    @Test fun dayForYesterdayWithNoEventsIsZeroed() {
        val data = UsageHistory(ctx).aggregate(UsagePeriod.Day, LocalDate.now().minusDays(1))
        assertEquals(24, data.bars.size)
        assertTrue(data.bars.all { it.millis == 0L })
        assertEquals(0L, data.totalMillis)
    }
}
