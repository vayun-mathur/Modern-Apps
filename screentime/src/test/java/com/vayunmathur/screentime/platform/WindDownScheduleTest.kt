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
}
