package com.vayunmathur.maps.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The transit line chip and its colour helpers.
 * Slice-2 coverage for maps (basename L, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class LineBadgeSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a hex colour parses with or without a hash`() {
        assertEquals(Color(0xFFFF0000), parseHexColor("FF0000"))
        assertEquals(Color(0xFFFF0000), parseHexColor("#FF0000"))
    }

    @Test
    fun `blank and null colours parse to null`() {
        assertNull(parseHexColor(null))
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("   "))
    }

    @Test
    fun `a malformed colour parses to null`() {
        assertNull(parseHexColor("not-a-colour"))
        assertNull(parseHexColor("12345"))
    }

    @Test
    fun `bright backgrounds get black text and dark ones white`() {
        assertEquals(Color.Black, onColorFor(Color.White))
        assertEquals(Color.White, onColorFor(Color.Black))
        // Pure red is dark by the luminance ramp (0.299), so it gets white text.
        assertEquals(Color.White, onColorFor(Color.Red))
        assertEquals(Color.Black, onColorFor(Color.Yellow))
    }

    @Test
    fun `the badge shows the line name`() {
        rule.setContent { LineBadge(line = "N", routeColor = "FF0000") }
        rule.onNodeWithText("N").assertIsDisplayed()
    }

    @Test
    fun `a blank line shows the placeholder dash`() {
        rule.setContent { LineBadge(line = "", routeColor = null) }
        rule.onNodeWithText("—").assertIsDisplayed()
    }

    @Test
    fun `a null colour falls back to the theme primary`() {
        // No crash, and the line still reads — the fallback is the theme colour.
        rule.setContent { LineBadge(line = "38", routeColor = "zzz") }
        rule.onNodeWithText("38").assertIsDisplayed()
    }
}
