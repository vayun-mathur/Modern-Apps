package com.vayunmathur.games.minesweeper.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The neighbour-count palette: eight distinct, readable colours in both variants. */
class CountPaletteTest {

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun eachVariantHasEightDistinctColours() {
        for (dark in listOf(false, true)) {
            val colours = (1..8).map { countColor(it, dark) }
            assertEquals(8, colours.toSet().size, "dark=$dark")
        }
    }

    @Test
    fun coloursContrastWithTheRevealedCellBackground() {
        // Material 3 baseline surfaceContainerLowest (light) and a dark surface container.
        val lightBg = Color(0xFFF7F2FA)
        val darkBg = Color(0xFF1D1B20)
        for (count in 1..8) {
            assertTrue(contrast(countColor(count, dark = false), lightBg) >= 4f, "light $count")
            assertTrue(contrast(countColor(count, dark = true), darkBg) >= 4f, "dark $count")
        }
    }

    @Test
    fun outOfRangeCountsClamp() {
        assertEquals(countColor(1, dark = true), countColor(0, dark = true))
        assertEquals(countColor(8, dark = false), countColor(9, dark = false))
    }
}
