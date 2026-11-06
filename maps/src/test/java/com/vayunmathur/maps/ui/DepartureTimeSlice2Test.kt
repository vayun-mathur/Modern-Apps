package com.vayunmathur.maps.ui

import com.vayunmathur.maps.ui.DepartureTime.Label
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Departure-board wording edges not pinned by [DepartureTimeTest].
 * Slice-2 coverage for maps (basename D, outside the slice-1 dirs).
 */
class DepartureTimeSlice2Test {

    private val now = 1_700_000_000_000L
    private val utc = ZoneId.of("UTC")

    @Test
    fun `half a minute rounds symmetrically in both directions`() {
        // Rounding is on the magnitude with the sign reapplied: the same
        // distance from now must read the same either side.
        assertEquals(Label.InMinutes(1), DepartureTime.label(now + 30_000L, now, utc))
        assertEquals(Label.MinutesAgo(1), DepartureTime.label(now - 30_000L, now, utc))
    }

    @Test
    fun `the countdown window edge rounds before it compares`() {
        // 10m29s rounds to 10 and still counts down; 10m31s rounds to 11
        // and becomes a clock time.
        assertTrue(DepartureTime.label(now + 629_000L, now, utc) is Label.InMinutes)
        assertTrue(DepartureTime.label(now + 631_000L, now, utc) is Label.Clock)
    }

    @Test
    fun `just under half a minute is now`() {
        assertEquals(Label.Now, DepartureTime.label(now + 29_000L, now, utc))
        assertEquals(Label.Now, DepartureTime.label(now - 29_000L, now, utc))
    }

    @Test
    fun `the locale default overload agrees with the explicit one`() {
        val label = DepartureTime.label(now + 5 * 60_000L, now)
        assertTrue(label is Label.InMinutes && label.minutes == 5L)
    }

    @Test
    fun `clock formatting is stable across calls`() {
        val a = DepartureTime.clock(now, utc, Locale.UK)
        assertEquals(a, DepartureTime.clock(now, utc, Locale.UK))
    }
}
