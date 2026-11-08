package com.vayunmathur.travel.pure

import com.vayunmathur.travel.ui.conditionsLabels
import com.vayunmathur.travel.ui.formatCountdown
import com.vayunmathur.travel.ui.formatDuration
import com.vayunmathur.travel.ui.formatMoney
import com.vayunmathur.travel.ui.stopsLabel
import com.vayunmathur.travel.network.ConditionsDto
import com.vayunmathur.travel.network.PenaltyRuleDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the travel display helpers through production TravelFormat:
 * money labels, duration/stop labels, countdowns and fare-condition
 * chips.
 *
 * All helpers are pure functions over strings and numbers, so they
 * are called directly with valid, empty, invalid and boundary input.
 */
class TravelFormatTest {
    @Test
    fun wholeDollarAmountsDropDecimals() {
        assertEquals("$412", formatMoney("412", "USD"))
        assertEquals("$412", formatMoney("412.00", "USD"))
    }

    @Test
    fun fractionalAmountsKeepTwoDecimals() {
        assertEquals("$412.50", formatMoney("412.5", "USD"))
        assertEquals("$412.05", formatMoney("412.05", "USD"))
    }

    @Test
    fun euroAndPoundGetSymbols() {
        assertEquals("€220", formatMoney("220", "EUR"))
        assertEquals("€220.50", formatMoney("220.5", "EUR"))
        assertEquals("£99", formatMoney("99", "GBP"))
    }

    @Test
    fun unknownCurrencyPrefixesCode() {
        assertEquals("JPY 1000", formatMoney("1000", "JPY"))
        assertEquals("JPY 1000", formatMoney("1000", "jpy"))
    }

    @Test
    fun fractionalAmountsIgnoreDefaultLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            // Comma-decimal locale: the label must still use a decimal point.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("€220.50", formatMoney("220.5", "EUR"))
            assertEquals("$412.50", formatMoney("412.5", "USD"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun unparseableAmountFallsBackToRaw() {
        assertEquals("USD abc", formatMoney("abc", "USD"))
        assertEquals("USD ", formatMoney("", "USD"))
    }

    @Test
    fun emptyCurrencyFallsBackCleanly() {
        assertEquals(" 10", formatMoney("10", ""))
    }

    @Test
    fun durationsSplitHoursAndMinutes() {
        assertEquals("8h 30m", formatDuration(510))
        assertEquals("8h", formatDuration(480))
        assertEquals("45m", formatDuration(45))
    }

    @Test
    fun zeroAndNegativeDurationsAreBlank() {
        assertEquals("", formatDuration(0))
        assertEquals("", formatDuration(-10))
    }

    @Test
    fun exactHourHasNoMinutes() {
        assertEquals("1h", formatDuration(60))
    }

    @Test
    fun stopsLabels() {
        assertEquals("Nonstop", stopsLabel(0))
        assertEquals("1 stop", stopsLabel(1))
        assertEquals("2 stops", stopsLabel(2))
        assertEquals("5 stops", stopsLabel(5))
    }

    @Test
    fun countdownFormatsMmSs() {
        assertEquals("2:05", formatCountdown(125))
        assertEquals("0:00", formatCountdown(0))
        assertEquals("60:00", formatCountdown(3600))
    }

    @Test
    fun countdownClampsNegativeToZero() {
        assertEquals("0:00", formatCountdown(-30))
    }

    @Test
    fun refundableAndChangeableChips() {
        val both = ConditionsDto(PenaltyRuleDto(true), PenaltyRuleDto(true))
        assertEquals(listOf("Refundable", "Changeable"), conditionsLabels(both))
    }

    @Test
    fun disallowedRefundFallsBackToNonRefundable() {
        val none = ConditionsDto(PenaltyRuleDto(false), PenaltyRuleDto(false))
        assertEquals(listOf("Non-refundable"), conditionsLabels(none))
    }

    @Test
    fun absentRulesGiveNoChips() {
        assertEquals(emptyList(), conditionsLabels(ConditionsDto()))
        assertEquals(listOf("Changeable"), conditionsLabels(ConditionsDto(null, PenaltyRuleDto(true))))
    }
}
