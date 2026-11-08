package com.vayunmathur.calculator.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormattingEdgeTest {

    private fun unit(token: String): UnitDef = UnitRegistry.parseTokens[token] ?: error("no unit '$token'")

    @Test
    fun nanAndInfinitiesHaveReadableForms() {
        assertEquals("Undefined", formatResult(Double.NaN))
        assertEquals("∞", formatResult(Double.POSITIVE_INFINITY))
        assertEquals("-∞", formatResult(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun zeroAndNegativeZeroShowAsZero() {
        assertEquals("0", formatResult(0.0))
        assertEquals("0", formatResult(-0.0))
    }

    @Test
    fun floatArtefactsAreRoundedAway() {
        assertEquals("0.3", formatResult(0.1 + 0.2))
        assertEquals("2.5", formatResult(2.50))
        assertEquals("0.333333333333", formatResult(1.0 / 3.0))
    }

    @Test
    fun largeMagnitudesUseScientificNotation() {
        assertEquals("1e+12", formatResult(1e12))
        assertTrue(formatResult(1e15).contains("e"))
        assertTrue(formatResult(-2.5e13).contains("e"))
    }

    @Test
    fun normalMagnitudesStayPlain() {
        assertEquals("999999999999", formatResult(999999999999.0))
        assertEquals("0.000001", formatResult(0.000001))
        assertEquals("-42.5", formatResult(-42.5))
        assertEquals("123456789", formatResult(123456789.0))
    }

    @Test
    fun smallMagnitudesUseScientificNotation() {
        assertTrue(formatResult(1e-7).contains("e"))
        assertTrue(formatResult(-1e-9).contains("e"))
    }

    @Test
    fun scientificTokenFormReparsesToTheSameValue() {
        // Display scientific notation is lowercase ("1e+12"), but the parser
        // reads lowercase e as Euler's constant: "1e+12" re-parses as
        // 1*e+12 = 14.718... The token form must use uppercase E, the
        // parser's scientific notation, so a result can seed the next input.
        assertEquals("1E+12", formatResultToken(1e12))
        assertEquals("1E-7", formatResultToken(1e-7))
        assertEquals("-2.5E+13", formatResultToken(-2.5e13))
        assertEquals("42.5", formatResultToken(42.5))
        assertEquals(1e12, Expression.parse(formatResultToken(1e12)).eval(), 0.0)
        assertEquals(1e-7, Expression.parse(formatResultToken(1e-7)).eval(), 0.0)
        val length = Expression.parse("1E12m").evalQuantity()
        assertEquals("1E+12 m", formatQuantity(length, unit("m"), useToken = true))
        assertEquals(1e12, Expression.parse("1E+12 m").evalQuantity().value, 0.0)
    }

    @Test
    fun tokenFormUsesPlainSpaceForRoundTrips() {
        val q = Expression.parse("5m").evalQuantity()
        assertEquals("5 m", formatQuantity(q, unit("m"), useToken = true))
    }

    @Test
    fun displayFormUsesThinSpace() {
        val q = Expression.parse("5m").evalQuantity()
        val text = formatQuantity(q, unit("m"))
        assertEquals(3, text.length)
        assertEquals('5', text[0])
        assertEquals(0x202F, text[1].code)
        assertEquals('m', text[2])
    }

    @Test
    fun dimensionlessValueIgnoresUnit() {
        assertEquals("5", formatQuantity(Quantity.scalar(5.0), unit("m")))
        assertEquals("5", formatQuantity(Quantity.scalar(5.0), null))
    }

    @Test
    fun instantTokenFormIsEpoch() {
        assertEquals("#1000000", formatInstant(1000000.0, useToken = true))
        assertEquals("#-5", formatInstant(-5.0, useToken = true))
    }

    @Test
    fun renderInputReplacesEpochTokens() {
        val rendered = renderInputForDisplay("a #1000000 b")
        assertTrue(!rendered.contains("#"), "epoch token was not replaced: $rendered")
        assertTrue(rendered.startsWith("a ") && rendered.endsWith(" b"))
    }
}
