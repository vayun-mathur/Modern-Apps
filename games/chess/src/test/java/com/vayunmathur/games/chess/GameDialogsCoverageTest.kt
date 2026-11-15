package com.vayunmathur.games.chess

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.util.ChessActions
import com.vayunmathur.games.chess.util.ChessUiState
import com.vayunmathur.games.chess.util.Difficulty
import com.vayunmathur.games.chess.util.GameMode
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** New-game dialog flows: AI settings pickers and their defaults. */
@RunWith(RobolectricTestRunner::class)
class GameDialogsCoverageTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) =
        if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    @Test fun `ai settings flow carries the picked colour and difficulty`() {
        val modes = mutableListOf<GameMode>()
        rule.setContent { NewGameDialog(onNewGame = { modes += it }, aiAvailable = true) }
        rule.onNodeWithText(str(R.string.human_vs_ai)).performClick()
        rule.onNodeWithText(str(R.string.play_as), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.color_black)).performClick()
        rule.onNodeWithText(str(R.string.difficulty_master)).performClick()
        rule.onNodeWithText(str(R.string.start_game)).performClick()
        assertEquals(listOf<GameMode>(GameMode.VsAI(PieceColor.BLACK, Difficulty.GRANDMASTER)), modes)
    }

    @Test fun `ai settings start with white and intermediate by default`() {
        val modes = mutableListOf<GameMode>()
        rule.setContent { NewGameDialog(onNewGame = { modes += it }, aiAvailable = true) }
        rule.onNodeWithText(str(R.string.human_vs_ai)).performClick()
        rule.onNodeWithText(str(R.string.start_game)).performClick()
        assertEquals(listOf<GameMode>(GameMode.VsAI(PieceColor.WHITE, Difficulty.INTERMEDIATE)), modes)
    }
}

/** The expanded (wide-window) game layout with its side panel. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w1280dp-h800dp")
class ExpandedGameLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) =
        if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    @Test fun `expanded layout shows the side panel with moves captures and status`() {
        var board = com.vayunmathur.games.chess.data.Board.initialState
        board = board.movePiece(sq('e', 2), sq('e', 4))
        board = board.movePiece(sq('d', 7), sq('d', 5))
        board = board.movePiece(sq('e', 4), sq('d', 5)) // exd5: a capture for the side panel
        var newGame = 0
        rule.setContent {
            ChessGameScreen(
                state = ChessUiState(board = board, turn = PieceColor.BLACK, gameStatus = "Check"),
                actions = ChessActions.Noop,
                onNewGame = { newGame++ },
                onOpenGameCenter = {},
            )
        }
        rule.onNodeWithText("exd5").assertExists()
        rule.onNodeWithText("Check").assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertEquals(1, newGame)
    }
}
