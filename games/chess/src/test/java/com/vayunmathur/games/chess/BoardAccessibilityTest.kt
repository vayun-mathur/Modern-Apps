package com.vayunmathur.games.chess

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.util.ChessActions
import com.vayunmathur.games.chess.util.ChessUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TalkBack can read and play the board: named squares, state, selection and a click action. */
@RunWith(RobolectricTestRunner::class)
class BoardAccessibilityTest {
    @get:Rule val rule = createComposeRule()

    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    private fun showBoard(
        board: Board = Board.initialState,
        selected: Position? = null,
        flipped: Boolean = false,
        turn: PieceColor = PieceColor.WHITE,
        showLastMove: Boolean = false,
        clicks: MutableList<Position> = mutableListOf(),
    ) {
        rule.setContent {
            BoardGrid(
                board = board, selectedPiece = selected, isFlipped = flipped, turn = turn,
                onSquareClick = { clicks += it }, showLastMove = showLastMove,
            )
        }
    }

    private fun state(description: String): String? =
        rule.onNodeWithContentDescription(description).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.StateDescription)

    private fun clickLabel(description: String): String? =
        rule.onNodeWithContentDescription(description).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.label

    @Test fun `every square is named, occupied or empty`() {
        showBoard()
        rule.onNodeWithContentDescription("e2, White Pawn").assertExists()
        rule.onNodeWithContentDescription("e8, Black King").assertExists()
        rule.onNodeWithContentDescription("e4, empty").assertExists()
        assertEquals(64, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test fun `double-tapping a square plays it`() {
        val clicks = mutableListOf<Position>()
        showBoard(clicks = clicks)
        assertEquals("Select", clickLabel("e2, White Pawn"))
        rule.onNodeWithContentDescription("e2, White Pawn").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(sq('e', 2)), clicks)
    }

    @Test fun `selection and legal targets are announced`() {
        showBoard(selected = sq('e', 2))
        rule.onNodeWithContentDescription("e2, White Pawn").assertIsSelected()
        rule.onNodeWithContentDescription("d2, White Pawn").assertIsNotSelected()
        assertEquals("legal move", state("e4, empty"))
        assertEquals("Move here", clickLabel("e4, empty"))
        assertEquals(null, state("e5, empty"), "not reachable")
    }

    @Test fun `captures, check and the last move are announced`() {
        // 1. f3 e5 2. g4 Qh4+: White's king on e1 is in check, Black's queen just moved to h4.
        val board = listOf(sq('f', 2) to sq('f', 3), sq('e', 7) to sq('e', 5), sq('g', 2) to sq('g', 4),
            sq('d', 8) to sq('h', 4)).fold(Board.initialState) { b, (f, t) -> b.movePiece(f, t) }
        showBoard(board = board, showLastMove = true)
        assertTrue(state("e1, White King")!!.contains("in check"))
        assertEquals("last move", state("h4, Black Queen"))
        assertEquals("last move", state("d8, empty"))
    }

    @Test fun `a capture target says so`() {
        // 1. e4 d5: the e4 pawn can take on d5.
        val board = Board.initialState.movePiece(sq('e', 2), sq('e', 4)).movePiece(sq('d', 7), sq('d', 5))
        showBoard(board = board, selected = sq('e', 4))
        assertEquals("can capture", state("d5, Black Pawn"))
    }

    @Test fun `names stay true on a flipped board`() {
        val clicks = mutableListOf<Position>()
        showBoard(flipped = true, clicks = clicks)
        rule.onNodeWithContentDescription("e2, White Pawn").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(sq('e', 2)), clicks)
    }

    @Test fun `the game screen announces the AI's reply as the last move`() {
        val board = Board.initialState.movePiece(sq('e', 2), sq('e', 4)).movePiece(sq('e', 7), sq('e', 5))
        rule.setContent {
            ChessGameScreen(
                state = ChessUiState(board = board), actions = ChessActions.Noop,
                onNewGame = {}, onOpenGameCenter = {},
            )
        }
        assertEquals("last move", state("e5, Black Pawn"))
        assertEquals("last move", state("e7, empty"))
        assertEquals(null, state("e4, White Pawn"), "only the most recent move is marked")
    }
}
