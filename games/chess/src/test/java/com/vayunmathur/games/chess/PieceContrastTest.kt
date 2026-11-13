package com.vayunmathur.games.chess

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two-tone pieces must read on every background they sit on: both board squares and the
 * captured-pieces card in light and dark themes. "Reads" = at least one of the piece's two inks
 * reaches the WCAG 3:1 contrast for graphical objects against that background.
 */
class PieceContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    // Material 3 Card containers (surfaceContainerHighest) in the baseline light/dark schemes.
    private val lightCard = Color(0xFFE6E0E9)
    private val darkCard = Color(0xFF36343B)
    private val backgrounds = mapOf(
        "light square" to LightSquareColor, "dark square" to DarkSquareColor,
        "light card" to lightCard, "dark card" to darkCard,
    )

    @Test fun `white pieces read on every background`() {
        for ((name, bg) in backgrounds) {
            val best = maxOf(contrast(WhitePieceFill, bg), contrast(WhitePieceOutline, bg))
            assertTrue(best >= 3f, "white piece on $name: $best:1")
        }
    }

    @Test fun `black pieces read on every background`() {
        for ((name, bg) in backgrounds) {
            val best = maxOf(contrast(BlackPieceFill, bg), contrast(BlackPieceOutline, bg))
            assertTrue(best >= 3f, "black piece on $name: $best:1")
        }
    }

    @Test fun `the old flat palette was the problem`() {
        // Flat white on the old 0xFFBBBBBB light square, flat black on a dark card.
        assertTrue(contrast(Color.White, Color(0xFFBBBBBB)) < 2f)
        assertTrue(contrast(Color.Black, darkCard) < 2f)
    }
}
