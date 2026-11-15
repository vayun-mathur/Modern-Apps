package com.vayunmathur.games.chess

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.LearnLevel
import com.vayunmathur.games.chess.data.LearnShape
import com.vayunmathur.games.chess.data.LearnStage
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.network.LichessTimeControl
import com.vayunmathur.games.chess.network.UciMove
import com.vayunmathur.games.chess.platform.OnlineAuth
import com.vayunmathur.games.chess.platform.OnlineChessUiState
import com.vayunmathur.games.chess.platform.OnlineConnection
import com.vayunmathur.games.chess.util.LearnUiState
import com.vayunmathur.games.chess.util.PuzzleActions
import com.vayunmathur.games.chess.util.PuzzleDifficulty
import com.vayunmathur.games.chess.util.PuzzleStatus
import com.vayunmathur.games.chess.util.PuzzleUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stateful-screen variants: online lobby/game, puzzle statuses, learn dialogs and board. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w800dp-h1280dp")
class ScreensCoverageTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) =
        if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    private class RecordingPuzzleActions : PuzzleActions {
        val calls = mutableListOf<String>()
        override fun onSquareClick(position: Position) { calls += "click" }
        override fun onPromote(pieceType: PieceType) { calls += "promote:$pieceType" }
        override fun onCancelPromotion() { calls += "cancelPromotion" }
        override fun loadRandom(difficulty: PuzzleDifficulty) { calls += "load:$difficulty" }
        override fun retry() { calls += "retry" }
        override fun showSolution() { calls += "showSolution" }
    }

    // -- Online ---------------------------------------------------------------

    @Test fun `online lobby shows signing in then challenging progress`() {
        var state by mutableStateOf(OnlineChessUiState(auth = OnlineAuth.SigningIn))
        rule.setContent { ChessOnlineScreen(state) }
        rule.onNodeWithText(str(R.string.online_signing_in)).assertExists()
        state = OnlineChessUiState(
            auth = OnlineAuth.SignedIn("ada"),
            connection = OnlineConnection.Challenging("bob"),
            challengeUsername = "bob",
        )
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.online_challenge_sent, "bob"), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.online_signed_in_as, "ada")).assertExists()
    }

    @Test fun `online lobby signed in shows time controls and the challenge field`() {
        rule.setContent {
            ChessOnlineScreen(
                OnlineChessUiState(
                    auth = OnlineAuth.SignedIn("ada"),
                    selectedTimeControl = LichessTimeControl.RAPID_10_0,
                )
            )
        }
        for (label in listOf("1+0", "3+2", "5+3", "10+0")) {
            rule.onNodeWithText(label).assertExists()
        }
        rule.onNodeWithText(str(R.string.online_sign_out)).assertExists()
        rule.onNodeWithText(str(R.string.online_challenge_username)).assertExists()
    }

    @Test fun `online game shows clocks reconnecting and the abort bar until the first move`() {
        val playing = OnlineConnection.Playing(
            gameId = "g1", localColor = PieceColor.WHITE, opponentName = "bob",
            whiteClockMs = 180_000, blackClockMs = 65_000, reconnecting = true,
        )
        var connection by mutableStateOf<OnlineConnection>(playing)
        rule.setContent {
            ChessOnlineScreen(OnlineChessUiState(auth = OnlineAuth.SignedIn("ada"), connection = connection))
        }
        rule.onNodeWithText(str(R.string.online_reconnecting)).assertExists()
        rule.onNodeWithText("${str(R.string.color_white)} 3:00", substring = true).assertExists()
        rule.onNodeWithText("${str(R.string.color_black)} 1:05", substring = true).assertExists()
        rule.onNodeWithText(str(R.string.online_abort_confirm)).assertExists()
        rule.onNodeWithText(str(R.string.online_resign_confirm)).assertExists()
        connection = playing.copy(
            board = Board.initialState.movePiece(sq('e', 2), sq('e', 4)), reconnecting = false,
        )
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.online_abort_confirm)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.online_resign_confirm)).assertExists()
    }

    @Test fun `online game over shows the result and a staged promotion shows the picker`() {
        var connection by mutableStateOf<OnlineConnection>(
            OnlineConnection.Playing(
                gameId = "g1", localColor = PieceColor.BLACK, opponentName = "bob", resultText = "You win!",
            )
        )
        rule.setContent {
            ChessOnlineScreen(OnlineChessUiState(auth = OnlineAuth.SignedIn("ada"), connection = connection))
        }
        rule.onNodeWithText("You win!").assertExists()
        rule.onNodeWithText(str(R.string.online_back_to_lobby)).assertExists()
        val board = Board.fromFen("8/P7/8/8/8/8/k6K/8 w - - 0 1")
        connection = OnlineConnection.Playing(
            gameId = "g2", localColor = PieceColor.WHITE, board = board,
            pendingPromotion = UciMove(sq('a', 7), sq('a', 8)),
        )
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.promote_pawn)).assertExists()
    }

    // -- Puzzle ---------------------------------------------------------------

    @Test fun `puzzle screen renders every status and routes failed actions`() {
        val actions = RecordingPuzzleActions()
        var state by mutableStateOf(PuzzleUiState(status = PuzzleStatus.Loading, rating = 1234))
        rule.setContent { PuzzleBoardScreen(state, actions) }
        for ((status, text) in listOf(
            PuzzleStatus.Loading to str(R.string.puzzle_loading),
            PuzzleStatus.Solving to str(R.string.puzzle_your_move),
            PuzzleStatus.Solved to str(R.string.puzzle_solved),
            PuzzleStatus.ShowingSolution to str(R.string.puzzle_show_solution),
        )) {
            state = PuzzleUiState(status = status, rating = 1234)
            rule.waitForIdle()
            rule.onNodeWithText(text).assertExists()
            rule.onNodeWithText(str(R.string.puzzle_rating, 1234)).assertExists()
        }
        state = PuzzleUiState(status = PuzzleStatus.Failed, rating = 900)
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.puzzle_failed)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.retry)).performClick()
        rule.onNodeWithText(str(R.string.puzzle_show_solution)).performClick()
        rule.onNodeWithText(str(R.string.puzzle_next)).performClick()
        assertEquals(listOf("retry", "showSolution", "load:EASY"), actions.calls)
    }

    @Test fun `puzzle difficulty selector routes through actions`() {
        val actions = RecordingPuzzleActions()
        rule.setContent {
            PuzzleBoardScreen(
                PuzzleUiState(status = PuzzleStatus.Solving, difficulty = PuzzleDifficulty.EASY), actions)
        }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.hard)).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.easy)).assertExists()
        assertEquals(listOf("load:HARD"), actions.calls)
    }

    @Test fun `puzzle promotion dialog routes the picked piece through actions`() {
        val actions = RecordingPuzzleActions()
        val stepped = Board.fromFen("8/P7/8/8/8/8/k6K/8 w - - 0 1")
            .movePiece(sq('a', 7), sq('a', 8))
        rule.setContent {
            PuzzleBoardScreen(PuzzleUiState(board = stepped, status = PuzzleStatus.Solving), actions)
        }
        rule.onNodeWithText(str(R.string.promote_pawn)).assertExists()
        rule.onNode(
            hasClickAction() and androidx.compose.ui.test.hasContentDescription(
                str(R.string.piece_white_queen)
            )
        ).performClick()
        assertEquals(listOf("promote:QUEEN"), actions.calls)
    }

    // -- Learn ----------------------------------------------------------------

    private fun stage(levels: Int = 3) = LearnStage(
        id = 7, key = "rook", title = "Rook", subtitle = "The rook",
        intro = "Learn the rook.", complete = "Stage done.",
        levels = List(levels) {
            LearnLevel(goal = "Collect", fen = "8/8/8/8/8/8/4R3/8 w - - 0 1", goalType = "apples")
        },
    )

    @Test fun `learn stepper shows numbers and earned stars and routes selection`() {
        val selected = mutableListOf<Int>()
        rule.setContent {
            LevelStepper(count = 4, current = 1, stars = listOf(3, 0, 1, 0), onSelect = { selected += it })
        }
        rule.onNodeWithText("2").performClick()
        assertEquals(listOf(1), selected)
        rule.onAllNodesWithText("★", substring = true).assertCountEquals(2)
    }

    @Test fun `learn stage intro dialog renders and starts`() {
        var started = 0
        rule.setContent { StageIntroDialog(stage(3), onStart = { started++ }) }
        rule.onNodeWithText(str(R.string.learn_stage_number, 7, "Rook")).assertExists()
        rule.onNodeWithText("Learn the rook.").assertExists()
        rule.onNodeWithText(str(R.string.learn_lets_go)).performClick()
        assertEquals(1, started)
    }

    @Test fun `learn stage complete dialog shows score and next stage`() {
        var next = 0; var back = 0
        rule.setContent {
            StageCompleteDialog(
                stage = stage(2), stageStars = listOf(3, 3), score = 1250,
                nextTitle = "Bishop", onNext = { next++ }, onBackToMenu = { back++ },
            )
        }
        rule.onNodeWithText(str(R.string.learn_your_score, 1250)).assertExists()
        rule.onNodeWithText("Stage done.").assertExists()
        rule.onNodeWithText(str(R.string.learn_next_stage, "Bishop")).performClick()
        rule.onNodeWithText(str(R.string.learn_back_to_menu)).performClick()
        assertEquals(1, next)
        assertEquals(1, back)
    }

    @Test fun `learn board renders apples and shapes and forwards clicks`() {
        val clicks = mutableListOf<Position>()
        val ui = LearnUiState(
            board = Board.fromFen("8/8/8/8/8/8/4R3/8 w - - 0 1"),
            apples = setOf(sq('e', 7)),
            shapes = listOf(LearnShape("e2", "e7"), LearnShape("a1")),
            playerColor = PieceColor.WHITE,
        )
        rule.setContent { LearnBoard(ui, onSquareClick = { clicks += it }) }
        rule.onAllNodes(hasClickAction())[6 * 8 + 4].performClick() // e2
        assertEquals(listOf(sq('e', 2)), clicks)
        rule.onAllNodes(
            androidx.compose.ui.test.hasContentDescription(str(R.string.piece_white_rook), substring = true)
        ).assertCountEquals(1)
    }
}
