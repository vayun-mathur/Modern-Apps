package com.vayunmathur.games.chess

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        val promotes = mutableListOf<PieceType>()
        override fun onSquareClick(position: Position) { clicks += position }
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
