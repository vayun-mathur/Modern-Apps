package com.vayunmathur.games.chess

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** a-h / 1-8 edge labels on the board, which follow the flip and ignore the font scale. */
@RunWith(RobolectricTestRunner::class)
class BoardCoordinatesTest {
    @get:Rule val rule = createComposeRule()

    private fun showBoard(flipped: Boolean, fontScale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                BoardGrid(
                    board = Board.initialState, selectedPiece = null, isFlipped = flipped,
                    turn = PieceColor.WHITE, onSquareClick = {},
                )
            }
        }
    }

    private fun center(label: String): Offset =
        rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.center

    @Test fun `every file and rank is labelled`() {
        showBoard(flipped = false)
        ('a'..'h').forEach { rule.onNodeWithText(it.toString()).assertExists() }
        ('1'..'8').forEach { rule.onNodeWithText(it.toString()).assertExists() }
    }

    @Test fun `from white's side files run a to h left to right along the bottom`() {
        showBoard(flipped = false)
        assertTrue(center("a").x < center("h").x)
        assertTrue(center("8").y < center("1").y, "rank 8 at the top")
        assertTrue(center("a").y > center("8").y, "files along the bottom edge")
        assertTrue(center("8").x < center("h").x, "ranks along the left edge")
    }

    @Test fun `from black's side the labels flip with the board`() {
        showBoard(flipped = true)
        assertTrue(center("h").x < center("a").x, "h-file on the left")
        assertTrue(center("1").y < center("8").y, "rank 1 at the top")
        assertTrue(center("a").y > center("1").y, "files along the bottom edge")
        assertTrue(center("1").x < center("a").x, "ranks along the left edge")
    }

    @Test fun `labels are sized from the square, not the font scale`() {
        showBoard(flipped = false, fontScale = 2f)
        val cell = rule.onRoot().fetchSemanticsNode().size.width / 8f
        val node = rule.onNodeWithText("a").fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val input = layouts.first().layoutInput
        // Rendered size in px, after the 2x font scale is applied.
        val px = with(input.density) { input.style.fontSize.toPx() }
        assertEquals(cell * 0.22f, px, 0.5f, "label tracks the 22% square fraction at 200% font")
    }
}
