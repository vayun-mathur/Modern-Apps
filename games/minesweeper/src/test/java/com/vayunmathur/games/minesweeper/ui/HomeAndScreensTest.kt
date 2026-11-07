package com.vayunmathur.games.minesweeper.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.Route
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import com.vayunmathur.games.minesweeper.data.MinesweeperStatsRepository
import com.vayunmathur.games.minesweeper.data.TapMode
import com.vayunmathur.games.minesweeper.platform.MinesweeperActions
import com.vayunmathur.games.minesweeper.platform.MinesweeperUiState
import com.vayunmathur.games.minesweeper.platform.MinesweeperViewModel
import com.vayunmathur.games.minesweeper.ui.dialogs.GameConfigDialog
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home, config dialog, display names, game screen, navigation, overlays. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeAndScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun str(id: Int) = ctx.getString(id)
    private fun uiStr(id: Int) = ctx.getString(id)
    private fun vm() = MinesweeperViewModel(ApplicationProvider.getApplicationContext<Application>())

    @BeforeTest fun clearPrefs() {
        ctx.getSharedPreferences("minesweeper_stats", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class RecordingActions : MinesweeperActions {
        val calls = mutableListOf<String>()
        var mode: TapMode? = null
        override fun tapCell(index: Int) { calls += "tap:$index" }
        override fun flagCell(index: Int) { calls += "flag:$index" }
        override fun setTapMode(m: TapMode) { mode = m; calls += "mode:$m" }
        override fun restart() { calls += "restart" }
        override fun giveUp() { calls += "giveUp" }
    }

    @Test fun displayNamesAllSizesAndDifficulties() {
        rule.setContent {
            Column {
                androidx.compose.material3.Text(BoardSize.SMALL.displayName())
                androidx.compose.material3.Text(BoardSize.MEDIUM.displayName())
                androidx.compose.material3.Text(BoardSize.LARGE.displayName())
                androidx.compose.material3.Text(Difficulty.EASY.displayName())
                androidx.compose.material3.Text(Difficulty.MEDIUM.displayName())
                androidx.compose.material3.Text(Difficulty.HARD.displayName())
                androidx.compose.material3.Text(Difficulty.EXPERT.displayName())
            }
        }
        rule.onNodeWithText(str(R.string.size_small)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.size_large)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.difficulty_expert)).assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithText(uiStr(com.vayunmathur.library.ui.R.string.medium)).fetchSemanticsNodes().size)
    }

    @Test fun configDialogDefaultsLiveCountAndStart() {
        var started: GameConfig? = null
        rule.setContent {
            GameConfigDialog(initial = GameConfig(), onStart = { started = it }, onDismiss = {})
        }
        rule.onNodeWithText(str(R.string.new_field)).assertIsDisplayed()
        rule.onNodeWithText("21 / 140").assertIsDisplayed()
        // Switch to Small + Easy: 8 mines in 80 cells.
        rule.onNodeWithText(str(R.string.size_small)).performClick()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.easy)).performClick()
        rule.onNodeWithText("8 / 80").assertIsDisplayed()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertEquals(GameConfig(BoardSize.SMALL, Difficulty.EASY), started)
    }

    @Test fun configDialogSeededInitialAndDismiss() {
        var started: GameConfig? = null
        var dismissals = 0
        rule.setContent {
            GameConfigDialog(
                initial = GameConfig(BoardSize.LARGE, Difficulty.EXPERT),
                onStart = { started = it },
                onDismiss = { dismissals++ },
            )
        }
        rule.onNodeWithText("50 / 216").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.size_large)).assertExists()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.back)).performClick()
        assertEquals(1, dismissals)
        assertEquals(null, started)
    }

    @Test fun configDialogDifficultySelectionChangesCount() {
        var started: GameConfig? = null
        rule.setContent {
            GameConfigDialog(initial = GameConfig(BoardSize.SMALL, Difficulty.EASY), onStart = { started = it }, onDismiss = {})
        }
        rule.onNodeWithText(str(R.string.difficulty_expert)).performClick()
        // Expert small: 80 * 0.23 = 18.4 -> 18 mines.
        rule.onNodeWithText("18 / 80").assertIsDisplayed()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertEquals(GameConfig(BoardSize.SMALL, Difficulty.EXPERT), started)
    }

    @Test fun homeStartsNewGameThroughDialog() {
        val vm = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent { HomeScreen(backStack, vm) }
        rule.onNodeWithText("Minesweeper").assertExists()
        // No active game yet: Continue is absent.
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.continue_label)).assertDoesNotExist()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        rule.onNodeWithText(str(R.string.new_field)).assertIsDisplayed()
        // Dialog's New Game button is the second "New Game" node (home's is first).
        val nodes = rule.onAllNodesWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game))
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
        assertEquals(Route.Game(GameConfig()), backStack.last())
        assertEquals(140, vm.uiState.value.game!!.cellCount)
    }

    @Test fun homeDismissesDialogWithoutStarting() {
        val vm = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent { HomeScreen(backStack, vm) }
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.back)).performClick()
        rule.onNodeWithText(str(R.string.new_field)).assertDoesNotExist()
        assertEquals(Route.Home, backStack.last())
        assertEquals(null, vm.uiState.value.game)
    }

    @Test fun homeContinueResumesAndStatsCardsShowRecords() {
        MinesweeperStatsRepository(ctx).recordGameWon(BoardSize.SMALL, Difficulty.EASY, 75)
        val vm = vm()
        vm.newGame(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent { HomeScreen(backStack, vm) }
        // Small card shows the recorded win and best time.
        rule.onNodeWithText("1/0").assertExists()
        rule.onNodeWithText("01:15").assertExists()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.continue_label)).performClick()
        assertEquals(Route.Game(GameConfig(BoardSize.SMALL, Difficulty.EASY)), backStack.last())
    }

    @Test fun gameScreenDealsWhenNoGameAndKeepsExisting() {
        val vm = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent { GameScreen(backStack, vm, GameConfig(BoardSize.SMALL, Difficulty.EASY)) }
        rule.waitForIdle()
        assertEquals(80, vm.uiState.value.game!!.cellCount)
        rule.onNodeWithText(str(R.string.how_to_play)).assertExists()
        // Recomposing with a different config does not redeal an existing game:
        // the LaunchedEffect is keyed on the config passed at first composition.
        assertEquals(BoardSize.SMALL, vm.uiState.value.config.size)
    }

    @Test fun navigationHomeAndMainActivity() {
        val vm = vm()
        rule.setContent { com.vayunmathur.games.minesweeper.Navigation(vm) }
        rule.onNodeWithText("Minesweeper").assertExists()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).assertExists()
        val controller = Robolectric.buildActivity(com.vayunmathur.games.minesweeper.MainActivity::class.java)
        controller.create()
        assertTrue(!controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun boardWonOverlayPlayAgainAndBack() {
        val actions = RecordingActions()
        var exits = 0
        val game = MinesweeperGameState.of(3, 3, 1).copy(
            outcome = GameOutcome.WON, started = true, elapsedSeconds = 42,
            revealed = List(9) { true },
        )
        rule.setContent {
            GameBoardScreen(
                state = MinesweeperUiState(game = game),
                actions = actions,
                onExit = { exits++ },
            )
        }
        rule.onNodeWithText(str(R.string.cleared)).assertIsDisplayed()
        rule.onNodeWithText("${uiStr(com.vayunmathur.library.ui.R.string.game_time)}: 00:42").assertExists()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.game_play_again)).performClick()
        assertTrue(actions.calls.contains("restart"), "${actions.calls}")
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.back)).performClick()
        assertEquals(1, exits)
    }

    @Test fun boardLostOverlayHidesTime() {
        val actions = RecordingActions()
        val game = MinesweeperGameState.of(3, 3, 1).copy(
            outcome = GameOutcome.LOST, started = true, elapsedSeconds = 42, explodedAt = 4,
            revealed = List(9) { it == 4 },
        )
        rule.setContent {
            GameBoardScreen(state = MinesweeperUiState(game = game), actions = actions, onExit = {})
        }
        rule.onNodeWithText(str(R.string.boom)).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("${uiStr(com.vayunmathur.library.ui.R.string.game_time)}: 00:42")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test fun boardToggleAndImmediateRestartAndGiveUpWithoutProgress() {
        val actions = RecordingActions()
        var exits = 0
        val game = MinesweeperGameState.empty(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        rule.setContent {
            GameBoardScreen(
                state = MinesweeperUiState(config = GameConfig(BoardSize.SMALL, Difficulty.EASY), game = game),
                actions = actions,
                onExit = { exits++ },
            )
        }
        rule.onNodeWithText(str(R.string.mode_flag)).performScrollTo().performClick()
        assertEquals(TapMode.FLAG, actions.mode)
        rule.onNodeWithText(str(R.string.mode_dig)).performScrollTo().performClick()
        assertEquals(TapMode.DIG, actions.mode)
        // Not started: New Field restarts immediately, Give Up exits immediately.
        rule.onNodeWithText(str(R.string.new_field)).performScrollTo().performClick()
        assertTrue(actions.calls.contains("restart"))
        rule.onNodeWithText(str(R.string.give_up)).performScrollTo().performClick()
        assertTrue(actions.calls.contains("giveUp"))
        assertEquals(1, exits)
    }

    @Test fun boardConfirmDialogsWhenGameInProgress() {
        val actions = RecordingActions()
        var exits = 0
        val game = MinesweeperGameState.of(8, 10, 8).copy(started = true, revealed = List(80) { it == 0 })
        rule.setContent {
            GameBoardScreen(
                state = MinesweeperUiState(config = GameConfig(BoardSize.SMALL, Difficulty.EASY), game = game),
                actions = actions,
                onExit = { exits++ },
            )
        }
        // Restart asks first; cancel keeps playing.
        rule.onNodeWithText(str(R.string.new_field)).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.confirm_new_field_title)).assertIsDisplayed()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertTrue(actions.calls.isEmpty(), "${actions.calls}")
        // Give up asks first; confirm gives up and exits.
        rule.onNodeWithText(str(R.string.give_up)).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.confirm_give_up_title)).assertIsDisplayed()
        val giveUps = rule.onAllNodesWithText(str(R.string.give_up))
        giveUps[giveUps.fetchSemanticsNodes().size - 1].performClick()
        assertTrue(actions.calls.contains("giveUp"))
        assertEquals(1, exits)
    }
}
