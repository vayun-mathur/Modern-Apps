package com.vayunmathur.screentime.ui

import com.vayunmathur.screentime.platform.UsagePeriod
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Duration and range formatting: header, axis, minutes, week starts. */
class UsageFormatTest {

    @Test fun formatStat_tiers() {
        assertEquals("00s", formatStat(0))
        assertEquals("45s", formatStat(45_000))
        assertEquals("07m46s", formatStat(466_000))
        assertEquals("01h00m", formatStat(3_600_000))
        assertEquals("23h29m", formatStat(84_540_000))
    }

    @Test fun formatAxis_compact() {
        assertEquals("0h", formatAxis(0))
        assertEquals("0h", formatAxis(-5))
        assertEquals("49m", formatAxis(49 * 60_000L))
        assertEquals("3h", formatAxis(3 * 3_600_000L))
        assertEquals("3h45m", formatAxis(3 * 3_600_000L + 45 * 60_000L))
    }

    @Test fun formatMinutes_values() {
        assertEquals("0 min", formatMinutes(0))
        assertEquals("45 min", formatMinutes(45))
        assertEquals("1 h", formatMinutes(60))
        assertEquals("1 h 30 min", formatMinutes(90))
        assertEquals("2 h", formatMinutes(120))
    }

    @Test fun weekStart_isMonday() {
        // 2026-11-06 is a Friday; its Monday is 2026-11-02.
        assertEquals(LocalDate.of(2026, 11, 2), weekStart(LocalDate.of(2026, 11, 6)))
        assertEquals(LocalDate.of(2026, 11, 2), weekStart(LocalDate.of(2026, 11, 2)))
        assertEquals(LocalDate.of(2026, 11, 2), weekStart(LocalDate.of(2026, 11, 8)))
    }

    @Test fun rangeLabel_dayAndWeek() {
        val anchor = LocalDate.of(2026, 11, 6)
        assertTrue(rangeLabel(UsagePeriod.Day, anchor).contains("2026"))
        val week = rangeLabel(UsagePeriod.Week, anchor)
        assertTrue(week.contains("Nov 2") && week.contains("Nov 8"), week)
    }
}
