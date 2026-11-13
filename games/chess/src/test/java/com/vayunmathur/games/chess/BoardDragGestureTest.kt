package com.vayunmathur.games.chess

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BoardGrid's drag gesture and layout, driven through real touch input. Kept apart from
 * ChessDropTest's view-model tests: a compose rule in the same Robolectric class broke the
 * engine probe those view models run on construction.
 */
@RunWith(RobolectricTestRunner::class)
class BoardDragGestureTest {
    @get:Rule val rule = createComposeRule()

    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    @Test fun boardKeepsFilesLeftToRightUnderRtl() {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                BoardGrid(
                    board = Board.initialState, selectedPiece = null, isFlipped = false,
                    turn = PieceColor.WHITE, onSquareClick = {},
                )
            }
        }
        val squares = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        // Layout order: squares[0] is a8 (row 0, col 0), squares[7] is h8.
        assertTrue(
            squares[0].boundsInRoot.left < squares[7].boundsInRoot.left,
            "a8 must stay on the left in an RTL locale, or the drag maths would hit the mirrored file"
        )
    }

    private class BoardRecorder : HapticFeedback {
        val clicks = mutableListOf<Position>()
        val drops = mutableListOf<Pair<Position, Position>>()
        val haptics = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            haptics += hapticFeedbackType
        }
    }

    private fun showBoard(rec: BoardRecorder, flipped: Boolean = false) {
        rule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides rec) {
                BoardGrid(
                    board = Board.initialState, selectedPiece = null, isFlipped = flipped,
                    turn = PieceColor.WHITE, onSquareClick = { rec.clicks += it },
                    onSquareDrop = { from, to -> rec.drops += from to to },
                )
            }
        }
    }

    /** Centre of the square drawn at visual row/column [vr]/[vc]; the board fills the root's width. */
    private fun TouchInjectionScope.cell(vr: Int, vc: Int): Offset {
        val side = width / 8f
        return Offset((vc + 0.5f) * side, (vr + 0.5f) * side)
    }

    @Test fun quickFlickMovesWithoutALongPress() {
        val rec = BoardRecorder()
        showBoard(rec)
        // e2 is drawn at visual (6, 4), e4 at (4, 4). No dwell before moving.
        rule.onRoot().performTouchInput { down(cell(6, 4)); moveTo(cell(4, 4)); up() }
        rule.waitForIdle()
        assertEquals(listOf(sq('e', 2) to sq('e', 4)), rec.drops)
        assertTrue(rec.clicks.isEmpty(), "a drag must not also fire the square's tap")
    }

    @Test fun flickOnAFlippedBoardMovesTheRealSquares() {
        val rec = BoardRecorder()
        showBoard(rec, flipped = true)
        // Seen from Black, e2 is drawn at visual (1, 3) and e4 at (3, 3).
        rule.onRoot().performTouchInput { down(cell(1, 3)); moveTo(cell(3, 3)); up() }
        rule.waitForIdle()
        assertEquals(listOf(sq('e', 2) to sq('e', 4)), rec.drops)
    }

    @Test fun dragDrawsTheFloatingPieceAndBuzzesOnPickUp() {
        val rec = BoardRecorder()
        showBoard(rec)
        rule.onNodeWithTag(DRAGGED_PIECE_TAG).assertDoesNotExist()
        rule.onRoot().performTouchInput { down(cell(6, 4)); moveTo(cell(5, 4)) }
        rule.waitForIdle()
        rule.onNodeWithTag(DRAGGED_PIECE_TAG).assertExists()
        assertEquals(listOf(HapticFeedbackType.LongPress), rec.haptics, "one pickup haptic")
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        rule.onNodeWithTag(DRAGGED_PIECE_TAG).assertDoesNotExist()
        assertEquals(listOf(sq('e', 2) to sq('e', 3)), rec.drops)
    }

    @Test fun swipeFromAnEmptySquareIsNotADrag() {
        val rec = BoardRecorder()
        showBoard(rec)
        // e4 is empty: the press is left to taps and enclosing scrolls.
        rule.onRoot().performTouchInput { down(cell(4, 4)); moveTo(cell(2, 4)); up() }
        rule.waitForIdle()
        assertTrue(rec.drops.isEmpty())
        assertTrue(rec.haptics.isEmpty())
    }
}
