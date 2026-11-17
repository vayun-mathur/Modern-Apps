package com.vayunmathur.calculator.util

import com.vayunmathur.calculator.domain.*
import com.vayunmathur.calculator.network.*
import com.vayunmathur.calculator.platform.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Instant
import java.time.ZoneId
import com.vayunmathur.calculator.ui.todayAtTimeSeconds

/** Covers the date/datetime support added to the expression engine: the `#<epoch>` literal and
 * the instant arithmetic rules (instant−instant=duration, instant±duration=instant). */
class DateExpressionTest {

    private fun q(src: String): Quantity = Expression.parse(src).evalQuantity()

    @Test
    fun dateMinusDateGivesDuration() {
        val result = q("#1000000 - #900000")
        assertEquals(Dimension.TIME, result.dimension)
        assertFalse(result.instant, "a difference of two dates is a duration, not an instant")
        assertEquals(100000.0, result.value, 1e-9)
    }

    @Test
    fun datePlusDurationGivesLaterDate() {
        val result = q("#1000000 + 1day")
        assertTrue(result.instant)
        assertEquals(1000000.0 + 86400.0, result.value, 1e-9)
    }

    @Test
    fun durationPlusDateGivesLaterDate() {
        val result = q("2h + #1000000")
        assertTrue(result.instant)
        assertEquals(1000000.0 + 7200.0, result.value, 1e-9)
    }

    @Test
    fun dateMinusDurationGivesEarlierDate() {
        val result = q("#1000000 - 1day")
        assertTrue(result.instant)
        assertEquals(1000000.0 - 86400.0, result.value, 1e-9)
    }

    @Test
    fun addingTwoDatesIsRejected() {
        assertFailsWith<ExpressionError> { q("#1000000 + #900000") }
    }

    @Test
    fun subtractingDateFromDurationIsRejected() {
        assertFailsWith<ExpressionError> { q("1day - #1000000") }
    }

    @Test
    fun multiplyingADateIsRejected() {
        assertFailsWith<ExpressionError> { q("#1000000 * 2") }
    }

    @Test
    fun addingAScalarToADateIsRejected() {
        assertFailsWith<ExpressionError> { q("#1000000 + 5") }
    }

    @Test
    fun addingTwoTimesOfDayIsRejected() {
        // The time picker inserts an instant (today at H:M), not a duration, so 5 AM + 2 AM
        // errors like any other instant + instant rather than returning 7 AM.
        val fiveAm = "#${todayAtTimeSeconds(5, 0)}"
        val twoAm = "#${todayAtTimeSeconds(2, 0)}"
        assertFailsWith<ExpressionError> { q("$fiveAm + $twoAm") }
    }

    @Test
    fun subtractingTwoTimesOfDayGivesADuration() {
        val fiveAm = "#${todayAtTimeSeconds(5, 0)}"
        val twoAm = "#${todayAtTimeSeconds(2, 0)}"
        val result = q("$fiveAm - $twoAm")
        assertEquals(Dimension.TIME, result.dimension)
        assertFalse(result.instant, "a difference of two times is a duration, not an instant")
        assertEquals(3 * 3600.0, result.value, 1e-9)
    }

    @Test
    fun timeOfDayIsAnInstantOnTheCurrentDay() {
        val epoch = todayAtTimeSeconds(14, 30)
        val result = q("#$epoch")
        assertTrue(result.instant)
        val zoned = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault())
        assertEquals(14, zoned.hour)
        assertEquals(30, zoned.minute)
    }
}
