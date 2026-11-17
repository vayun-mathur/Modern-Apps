package com.vayunmathur.maps.ui
import com.vayunmathur.maps.data.nearest

import com.vayunmathur.maps.ui.DepartureTime.Label
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules the departure board reads by. */
class DepartureTimeTest {

    private val now = 1_700_000_000_000L
    private val utc = ZoneId.of("UTC")
    private val uk = Locale.UK

    private fun at(minutes: Long) = now + minutes * 60_000L

    private fun label(minutes: Long) = DepartureTime.label(at(minutes), now, utc, uk)

    @Test
    fun `a departure inside ten minutes counts down`() {
        assertEquals(Label.InMinutes(1), label(1))
        assertEquals(Label.InMinutes(5), label(5))
        assertEquals(Label.InMinutes(10), label(10))
    }

    @Test
    fun `a departure inside the last ten minutes counts up`() {
        assertEquals(Label.MinutesAgo(1), label(-1))
        assertEquals(Label.MinutesAgo(9), label(-9))
        assertEquals(Label.MinutesAgo(10), label(-10))
    }

    /** "in 0 minutes" is not something a board should ever say. */
    @Test
    fun `a departure right now says so`() {
        assertEquals(Label.Now, label(0))
        assertEquals(Label.Now, DepartureTime.label(now + 20_000L, now, utc, uk))
        assertEquals(Label.Now, DepartureTime.label(now - 20_000L, now, utc, uk))
    }

    /**
     * Rounded, not truncated: at 90 seconds "in 2 minutes" is nearer the truth, and a board that
     * reads "1 minute" for two full minutes looks broken.
     */
    @Test
    fun `partial minutes round to the nearest`() {
        assertEquals(Label.InMinutes(2), DepartureTime.label(now + 90_000L, now, utc, uk))
        assertEquals(Label.InMinutes(1), DepartureTime.label(now + 80_000L, now, utc, uk))
        assertEquals(Label.MinutesAgo(2), DepartureTime.label(now - 90_000L, now, utc, uk))
    }

    @Test
    fun `past ten minutes it becomes a clock time`() {
        val soon = label(10)
        assertTrue("ten minutes still counts down", soon is Label.InMinutes)
        val later = label(11)
        assertTrue("eleven does not", later is Label.Clock)
        assertTrue(label(-11) is Label.Clock)
    }

    @Test
    fun `a clock time is the locale's own`() {
        // now == 2023-11-14T22:13:20Z
        assertEquals("22:13", DepartureTime.clock(now, utc, Locale.UK))
        assertTrue(
            "a 12-hour locale gets a 12-hour time",
            DepartureTime.clock(now, utc, Locale.US).contains("PM"),
        )
    }

    @Test
    fun `the board spans a day either side and no further`() {
        assertTrue(DepartureTime.withinWindow(at(60 * 24), now))
        assertTrue(DepartureTime.withinWindow(at(-60 * 24), now))
        assertFalse(DepartureTime.withinWindow(at(60 * 24 + 1), now))
        assertFalse(DepartureTime.withinWindow(at(-60 * 24 - 1), now))
    }

    @Test
    fun `the board opens on the next departure that has not gone`() {
        val times = listOf(at(-30), at(-10), at(-1), at(2), at(20))
        assertEquals(3, DepartureTime.firstUpcoming(times, now))
    }

    /** Every train gone: land at the end, where the most recent one is. */
    @Test
    fun `an all-past board opens at the end`() {
        val times = listOf(at(-30), at(-10), at(-1))
        assertEquals(3, DepartureTime.firstUpcoming(times, now))
    }

    @Test
    fun `an all-future board opens at the top`() {
        val times = listOf(at(1), at(10), at(30))
        assertEquals(0, DepartureTime.firstUpcoming(times, now))
    }

    @Test
    fun `an empty board does not blow up`() {
        assertEquals(0, DepartureTime.firstUpcoming(emptyList(), now))
    }

    /** A departure exactly now has not gone, so it is the one to scroll to. */
    @Test
    fun `a departure at this exact moment counts as upcoming`() {
        val times = listOf(at(-10), now, at(10))
        assertEquals(1, DepartureTime.firstUpcoming(times, now))
    }
}
