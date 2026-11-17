package com.vayunmathur.maps.util
import com.vayunmathur.maps.platform.DurationParts
import com.vayunmathur.maps.platform.durationParts
import com.vayunmathur.maps.platform.formatDistance
import com.vayunmathur.maps.platform.formatDuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The unit breakdown behind [formatDuration]. The formatting itself is ICU and needs a
 * device, exactly like [formatDistance]; the arithmetic that decides "1 h 12 min" vs
 * "45 min" vs "less than a minute" does not, and that is the part with edge cases.
 */
class DurationPartsTest {

    @Test
    fun `under an hour is minutes only`() {
        assertEquals(DurationParts(hours = 0, minutes = 45), durationParts(45.minutes))
    }

    @Test
    fun `over an hour splits into hours and minutes`() {
        assertEquals(DurationParts(hours = 1, minutes = 12), durationParts(72.minutes))
        assertEquals(DurationParts(hours = 2, minutes = 5), durationParts(2.hours + 5.minutes))
    }

    /** A whole number of hours has no minutes line — largest units only. */
    @Test
    fun `a whole hour has no minutes`() {
        assertEquals(DurationParts(hours = 2, minutes = 0), durationParts(2.hours))
        assertFalse(durationParts(2.hours).isUnderAMinute)
    }

    /** Seconds are dropped, not rounded, so the number never disagrees with the clock. */
    @Test
    fun `seconds are truncated rather than rounded up`() {
        assertEquals(DurationParts(0, 45), durationParts(45.minutes + 59.seconds))
        assertEquals(DurationParts(1, 12), durationParts(1.hours + 12.minutes + 59.seconds))
    }

    @Test
    fun `fifty-nine seconds is under a minute`() {
        assertTrue(durationParts(59.seconds).isUnderAMinute)
    }

    @Test
    fun `sixty seconds is one minute, not under a minute`() {
        val parts = durationParts(60.seconds)
        assertFalse(parts.isUnderAMinute)
        assertEquals(DurationParts(0, 1), parts)
    }

    @Test
    fun `sixty-one minutes crosses into an hour`() {
        assertEquals(DurationParts(1, 1), durationParts(61.minutes))
    }

    @Test
    fun `zero is under a minute`() {
        assertTrue(durationParts(0.seconds).isUnderAMinute)
    }

    /** A negative duration cannot be rendered as "-1 h"; it clamps to the empty case. */
    @Test
    fun `a negative duration is under a minute`() {
        assertTrue(durationParts((-5).minutes).isUnderAMinute)
    }
}
