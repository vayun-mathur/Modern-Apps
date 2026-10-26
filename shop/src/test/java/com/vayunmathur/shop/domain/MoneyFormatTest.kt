package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.Money
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoneyFormatTest {

    @Test
    fun minorUnitsDivideByOneHundred() {
        assertEquals(12.99, MoneyFormat.majorUnits(Money(1299, "USD")), 0.001)
    }

    @Test
    fun zeroDecimalCurrenciesAreNotDivided() {
        assertEquals(1299.0, MoneyFormat.majorUnits(Money(1299, "JPY")), 0.001)
        assertEquals(500.0, MoneyFormat.majorUnits(Money(500, "KRW")), 0.001)
    }

    @Test
    fun formatsUsdWithSymbol() {
        val formatted = MoneyFormat.format(Money(1299, "USD"), Locale.US)
        assertTrue(formatted.contains("12.99"), "was: $formatted")
        assertTrue(formatted.contains("$"), "was: $formatted")
    }

    @Test
    fun threeDecimalCurrenciesUseThousandths() {
        assertEquals(12.999, MoneyFormat.majorUnits(Money(12999, "KWD")), 0.0001)
        assertTrue(MoneyFormat.format(Money(12999, "KWD")).contains("12.999"))
    }

    @Test
    fun unknownCurrencyFallsBackToCode() {
        val formatted = MoneyFormat.format(Money(100, "XXQ"), Locale.US)
        assertTrue(formatted.contains("XXQ"), "was: $formatted")
    }
}
