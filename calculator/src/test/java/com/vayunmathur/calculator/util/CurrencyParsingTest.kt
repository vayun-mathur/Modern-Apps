package com.vayunmathur.calculator.util

import com.vayunmathur.calculator.domain.Dimension
import com.vayunmathur.calculator.domain.UnitRegistry
import com.vayunmathur.calculator.network.CurrencyRatesDto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrencyParsingTest {

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    // ---- rates payload to model, without any network ----

    @Test
    fun dtoDefaultsToUsdBaseWithNoRates() {
        val dto = CurrencyRatesDto()
        assertEquals("USD", dto.base)
        assertEquals(0L, dto.updated)
        assertTrue(dto.rates.isEmpty())
    }

    @Test
    fun emptyRatesMapToNullCategory() {
        assertNull(UnitRegistry.currencyCategory(emptyMap()))
        assertNull(UnitRegistry.currencyCategory(CurrencyRatesDto().rates))
    }

    @Test
    fun invalidRatesAreDropped() {
        val category = UnitRegistry.currencyCategory(
            mapOf("USD" to 1.0, "ZERO" to 0.0, "NEG" to -2.0, "NAN" to Double.NaN, "INF" to Double.POSITIVE_INFINITY),
        )
        assertNotNull(category)
        assertEquals(listOf("USD"), category.units.map { it.token })
    }

    @Test
    fun allInvalidRatesMapToNullCategory() {
        assertNull(UnitRegistry.currencyCategory(mapOf("ZERO" to 0.0, "NEG" to -1.0)))
        assertNull(UnitRegistry.currencyCategory(mapOf("NAN" to Double.NaN)))
    }

    @Test
    fun usdFactorIsOneAndEurConversionIsExact() {
        val category = UnitRegistry.currencyCategory(mapOf("USD" to 1.0, "EUR" to 0.9))
        assertNotNull(category)
        val usd = category.units.first { it.token == "USD" }
        val eur = category.units.first { it.token == "EUR" }
        assertEquals(1.0, usd.factorToBase)
        assertClose(100.0, usd.fromBase(eur.toBase(90.0)))
        assertClose(90.0, eur.fromBase(usd.toBase(100.0)))
    }

    @Test
    fun priorityCurrenciesLeadAndRestAreAlphabetical() {
        val category = UnitRegistry.currencyCategory(mapOf("ZZZ" to 2.0, "EUR" to 0.9, "USD" to 1.0, "AAA" to 3.0))
        assertNotNull(category)
        assertEquals(listOf("USD", "EUR", "AAA", "ZZZ"), category.units.map { it.token })
    }

    @Test
    fun unknownCodesFallBackToCodeAsName() {
        val category = UnitRegistry.currencyCategory(mapOf("USD" to 1.0, "ZZZ" to 2.0))
        assertNotNull(category)
        assertEquals("US Dollar", category.units.first { it.token == "USD" }.name)
        assertEquals("ZZZ", category.units.first { it.token == "ZZZ" }.name)
    }

    @Test
    fun currencyCategoryIsConverterOnly() {
        val category = UnitRegistry.currencyCategory(mapOf("USD" to 1.0, "EUR" to 0.9))
        assertNotNull(category)
        assertEquals("Currency", category.name)
        assertFalse(category.inEquations)
        assertTrue(category.units.all { it.dimension == Dimension.CURRENCY })
        assertNull(UnitRegistry.parseTokens["USD"])
        assertNull(UnitRegistry.parseTokens["EUR"])
    }
}
