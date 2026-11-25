package com.vayunmathur.library.format

import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeAgeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun unknownAndFuture() {
        assertEquals(RelativeAge.Unknown, relativeAge(0L, now))
        assertEquals(RelativeAge.Unknown, relativeAge(-5L, now))
        assertEquals(RelativeAge.Future, relativeAge(now + 1L, now))
    }

    @Test
    fun bucketBoundaries() {
        assertEquals(RelativeAge.JustNow, relativeAge(now, now))
        assertEquals(RelativeAge.JustNow, relativeAge(now - 59_999L, now))
        assertEquals(RelativeAge.Minutes(1), relativeAge(now - 60_000L, now))
        assertEquals(RelativeAge.Minutes(59), relativeAge(now - 3_599_999L, now))
        assertEquals(RelativeAge.Hours(1), relativeAge(now - 3_600_000L, now))
        assertEquals(RelativeAge.Hours(23), relativeAge(now - 86_399_999L, now))
        assertEquals(RelativeAge.Days(1), relativeAge(now - 86_400_000L, now))
        assertEquals(RelativeAge.Days(30), relativeAge(now - 2_592_000_000L, now))
    }

    @Test
    fun maxDaysMovesOldAgesToOlder() {
        assertEquals(RelativeAge.Days(6), relativeAge(now - 6 * 86_400_000L, now, maxDays = 7))
        assertEquals(RelativeAge.Older, relativeAge(now - 7 * 86_400_000L, now, maxDays = 7))
    }

    @Test
    fun formatUsesSharedWordingByDefault() {
        assertEquals("", formatRelativeAge(0L, now))
        assertEquals("just now", formatRelativeAge(now - 10_000L, now))
        assertEquals("just now", formatRelativeAge(now + 10_000L, now))
        assertEquals("5m ago", formatRelativeAge(now - 300_000L, now))
        assertEquals("3h ago", formatRelativeAge(now - 10_800_000L, now))
        assertEquals("2d ago", formatRelativeAge(now - 172_800_000L, now))
    }

    @Test
    fun formatLabelsCoverNowWordingAndDateFallback() {
        // news: "now" for both future and just-now.
        assertEquals("now", formatRelativeAge(now + 60_000L, now, justNowLabel = "now"))
        assertEquals("now", formatRelativeAge(now - 10_000L, now, justNowLabel = "now"))
        // games hub / madeveloper: future reads "now" / a date, old ages a date.
        assertEquals(
            "now",
            formatRelativeAge(now + 60_000L, now, futureLabel = "now"),
        )
        assertEquals(
            "date",
            formatRelativeAge(now - 30 * 86_400_000L, now, maxDays = 7, olderLabel = { "date" }),
        )
    }
}
