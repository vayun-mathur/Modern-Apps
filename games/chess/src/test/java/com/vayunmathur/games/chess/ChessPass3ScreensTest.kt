package com.vayunmathur.games.chess

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.util.ChessAchievementsManager
import com.vayunmathur.games.chess.util.ChessUiState
import com.vayunmathur.games.chess.util.ChessViewModel
import com.vayunmathur.games.chess.util.Difficulty
import com.vayunmathur.games.chess.util.GameMode
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pass 3: NewGameDialog flow, ChessGame achievements wiring, layout variants. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ChessPass3ScreensTest {

    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx: Context get() = app
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        runBlocking { DataStoreUtils.getInstance(app).removeKeys(listOf("chess_wins_count")) }
    }

    @AfterTest fun tearDown() {
        runBlocking { DataStoreUtils.getInstance(app).removeKeys(listOf("chess_wins_count")) }
        Dispatchers.resetMain()
    }

    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    private fun manager(): ChessAchievementsManager {
        val json = app.assets.open("achievements.json").bufferedReader().readText()
        return ChessAchievementsManager(app, json)
    }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            // Pump the main looper so composition LaunchedEffects can run.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.waitForIdle()
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    // ---- NewGameDialog ----

    @Test fun newGameDialogTwoPlayerStartsImmediately() {
        var mode: GameMode? = null
        rule.setContent { NewGameDialog(onNewGame = { mode = it }) }
        rule.onNodeWithText(str(R.string.two_player_local)).performClick()
        assertEquals(GameMode.TwoPlayer, mode)
    }

    @Test fun newGameDialogAiSettingsPickColorAndDifficulty() {
        var mode: GameMode? = null
        rule.setContent { NewGameDialog(onNewGame = { mode = it }) }
        rule.onNodeWithText(str(R.string.human_vs_ai)).performClick()
        rule.onNodeWithText(str(R.string.color_black)).performClick()
        rule.onNodeWithText(str(UiR.string.easy)).performClick()
        rule.onNodeWithText(str(R.string.start_game)).performClick()
        assertEquals(GameMode.VsAI(PieceColor.BLACK, Difficulty.BEGINNER), mode)
    }

    @Test fun newGameDialogMasterDifficultyAndWhiteDefault() {
        var mode: GameMode? = null
        rule.setContent { NewGameDialog(onNewGame = { mode = it }) }
        rule.onNodeWithText(str(R.string.human_vs_ai)).performClick()
        rule.onNodeWithText(str(R.string.difficulty_master)).performClick()
        rule.onNodeWithText(str(R.string.start_game)).performClick()
        assertEquals(GameMode.VsAI(PieceColor.WHITE, Difficulty.GRANDMASTER), mode)
    }

    @Test fun newGameDialogWithoutAiHidesAiOption() {
        rule.setContent { NewGameDialog(onNewGame = {}, aiAvailable = false) }
        rule.onNodeWithText(str(R.string.two_player_local)).assertIsDisplayed()
        rule.onAllNodesWithText(str(R.string.human_vs_ai)).fetchSemanticsNodes().let {
            assertEquals(0, it.size, "AI option hidden when the model is unavailable")
        }
    }

    // ---- ChessGame achievements wiring ----

    @Test fun chessGameFoolsMateRecordsWinOnce() {
        val vm = ChessViewModel(app)
        val mgr = manager()
        var newGames = 0
        rule.setContent {
            ChessGame(viewModel = vm, onNewGame = { newGames++ }, onOpenGameCenter = {}, achievementsManager = mgr)
        }
        fun move(f1: Char, r1: Int, f2: Char, r2: Int) {
            vm.onSquareClick(sq(f1, r1)); vm.onSquareClick(sq(f2, r2))
        }
        // Fool's mate: 1. f3 e5 2. g4 Qh4#
        move('f', 2, 'f', 3); move('e', 7, 'e', 5); move('g', 2, 'g', 4); move('d', 8, 'h', 4)
        assertTrue(vm.uiState.value.gameResult != null, "fool's mate ends the game")
        await("win recorded") {
            DataStoreUtils.getInstance(app).getLong("chess_wins_count")?.let { it >= 1 } == true
        }
        await("first_mate unlocked") {
            runBlocking {
                mgr.getAchievementStatuses().first().any { it.achievement.id == "first_mate" && it.isUnlocked }
            }
        }
        assertEquals(false, vm.claimWinScoring(), "win scoring is claimed exactly once")
        assertEquals(0, newGames)
    }

    @Test fun chessGameCastlingUnlocksAchievement() {
        val vm = ChessViewModel(app)
        val mgr = manager()
        rule.setContent {
            ChessGame(viewModel = vm, onNewGame = {}, onOpenGameCenter = {}, achievementsManager = mgr)
        }
        fun move(f1: Char, r1: Int, f2: Char, r2: Int) {
            vm.onSquareClick(sq(f1, r1)); vm.onSquareClick(sq(f2, r2))
        }
        // 1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. O-O
        move('e', 2, 'e', 4); move('e', 7, 'e', 5)
        move('g', 1, 'f', 3); move('b', 8, 'c', 6)
        move('f', 1, 'c', 4); move('f', 8, 'c', 5)
        move('e', 1, 'g', 1)
        assertTrue(vm.uiState.value.board.moves.lastOrNull()?.isCastling == true, "last move castles")
        await("castled unlocked") {
            runBlocking {
                mgr.getAchievementStatuses().first().any { it.achievement.id == "castled" && it.isUnlocked }
            }
        }
    }

    // ---- Layout variants ----

    @Test fun gameScreenPortraitMidgameState() {
        val vm = ChessViewModel(app)
        vm.onSquareClick(sq('e', 2)); vm.onSquareClick(sq('e', 4))
        vm.onSquareClick(sq('e', 7)); vm.onSquareClick(sq('e', 5))
        rule.setContent {
            ChessGameScreen(
                state = vm.uiState.value.copy(gameStatus = "White to move"),
                actions = vm,
                onNewGame = {},
                onOpenGameCenter = {},
            )
        }
        rule.onNodeWithText("White to move").assertExists()
        rule.onNodeWithText("e4", substring = true).assertExists()
    }

    @Config(qualifiers = "w800dp-h400dp-land")
    @Test fun gameScreenLandscapeLayout() {
        rule.setContent {
            ChessGameScreen(
                state = ChessUiState(gameStatus = "Landscape game"),
                actions = com.vayunmathur.games.chess.util.ChessActions.Noop,
                onNewGame = {},
                onOpenGameCenter = {},
            )
        }
        rule.onNodeWithText("Landscape game").assertExists()
    }

    @Config(qualifiers = "w1200dp-h800dp")
    @Test fun gameScreenExpandedLayout() {
        var newGames = 0
        rule.setContent {
            ChessGameScreen(
                state = ChessUiState(gameStatus = "Expanded game"),
                actions = com.vayunmathur.games.chess.util.ChessActions.Noop,
                onNewGame = { newGames++ },
                onOpenGameCenter = {},
            )
        }
        rule.onNodeWithText("Expanded game").assertExists()
        rule.onNodeWithText(str(UiR.string.new_game)).performClick()
        assertEquals(1, newGames)
    }
}
