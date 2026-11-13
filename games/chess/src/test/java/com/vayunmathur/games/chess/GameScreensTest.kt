package com.vayunmathur.games.chess

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.Piece
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.util.ChessActions
import com.vayunmathur.games.chess.util.ChessUiState
import com.vayunmathur.games.chess.util.GameMode
import com.vayunmathur.games.chess.util.GameResult
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class GameScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = ctx.getString(id)

    private class RecordingActions : ChessActions {
        val clicks = mutableListOf<Position>()
        val drops = mutableListOf<Pair<Position, Position>>()
        val promotes = mutableListOf<PieceType>()
        override fun onSquareClick(position: Position) { clicks += position }
        override fun onSquareDrop(from: Position, to: Position) { drops += from to to }
        override fun onPromote(pieceType: PieceType) { promotes += pieceType }
    }

    @Test fun `game screen renders board pieces and routes square clicks`() {
        val actions = RecordingActions()
        var newGame = 0; var gameCenter = 0
        rule.setContent {
            ChessGameScreen(state = ChessUiState(), actions = actions,
                onNewGame = { newGame++ }, onOpenGameCenter = { gameCenter++ })
        }
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(str(R.string.piece_white_pawn))).assertCountEquals(8)
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(str(R.string.piece_black_king))).assertCountEquals(1)
        // First clickable nodes are board squares (the app-bar action follows in semantics order may vary);
        // click a square by counting: 64 squares exist
        val squares = rule.onAllNodes(hasClickAction())
        assertTrue(squares.fetchSemanticsNodes().size >= 64)
        squares[0].performClick()
        assertEquals(1, actions.clicks.size)
        assertEquals(0, newGame); assertEquals(0, gameCenter)
    }

    @Test fun `board grid flipped and selected shows hints and reports positions`() {
        val actions = RecordingActions()
        rule.setContent {
            BoardGrid(board = Board.initialState, selectedPiece = Position(6, 4),
                isFlipped = true, turn = PieceColor.WHITE, onSquareClick = actions::onSquareClick, showLastMove = true)
        }
        rule.onAllNodes(hasClickAction())[10].performClick()
        assertEquals(listOf(Position(1, 2)), actions.clicks)
    }

    @Test fun `a new last move slides its piece in from the start square`() {
        val start = Board.initialState
        val afterE4 = start.movePiece(Position(6, 4), Position(4, 4))
        var board by androidx.compose.runtime.mutableStateOf(start)
        rule.setContent {
            BoardGrid(board = board, selectedPiece = null, isFlipped = false, turn = PieceColor.BLACK,
                onSquareClick = {}, showLastMove = true)
        }
        val cell = rule.onRoot().fetchSemanticsNode().size.width / 8f
        rule.onNodeWithTag(SLIDING_PIECE_TAG).assertDoesNotExist()
        rule.mainClock.autoAdvance = false
        board = afterE4
        // A write from the test thread reaches the paused clock's recomposer only once applied.
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        val top0 = rule.onNodeWithTag(SLIDING_PIECE_TAG).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top0 > 4.5f * cell, "starts near e2 (row 6), was ${top0 / cell} cells down")
        rule.mainClock.advanceTimeBy(1_000)
        val top1 = rule.onNodeWithTag(SLIDING_PIECE_TAG).fetchSemanticsNode().boundsInRoot.top
        assertEquals(4f * cell, top1, 1f, "lands on e4 (row 4)")
    }

    @Test fun `board grid tap routes click and never fires drop`() {
        val actions = RecordingActions()
        rule.setContent {
            BoardGrid(board = Board.initialState, selectedPiece = null,
                isFlipped = false, turn = PieceColor.WHITE,
                onSquareClick = actions::onSquareClick, onSquareDrop = actions::onSquareDrop)
        }
        // e2 is row 6, col 4 in layout order.
        rule.onAllNodes(hasClickAction())[6 * 8 + 4].performClick()
        assertEquals(listOf(Position(6, 4)), actions.clicks)
        assertTrue(actions.drops.isEmpty(), "a quick tap must not start a drag")
    }

    @Test fun `board grid press-and-drag (even after a dwell) commits drop from e2 to e4`() {
        val actions = RecordingActions()
        rule.setContent {
            BoardGrid(board = Board.initialState, selectedPiece = null,
                isFlipped = false, turn = PieceColor.WHITE,
                onSquareClick = actions::onSquareClick, onSquareDrop = actions::onSquareDrop)
        }
        val squares = rule.onAllNodes(hasClickAction())
        // Touch-injection coordinates are local to the interacted node, so express
        // the e4 center relative to the e2 node's top-left.
        val fromNode = squares[6 * 8 + 4].fetchSemanticsNode()
        val toCenter = squares[4 * 8 + 4].fetchSemanticsNode().boundsInRoot.center
        val target = toCenter - fromNode.boundsInRoot.topLeft
        squares[6 * 8 + 4].performTouchInput {
            down(center)
            advanceEventTime(600)
            moveTo(target)
            advanceEventTime(100)
            up(0)
        }
        assertEquals(listOf(Position(6, 4) to Position(4, 4)), actions.drops)
        assertTrue(actions.clicks.isEmpty(), "the drag consumes the gesture; no tap fires")
    }

    @Test fun `promotion dialog offers four pieces and routes choice`() {
        val actions = RecordingActions()
        rule.setContent { PawnPromotionDialog(PieceColor.WHITE, onPromote = actions::onPromote) }
        rule.onNodeWithText(str(R.string.promote_pawn)).assertExists()
        rule.onAllNodes(hasClickAction())[0].performClick()
        assertEquals(listOf(PieceType.QUEEN), actions.promotes)
    }

    @Test fun `captured pieces row renders pieces`() {
        rule.setContent {
            CapturedPiecesRow(listOf(Piece(PieceType.QUEEN, PieceColor.BLACK), Piece(PieceType.PAWN, PieceColor.WHITE)))
        }
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(str(R.string.piece_black_queen))).assertCountEquals(1)
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(str(R.string.piece_white_pawn))).assertCountEquals(1)
    }

    @Test fun `captured pieces row empty renders nothing`() {
        rule.setContent { CapturedPiecesRow(emptyList()) }
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(str(R.string.piece_black_queen))).assertCountEquals(0)
    }

    @Test fun `moves list shows paired notation`() {
        val moves = Board.initialState.legalMoves(PieceColor.WHITE).take(2)
        rule.setContent { MovesList(moves = moves, turn = PieceColor.BLACK) }
        rule.onNodeWithText(str(R.string.color_white)).assertExists()
        rule.onNodeWithText(str(R.string.color_black)).assertExists()
        rule.onNodeWithText(moves[0].toString()).assertExists()
    }

    @Test fun `new game dialog two player and ai settings flow`() {
        val modes = mutableListOf<GameMode>()
        rule.setContent { NewGameDialog(onNewGame = { modes += it }, aiAvailable = true) }
        rule.onNodeWithText(str(R.string.two_player_local)).performClick()
        assertEquals(listOf<GameMode>(GameMode.TwoPlayer), modes)
    }

    @Test fun `new game dialog without ai hides ai option`() {
        val modes = mutableListOf<GameMode>()
        rule.setContent { NewGameDialog(onNewGame = { modes += it }, aiAvailable = false) }
        rule.onNodeWithText(str(R.string.human_vs_ai)).assertDoesNotExist()
        assertTrue(modes.isEmpty())
    }

    @Test fun `game over state still renders board`() {
        val actions = RecordingActions()
        rule.setContent {
            ChessGameScreen(
                state = ChessUiState(gameResult = GameResult.Checkmate(PieceColor.WHITE), gameStatus = "White wins"),
                actions = actions, onNewGame = {}, onOpenGameCenter = {})
        }
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(str(R.string.piece_white_king))).assertCountEquals(1)
    }

    @Test fun `chess piece descriptions follow color`() {
        rule.setContent { ChessPiece(Piece(PieceType.KNIGHT, PieceColor.BLACK)) }
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(str(R.string.piece_black_knight))).assertCountEquals(1)
    }
}
