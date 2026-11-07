package com.vayunmathur.maps.ui

import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The departure-time label edges the board tests cannot all reach: the
 * default clock entry point, symmetric rounding, and the scroll index at
 * its extremes.
 * Slice-2 coverage for maps (basename D, outside the slice-1 dirs).
 */
class DepartureTimeEdgesSlice2Test {

    private val now = 1_700_000_000_000L
    private val utc: ZoneId = ZoneId.of("UTC")

    @Test fun `the default clock entry point formats like the explicit one`() {
        assertEquals(
            DepartureTime.clock(now, ZoneId.systemDefault(), Locale.getDefault()),
            DepartureTime.clock(now),
        )
        assertTrue(DepartureTime.clock(now, utc, Locale.US).isNotBlank())
    }

    @Test fun `labels switch from countdown to clock past ten minutes`() {
        assertEquals(
            DepartureTime.Label.InMinutes(10),
            DepartureTime.label(now + 10 * 60_000L, now, utc, Locale.US),
        )
        assertTrue(
            DepartureTime.label(now + 11 * 60_000L, now, utc, Locale.US) is DepartureTime.Label.Clock,
        )
        assertEquals(
            DepartureTime.Label.MinutesAgo(10),
            DepartureTime.label(now - 10 * 60_000L, now, utc, Locale.US),
        )
        assertTrue(
            DepartureTime.label(now - 11 * 60_000L, now, utc, Locale.US) is DepartureTime.Label.Clock,
        )
    }

    @Test fun `ninety seconds rounds the same in both directions`() {
        assertEquals(
            DepartureTime.Label.InMinutes(2),
            DepartureTime.label(now + 90_000L, now, utc, Locale.US),
        )
        assertEquals(
            DepartureTime.Label.MinutesAgo(2),
            DepartureTime.label(now - 90_000L, now, utc, Locale.US),
        )
        assertEquals(
            DepartureTime.Label.Now,
            DepartureTime.label(now + 20_000L, now, utc, Locale.US),
        )
        assertEquals(
            DepartureTime.Label.Now,
            DepartureTime.label(now - 20_000L, now, utc, Locale.US),
        )
    }

    @Test fun `the window is a day either side inclusive`() {
        val dayMs = 24L * 60 * 60_000L
        assertTrue(DepartureTime.withinWindow(now + dayMs, now))
        assertTrue(DepartureTime.withinWindow(now - dayMs, now))
        assertFalse(DepartureTime.withinWindow(now + dayMs + 1, now))
        assertFalse(DepartureTime.withinWindow(now - dayMs - 1, now))
    }

    @Test fun `first upcoming at the extremes`() {
        assertEquals(0, DepartureTime.firstUpcoming(emptyList(), now))
        assertEquals(0, DepartureTime.firstUpcoming(listOf(now + 1, now + 2), now))
        // Everything gone: the index is the size, scrolling to the end.
        assertEquals(2, DepartureTime.firstUpcoming(listOf(now - 2, now - 1), now))
        assertEquals(1, DepartureTime.firstUpcoming(listOf(now - 1, now + 1), now))
        // A departure exactly now counts as upcoming.
        assertEquals(0, DepartureTime.firstUpcoming(listOf(now), now))
    }
}
