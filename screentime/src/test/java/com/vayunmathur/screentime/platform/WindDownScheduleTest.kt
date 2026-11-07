package com.vayunmathur.screentime.platform

import com.vayunmathur.screentime.data.WindDownSchedule
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wind-down windows: same-day, overnight, day masks, disabled. */
class WindDownScheduleTest {

    private fun at(day: Int, hour: Int, minute: Int = 0): LocalDateTime =
        LocalDateTime.of(2026, 11, day, hour, minute) // 2026-11-02 is a Monday

    @Test fun disabled_neverActive() {
        val s = WindDownSchedule(enabled = false, startMinute = 22 * 60, endMinute = 7 * 60)
        assertFalse(s.activeAt(at(2, 23)))
        assertFalse(s.activeAt(at(3, 2)))
    }

    @Test fun overnight_allDays() {
        val s = WindDownSchedule(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60)
        assertFalse(s.activeAt(at(2, 21, 59)))
        assertTrue(s.activeAt(at(2, 22, 0)))
        assertTrue(s.activeAt(at(2, 23, 59)))
        assertTrue(s.activeAt(at(3, 0, 0)))
        assertTrue(s.activeAt(at(3, 6, 59)))
        assertFalse(s.activeAt(at(3, 7, 0)))
        assertFalse(s.activeAt(at(3, 12, 0)))
    }

    @Test fun sameDay_window() {
        val s = WindDownSchedule(enabled = true, startMinute = 9 * 60, endMinute = 17 * 60)
        assertFalse(s.activeAt(at(2, 8, 59)))
        assertTrue(s.activeAt(at(2, 9, 0)))
        assertTrue(s.activeAt(at(2, 16, 59)))
        assertFalse(s.activeAt(at(2, 17, 0)))
    }

    @Test fun dayMask_weekdaysOnly() {
        // Monday..Friday = bits 0..4
        val s = WindDownSchedule(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60, daysMask = 0b001_1111)
        assertTrue(s.activeAt(at(6, 23))) // Friday 23:00
        assertTrue(s.activeAt(at(7, 2))) // Saturday 02:00 (started Friday)
        assertFalse(s.activeAt(at(7, 23))) // Saturday 23:00 not set
        assertFalse(s.activeAt(at(8, 2))) // Sunday 02:00 (started Saturday, not set)
    }

    @Test fun defaults_areOvernight() {
        val s = WindDownSchedule(enabled = true)
        assertEqualsDefaults(s)
        assertTrue(s.activeAt(at(2, 23)))
        assertFalse(s.activeAt(at(2, 12)))
    }

    private fun assertEqualsDefaults(s: WindDownSchedule) {
        kotlin.test.assertEquals(22 * 60, s.startMinute)
        kotlin.test.assertEquals(7 * 60, s.endMinute)
        kotlin.test.assertEquals(WindDownSchedule.ALL_DAYS, s.daysMask)
    }
}
