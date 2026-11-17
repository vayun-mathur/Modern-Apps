package com.vayunmathur.health.util
import com.vayunmathur.health.platform.HealthViewModel

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression test for #727: the week view collapsed sparse data left, so a
 * Tuesday-start week labeled Tuesday's bar as Sunday. [HealthViewModel] now
 * fills all 7 slots; this pins the fill behavior (null gaps, correct days).
 */
class WeekBucketsTest {

    // 2026-09-13 is a Sunday; the issue's tracking started Tuesday 2026-09-15.
    private val weekStart = LocalDate(2026, 9, 13)
    private val tuesday = LocalDate(2026, 9, 15)
    private val wednesday = LocalDate(2026, 9, 16)

    private fun byEpoch(vararg pairs: Pair<LocalDate, Double>): Map<Long, Pair<Double?, Double?>> =
        pairs.associate { (date, value) ->
            date.toEpochDays().toLong() to (value to value)
        }

    @Test
    fun sparseWeekKeepsEachDayInItsOwnSlot() {
        val series = HealthViewModel.fillWeekSeries(
            weekStart,
            byEpoch(tuesday to 5000.0, wednesday to 8000.0),
        )
        assertEquals(7, series.size)
        // Sun/Mon empty, Tue/Wed hold the values, Thu..Sat empty.
        assertNull(series[0].second)
        assertNull(series[1].second)
        assertEquals(tuesday, series[2].first)
        assertEquals(5000.0, series[2].second)
        assertEquals(wednesday, series[3].first)
        assertEquals(8000.0, series[3].second)
        assertNull(series[4].second)
        assertNull(series[5].second)
        assertNull(series[6].second)
    }

    @Test
    fun fullWeekPreservesOrderAndValues() {
        val values = (0..6).associate { offset ->
            val date = LocalDate(2026, 9, 13 + offset)
            date.toEpochDays().toLong() to ((1000.0 + offset) to (1000.0 + offset))
        }
        val series = HealthViewModel.fillWeekSeries(weekStart, values)
        assertEquals(7, series.size)
        series.forEachIndexed { index, (date, value, _) ->
            assertEquals(LocalDate(2026, 9, 13 + index), date)
            assertEquals(1000.0 + index, value)
        }
    }

    @Test
    fun emptyWeekYieldsSevenNullSlots() {
        val series = HealthViewModel.fillWeekSeries(weekStart, emptyMap())
        assertEquals(7, series.size)
        series.forEachIndexed { index, (date, value, secondary) ->
            assertEquals(LocalDate(2026, 9, 13 + index), date)
            assertNull(value)
            assertNull(secondary)
        }
    }
}
