package com.vayunmathur.parentalcontrols.domain

import com.vayunmathur.parentalcontrols.data.BedtimeSchedule
import com.vayunmathur.parentalcontrols.data.DailyLimit
import com.vayunmathur.parentalcontrols.data.DowntimeSchedule
import com.vayunmathur.parentalcontrols.data.SchoolTimeSchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeWindowTest {

    @Test
    fun disabledWindowContainsNothing() {
        val window = TimeWindow(enabled = false, startMinute = 0, endMinute = 1439)
        assertFalse(window.contains(0, 0))
        assertFalse(window.contains(3, 720))
    }

    @Test
    fun sameDayWindowBoundaries() {
        // Monday=0. 09:00 (540) to 17:00 (1020), all days.
        val window = TimeWindow(enabled = true, startMinute = 540, endMinute = 1020)
        assertFalse(window.contains(0, 539))
        assertTrue(window.contains(0, 540))
        assertTrue(window.contains(0, 1019))
        assertFalse(window.contains(0, 1020))
    }

    @Test
    fun dayMaskRestrictsDays() {
        val weekdays = TimeWindow(enabled = true, startMinute = 0, endMinute = 60, daysMask = TimeWindow.WEEKDAYS)
        assertTrue(weekdays.contains(0, 30)) // Monday
        assertTrue(weekdays.contains(4, 30)) // Friday
        assertFalse(weekdays.contains(5, 30)) // Saturday
        assertFalse(weekdays.contains(6, 30)) // Sunday
        assertTrue(weekdays.isDaySet(0))
        assertFalse(weekdays.isDaySet(6))
    }

    @Test
    fun crossMidnightWindowEveningAndMorningHalves() {
        // Friday (4) 21:00 -> 07:00.
        val fridayOnly = 1 shl 4
        val window = TimeWindow(enabled = true, startMinute = 21 * 60, endMinute = 7 * 60, daysMask = fridayOnly)
        assertTrue(window.contains(4, 21 * 60)) // Friday evening opens
        assertTrue(window.contains(4, 23 * 60 + 59))
        assertFalse(window.contains(4, 20 * 60 + 59))
        assertFalse(window.contains(4, 2 * 60)) // Friday 02:00 belongs to Thursday's window
        assertTrue(window.contains(5, 2 * 60)) // Saturday 02:00 is Friday window's morning half
        assertTrue(window.contains(5, 6 * 60 + 59))
        assertFalse(window.contains(5, 7 * 60))
        assertFalse(window.contains(5, 22 * 60)) // Saturday evening is not Friday's window
    }

    @Test
    fun constantsAndDefaults() {
        assertEquals(7, TimeWindow.DAYS_IN_WEEK)
        assertEquals(127, TimeWindow.ALL_DAYS)
        assertEquals(31, TimeWindow.WEEKDAYS)
        assertEquals(1440, TimeWindow.MINUTES_PER_DAY)
        val bedtime = BedtimeSchedule()
        assertEquals(0, bedtime.id)
        assertEquals(21 * 60, bedtime.startMinute)
        assertEquals(7 * 60, bedtime.endMinute)
        assertFalse(bedtime.enabled)
        assertEquals(12 * 60, DowntimeSchedule().startMinute)
        assertEquals(14 * 60, DowntimeSchedule().endMinute)
        val school = SchoolTimeSchedule()
        assertEquals(8 * 60, school.startMinute)
        assertEquals(15 * 60, school.endMinute)
        assertEquals(TimeWindow.WEEKDAYS, school.daysMask)
        assertEquals(null, DailyLimit().dailyLimitMinutes)
    }

    @Test
    fun schedulesDelegateToWindowSemantics() {
        val bedtime = BedtimeSchedule(enabled = true) // 21:00-07:00 all days
        assertTrue(bedtime.contains(0, 22 * 60))
        assertTrue(bedtime.contains(1, 3 * 60)) // morning half of Monday's window
        assertFalse(bedtime.contains(0, 12 * 60))
        assertTrue(bedtime.isDaySet(6))
        val downtime = DowntimeSchedule(enabled = true) // 12:00-14:00
        assertTrue(downtime.contains(2, 13 * 60))
        assertFalse(downtime.contains(2, 14 * 60))
        val school = SchoolTimeSchedule(enabled = true) // weekdays 08:00-15:00
        assertTrue(school.contains(0, 9 * 60))
        assertFalse(school.contains(5, 9 * 60)) // Saturday
        // Disabled schedules contain nothing.
        assertFalse(BedtimeSchedule().contains(0, 22 * 60))
        assertFalse(DowntimeSchedule().contains(0, 13 * 60))
        assertFalse(SchoolTimeSchedule().contains(0, 9 * 60))
    }
}
