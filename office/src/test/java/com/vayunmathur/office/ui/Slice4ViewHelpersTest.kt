package com.vayunmathur.office.ui

import com.vayunmathur.library.ui.odf.OdfCondFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4/4 pure view-helper tests: conditional-format evaluation
 * (ParagraphViews) and chart axis formatting (OdfChartViews).
 */
class Slice4ViewHelpersTest {

    private fun rule(cond: String, bg: Long? = null) = OdfCondFormat(cond, backgroundColor = bg)

    // ---- evalCondFormat ----

    @Test fun numericComparisons() {
        val gt = rule("value() > 10", bg = 1L)
        assertEquals(gt, evalCondFormat(listOf(gt), 15.0, "15"))
        assertNull(evalCondFormat(listOf(gt), 5.0, "5"))
        assertNull(evalCondFormat(listOf(gt), null, "15"))
    }

    @Test fun allNumericOperators() {
        assertTrue(evalCondFormat(listOf(rule("value() < 10")), 5.0, "5") != null)
        assertTrue(evalCondFormat(listOf(rule("value() <= 10")), 10.0, "10") != null)
        assertTrue(evalCondFormat(listOf(rule("value() >= 10")), 10.0, "10") != null)
        assertTrue(evalCondFormat(listOf(rule("value() = 10")), 10.0, "10") != null)
        assertTrue(evalCondFormat(listOf(rule("value() <> 10")), 9.0, "9") != null)
        assertNull(evalCondFormat(listOf(rule("value() = 10")), 9.0, "9"))
    }

    @Test fun cellContentAlias() {
        assertTrue(evalCondFormat(listOf(rule("cell-content() = 3")), 3.0, "3") != null)
    }

    @Test fun stringEquality() {
        val eq = rule("value() = \"done\"")
        assertEquals(eq, evalCondFormat(listOf(eq), null, "done"))
        assertNull(evalCondFormat(listOf(eq), null, "todo"))
        val ne = rule("value() <> \"done\"")
        assertEquals(ne, evalCondFormat(listOf(ne), null, "todo"))
        // Non-equality ops on strings never match.
        assertNull(evalCondFormat(listOf(rule("value() > \"done\"")), null, "zzz"))
    }

    @Test fun firstMatchWins() {
        val a = rule("value() > 1", bg = 1L)
        val b = rule("value() > 2", bg = 2L)
        assertEquals(a, evalCondFormat(listOf(a, b), 5.0, "5"))
        assertEquals(b, evalCondFormat(listOf(rule("value() > 99"), b), 5.0, "5"))
    }

    @Test fun malformedConditionsSkipped() {
        assertNull(evalCondFormat(listOf(rule("nonsense")), 5.0, "5"))
        assertNull(evalCondFormat(listOf(rule("")), 5.0, "5"))
        assertNull(evalCondFormat(emptyList(), 5.0, "5"))
        // Non-numeric rhs with numeric value: no match.
        assertNull(evalCondFormat(listOf(rule("value() = abc")), 5.0, "5"))
    }

    @Test fun whitespaceTolerated() {
        assertTrue(evalCondFormat(listOf(rule("  value()   <=   7  ")), 7.0, "7") != null)
    }

    // ---- formatAxis ----

    @Test fun axisWholeNumbers() {
        assertEquals("0", formatAxis(0f))
        assertEquals("42", formatAxis(42f))
        assertEquals("-3", formatAxis(-3f))
    }

    @Test fun axisFractionsOneDecimal() {
        assertEquals("1.5", formatAxis(1.5f))
        assertEquals("3.1", formatAxis(3.14f))
    }
}
