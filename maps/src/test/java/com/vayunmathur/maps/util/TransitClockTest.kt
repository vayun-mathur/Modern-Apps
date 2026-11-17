package com.vayunmathur.maps.util
import com.vayunmathur.maps.domain.formatServiceTime
import com.vayunmathur.maps.domain.transitClock

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The offline transit index is world-merged, so every query time must be derived
 * in the *feed's* timezone. These lock that down without a device: the whole
 * point is that the device's own zone must not leak in.
 */
class TransitClockTest {

    /** Wed 2024-01-03, 08:30 local, in whatever zone is asked for. */
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): (ZoneId) -> ZonedDateTime =
        { zone -> ZonedDateTime.of(y, m, d, h, min, 0, 0, zone) }

    @Test
    fun `derives seconds since midnight in the feed zone`() {
        val clock = transitClock("America/Los_Angeles", at(2024, 1, 3, 8, 30))
        assertEquals(8 * 3600 + 30 * 60, clock.depSecs)
    }

    @Test
    fun `maps weekday to Monday zero`() {
        // 2024-01-03 is a Wednesday, 2024-01-07 a Sunday.
        assertEquals(2, transitClock("UTC", at(2024, 1, 3, 8, 0)).weekday)
        assertEquals(6, transitClock("UTC", at(2024, 1, 7, 8, 0)).weekday)
    }

    @Test
    fun `encodes the date as yyyymmdd`() {
        assertEquals(20240103, transitClock("UTC", at(2024, 1, 3, 8, 0)).date)
    }

    @Test
    fun `previous service day wraps the month and the week`() {
        val clock = transitClock("UTC", at(2024, 3, 1, 0, 30))
        assertEquals(20240301, clock.date)
        // 2024 is a leap year, so the day before 1 March is 29 February.
        assertEquals(20240229, clock.prevDate)
        // 2024-03-01 was a Friday, so the previous day is a Thursday.
        assertEquals(4, clock.weekday)
        assertEquals(3, clock.prevWeekday)
    }

    @Test
    fun `two feeds an ocean apart get different query times for one instant`() {
        // 2024-01-03T08:30 in New York is 05:30 in Los Angeles: the same wall
        // clock reading in each zone must NOT produce the same depSecs, which is
        // exactly the bug of using the device zone against a world pack.
        val west = transitClock("America/Los_Angeles", at(2024, 1, 3, 8, 30))
        val east = transitClock("America/New_York", at(2024, 1, 3, 8, 30))
        assertEquals(west.depSecs, east.depSecs, "same wall clock in both zones")
        // ...but midnight is a different instant, so absolute times differ.
        assertNotEquals(west.midnightMillis, east.midnightMillis)
        assertEquals(3 * 3600 * 1000L, west.midnightMillis - east.midnightMillis)
    }

    @Test
    fun `midnight millis is feed-local midnight`() {
        val clock = transitClock("America/Los_Angeles", at(2024, 1, 3, 8, 30))
        val expected = ZonedDateTime.of(2024, 1, 3, 0, 0, 0, 0, ZoneId.of("America/Los_Angeles"))
            .toInstant().toEpochMilli()
        assertEquals(expected, clock.midnightMillis)
    }

    @Test
    fun `an unknown or missing zone falls back to the device zone`() {
        val fixed = at(2024, 1, 3, 8, 30)
        val device = transitClock(ZoneId.systemDefault().id, fixed)
        assertEquals(device, transitClock(null, fixed))
        assertEquals(device, transitClock("Not/AZone", fixed))
    }

    @Test
    fun `formats a service time as a wall clock`() {
        assertEquals("08:30", formatServiceTime(8 * 3600 + 30 * 60))
        assertEquals("23:59", formatServiceTime(23 * 3600 + 59 * 60))
        // A GTFS 24:30:00 trip renders as the 00:30 it actually runs at, marked as
        // the next day; a multi-day journey counts the days.
        assertEquals("00:30 +1", formatServiceTime(24 * 3600 + 30 * 60))
        assertEquals("13:15 +3", formatServiceTime(3 * 86_400 + 13 * 3600 + 15 * 60))
        assertEquals("", formatServiceTime(0))
    }
}
