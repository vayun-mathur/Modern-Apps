package com.vayunmathur.games.chess

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.Move
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.opposite
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class MovesListTest {
    @get:Rule val rule = createComposeRule()

    /** A real game of [halfMoves] half-moves: each side plays its first legal move. */
    private fun game(halfMoves: Int): List<Move> {
        var board = Board.initialState
        var turn = PieceColor.WHITE
        repeat(halfMoves) {
            val m = board.legalMoves(turn).first()
            board = board.movePiece(m.start, m.end, m.promotedTo)
            turn = turn.opposite
        }
        return board.moves
    }

    /** Off screen: disposed by the lazy list, or composed (prefetch) but not displayed. */
    private fun assertOffScreen(text: String) {
        if (rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText(text).assertIsNotDisplayed()
        }
    }

    @Test fun `rows pair half-moves under move numbers`() {
        val rows = moveListRows(game(5))
        assertEquals(listOf(1, 2, 3), rows.map { it.number })
        assertEquals(game(5)[4], rows[2].white)
        assertNull(rows[2].black, "White's 3rd move has no reply yet")
    }

    @Test fun `the newest move is scrolled into view`() {
        val moves = game(30)
        rule.setContent { MovesList(moves = moves, turn = PieceColor.WHITE) }
        rule.waitForIdle()
        rule.onNodeWithText("15.").assertIsDisplayed()
        assertOffScreen("1.")
    }

    @Test fun `a new move scrolls the list again`() {
        var moves by androidx.compose.runtime.mutableStateOf(game(10))
        rule.setContent { MovesList(moves = moves, turn = PieceColor.WHITE) }
        rule.waitForIdle()
        rule.onNodeWithText("5.").assertIsDisplayed()
        moves = game(24)
        rule.waitForIdle()
        rule.onNodeWithText("12.").assertIsDisplayed()
        assertOffScreen("5.")
    }
}
