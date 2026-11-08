package com.vayunmathur.screentime.platform

import com.vayunmathur.screentime.data.WindDownSchedule
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindDownScheduleTest {

    // 2026-11-09 is a Monday.
    private fun at(day: Int, month: Int, hour: Int, minute: Int = 0): LocalDateTime =
        LocalDateTime.of(2026, month, day, hour, minute)

    private fun overnight(daysMask: Int) = WindDownSchedule(
        enabled = true,
        startMinute = 22 * 60,
        endMinute = 6 * 60,
        daysMask = daysMask,
    )

    @Test
    fun overnightEveningBelongsToTodaysBit() {
        val schedule = overnight(daysMask = 1 shl 0)
        assertTrue(schedule.activeAt(at(day = 9, month = 11, hour = 23)))
    }

    @Test
    fun overnightTailBelongsToYesterdaysBit() {
        // "Mon 22:00-06:00" must still apply at 02:00 on Tuesday.
        val schedule = overnight(daysMask = 1 shl 0)
        assertTrue(schedule.activeAt(at(day = 10, month = 11, hour = 2)))
    }

    @Test
    fun overnightTailEndsAtEndMinute() {
        val schedule = overnight(daysMask = 1 shl 0)
        assertFalse(schedule.activeAt(at(day = 10, month = 11, hour = 7)))
    }

    @Test
    fun overnightTailAbsentWhenYesterdayUnset() {
        // Only Tuesday set: Monday 02:00 is not covered because Sunday is unset.
        val schedule = overnight(daysMask = 1 shl 1)
        assertFalse(schedule.activeAt(at(day = 9, month = 11, hour = 2)))
        assertTrue(schedule.activeAt(at(day = 10, month = 11, hour = 23)))
    }

    @Test
    fun middayOutsideWindowIsInactive() {
        val schedule = overnight(daysMask = WindDownSchedule.ALL_DAYS)
        assertFalse(schedule.activeAt(at(day = 9, month = 11, hour = 12)))
    }

    @Test
    fun sameDayWindowUsesTodaysBitOnly() {
        val schedule = WindDownSchedule(
            enabled = true,
            startMinute = 9 * 60,
            endMinute = 17 * 60,
            daysMask = 1 shl 0,
        )
        assertTrue(schedule.activeAt(at(day = 9, month = 11, hour = 12)))
        assertFalse(schedule.activeAt(at(day = 9, month = 11, hour = 8)))
        assertFalse(schedule.activeAt(at(day = 10, month = 11, hour = 12)))
    }

    @Test
    fun disabledScheduleIsNeverActive() {
        val schedule = overnight(daysMask = WindDownSchedule.ALL_DAYS).copy(enabled = false)
        assertFalse(schedule.activeAt(at(day = 9, month = 11, hour = 23)))
        assertFalse(schedule.activeAt(at(day = 10, month = 11, hour = 2)))
    }

    // Boundary + defaults coverage from cov/c9 (same-day month pinned to November).

    @Test fun disabledNeverActive() {
        val s = WindDownSchedule(enabled = false, startMinute = 22 * 60, endMinute = 7 * 60)
        assertFalse(s.activeAt(at(day = 2, month = 11, hour = 23)))
        assertFalse(s.activeAt(at(day = 3, month = 11, hour = 2)))
    }

    @Test fun overnightAllDaysBoundaries() {
        val s = WindDownSchedule(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60)
        assertFalse(s.activeAt(at(day = 2, month = 11, hour = 21, minute = 59)))
        assertTrue(s.activeAt(at(day = 2, month = 11, hour = 22, minute = 0)))
        assertTrue(s.activeAt(at(day = 2, month = 11, hour = 23, minute = 59)))
        assertTrue(s.activeAt(at(day = 3, month = 11, hour = 0, minute = 0)))
        assertTrue(s.activeAt(at(day = 3, month = 11, hour = 6, minute = 59)))
        assertFalse(s.activeAt(at(day = 3, month = 11, hour = 7, minute = 0)))
        assertFalse(s.activeAt(at(day = 3, month = 11, hour = 12, minute = 0)))
    }

    @Test fun sameDayWindowBoundaries() {
        val s = WindDownSchedule(enabled = true, startMinute = 9 * 60, endMinute = 17 * 60)
        assertFalse(s.activeAt(at(day = 2, month = 11, hour = 8, minute = 59)))
        assertTrue(s.activeAt(at(day = 2, month = 11, hour = 9, minute = 0)))
        assertTrue(s.activeAt(at(day = 2, month = 11, hour = 16, minute = 59)))
        assertFalse(s.activeAt(at(day = 2, month = 11, hour = 17, minute = 0)))
    }

    @Test fun dayMaskWeekdaysOnly() {
        // Monday..Friday = bits 0..4; 2026-11-06/07/08 are Fri/Sat/Sun.
        val s = WindDownSchedule(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60, daysMask = 0b001_1111)
        assertTrue(s.activeAt(at(day = 6, month = 11, hour = 23))) // Friday 23:00
        assertTrue(s.activeAt(at(day = 7, month = 11, hour = 2))) // Saturday 02:00 (started Friday)
        assertFalse(s.activeAt(at(day = 7, month = 11, hour = 23))) // Saturday 23:00 not set
        assertFalse(s.activeAt(at(day = 8, month = 11, hour = 2))) // Sunday 02:00 (started Saturday, not set)
    }

    @Test fun defaultsAreOvernight() {
        val s = WindDownSchedule(enabled = true)
        kotlin.test.assertEquals(22 * 60, s.startMinute)
        kotlin.test.assertEquals(7 * 60, s.endMinute)
        kotlin.test.assertEquals(WindDownSchedule.ALL_DAYS, s.daysMask)
        assertTrue(s.activeAt(at(day = 2, month = 11, hour = 23)))
        assertFalse(s.activeAt(at(day = 2, month = 11, hour = 12)))
    }
}
