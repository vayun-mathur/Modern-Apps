package com.vayunmathur.calculator.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The widget input lives in Glance preferences and is re-read, re-parsed and re-rendered on
 * every tap, so it must stay bounded: without a cap each tap made the next one slower until
 * updates stalled (#641).
 */
class CalculatorWidgetInputCapTest {

    @Test
    fun normalTypingIsUntouched() {
        assertEquals("12+8", appendWidgetInput("12+", "8"))
        assertEquals("", appendWidgetInput("", ""))
        assertEquals("7", appendWidgetInput("", "7"))
    }

    @Test
    fun longInputStaysBounded() {
        val grown = "1".repeat(MAX_WIDGET_INPUT_LENGTH) + "2+3"
        val capped = appendWidgetInput(grown, "4")
        assertTrue(
            capped.length <= MAX_WIDGET_INPUT_LENGTH,
            "widget input grew to ${capped.length}",
        )
    }

    @Test
    fun capKeepsTheStartOfWhatWasTyped() {
        val capped = appendWidgetInput("9*9+", "8".repeat(MAX_WIDGET_INPUT_LENGTH))
        assertTrue(capped.startsWith("9*9+"))
        assertEquals(MAX_WIDGET_INPUT_LENGTH, capped.length)
    }

    @Test
    fun alreadyOversizedInputShrinksOnNextTap() {
        val legacy = "5".repeat(MAX_WIDGET_INPUT_LENGTH + 50)
        assertEquals(MAX_WIDGET_INPUT_LENGTH, appendWidgetInput(legacy, "").length)
    }
}
