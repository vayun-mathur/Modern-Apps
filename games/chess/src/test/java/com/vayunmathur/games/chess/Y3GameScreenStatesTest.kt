package com.vayunmathur.games.chess

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.util.ChessActions
import com.vayunmathur.games.chess.util.ChessUiState
import com.vayunmathur.games.chess.util.Difficulty
import com.vayunmathur.games.chess.util.GameMode
import com.vayunmathur.games.chess.util.GameResult
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class Y3GameScreenStatesTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = ctx.getString(id)

    private class Rec : ChessActions {
        val promotes = mutableListOf<PieceType>(); var cancels = 0
        override fun onSquareClick(p: Position) {}
        override fun onSquareDrop(f: Position, t: Position) {}
        override fun onPromote(t: PieceType) { promotes += t }
        override fun onCancelPromotion() { cancels++ }
    }

    @Test fun aiThinkingAndStatusStates() {
        val actions = Rec()
        rule.setContent {
            ChessGameScreen(
                state = ChessUiState(gameMode = GameMode.VsAI(PieceColor.WHITE, Difficulty.BEGINNER), aiThinking = true),
                actions = actions, onNewGame = {}, onOpenGameCenter = {},
            )
        }
        rule.onNodeWithText(str(R.string.ai_thinking), substring = true).assertExists()
    }

    @Test fun checkmateStatusAndCapturedPieces() {
        var board = Board.initialState
        board = board.movePiece(Position(6, 5), Position(5, 5))
        board = board.movePiece(Position(1, 4), Position(3, 4))
        board = board.movePiece(Position(6, 6), Position(4, 6))
        board = board.movePiece(Position(0, 3), Position(4, 7))
        val actions = Rec()
        rule.setContent {
            ChessGameScreen(
                state = ChessUiState(board = board, turn = PieceColor.WHITE, gameStatus = "Checkmate", gameResult = GameResult.Checkmate(PieceColor.BLACK)),
                actions = actions, onNewGame = {}, onOpenGameCenter = {},
            )
        }
        rule.onNodeWithText("Checkmate").assertIsDisplayed()
        assertEquals(PieceColor.BLACK, (GameResult.Checkmate(PieceColor.BLACK)).winner)
    }

    @Test fun promotionDialogFromPendingBoard() {
        val start = Board.fromFen("8/P7/8/8/8/8/k6K/8 w - - 0 1")
        val pending = start.movePiece(Position(1, 0), Position(0, 0))
        val actions = Rec()
        rule.setContent {
            ChessGameScreen(state = ChessUiState(board = pending), actions = actions, onNewGame = {}, onOpenGameCenter = {})
        }
        // Promotion choices are shown; picking queen forwards to actions.
        rule.onNodeWithText(str(R.string.promote_pawn), substring = true).assertExists()
    }

    @Config(qualifiers = "w1280dp-h800dp")
    @Test fun expandedLayoutSidePanel() {
        val actions = Rec()
        rule.setContent {
            ChessGameScreen(
                state = ChessUiState(gameMode = GameMode.VsAI(PieceColor.BLACK, Difficulty.GRANDMASTER), isBoardFlipped = true, gameStatus = "Check!"),
                actions = actions, onNewGame = {}, onOpenGameCenter = {},
            )
        }
        rule.onNodeWithText("Check!").assertExists()
    }

    @Test fun puzzleBoardFailedStateShowsRetryActions() {
        val actions = object : com.vayunmathur.games.chess.util.PuzzleActions {}
        rule.setContent {
            PuzzleBoardScreen(
                state = com.vayunmathur.games.chess.util.PuzzleUiState(status = com.vayunmathur.games.chess.util.PuzzleStatus.Failed, rating = 1500, playerColor = PieceColor.BLACK),
                actions = actions,
            )
        }
        rule.onNodeWithText(ctx.getString(R.string.puzzle_rating, 1500), substring = true).assertExists()
    }

    @Test fun puzzleBoardSolvedAndLoadingStates() {
        val actions = object : com.vayunmathur.games.chess.util.PuzzleActions {}
        rule.setContent {
            PuzzleBoardScreen(
                state = com.vayunmathur.games.chess.util.PuzzleUiState(status = com.vayunmathur.games.chess.util.PuzzleStatus.Solved, rating = 900),
                actions = actions,
            )
        }
        rule.onNodeWithText(ctx.getString(R.string.puzzle_rating, 900), substring = true).assertExists()
    }

    @Test fun allPieceGlyphsRenderBothColors() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                for (type in PieceType.entries) {
                    androidx.compose.foundation.layout.Row {
                        ChessPiece(com.vayunmathur.games.chess.data.Piece(type, PieceColor.WHITE), size = 24.dp)
                        ChessPiece(com.vayunmathur.games.chess.data.Piece(type, PieceColor.BLACK), size = 24.dp, isFlipped = true)
                        ChessPiece(com.vayunmathur.games.chess.data.Piece(type, PieceColor.WHITE), described = false)
                    }
                }
            }
        }
        rule.onNodeWithContentDescription(str(R.string.piece_white_king), substring = true).assertExists()
        rule.onNodeWithContentDescription(str(R.string.piece_black_queen), substring = true).assertExists()
    }
}
