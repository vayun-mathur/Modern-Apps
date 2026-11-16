package com.vayunmathur.games.minesweeper.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.Route
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.platform.MinesweeperViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home: stat cards per size, Continue only with a live field, config dialog hand-off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = app.getString(id)
    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Home))

    @BeforeTest
    fun clearStats() {
        app.getSharedPreferences("minesweeper_stats", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun homeShowsNewGameAndEmptyStatCardsWithoutContinue() {
        val vm = MinesweeperViewModel(app)
        rule.setContent { HomeScreen(backStack(), vm) }

        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.new_game)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.continue_label))
            .assertDoesNotExist()
        rule.onAllNodesWithText("0/0").assertCountEquals(3)
        rule.onAllNodesWithText(str(R.string.cleared_count)).assertCountEquals(3)
        rule.onNodeWithText(str(R.string.size_small)).assertExists()
        rule.onNodeWithText(str(R.string.size_large)).assertExists()
    }

    @Test
    fun continueResumesTheActiveGameWithItsConfig() {
        val vm = MinesweeperViewModel(app)
        vm.newGame(GameConfig(BoardSize.LARGE, Difficulty.HARD))
        val stack = backStack()
        rule.setContent { HomeScreen(stack, vm) }

        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.continue_label))
            .assertExists().performClick()
        assertEquals(2, stack.backStack.size)
        val route = assertIs<Route.Game>(stack.backStack.last())
        assertEquals(GameConfig(BoardSize.LARGE, Difficulty.HARD), route.config)
    }

    @Test
    fun newGameOpensThePickerAndDismissingItDealsNothing() {
        val vm = MinesweeperViewModel(app)
        val stack = backStack()
        rule.setContent { HomeScreen(stack, vm) }

        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        rule.onNodeWithText(str(R.string.field_size)).assertExists()
        rule.onNodeWithText("21 / 140").assertExists()
        assertEquals(1, stack.backStack.size)
        assertNull(vm.uiState.value.game)

        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.back)).performClick()
        rule.onNodeWithText(str(R.string.field_size)).assertDoesNotExist()
        assertEquals(1, stack.backStack.size)
        assertNull(vm.uiState.value.game)
    }

    @Test
    fun startingFromThePickerDealsTheChosenFieldAndNavigates() {
        val vm = MinesweeperViewModel(app)
        val stack = backStack()
        rule.setContent { HomeScreen(stack, vm) }

        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        // "Large" also labels a home stat card; the picker's segment is the clickable one.
        rule.onNode(hasText(str(R.string.size_large)) and hasClickAction()).performClick()
        rule.onNodeWithText(str(R.string.difficulty_expert)).performClick()
        rule.onNodeWithText("50 / 216").assertExists()
        // The picker is a dialog over the home button of the same label; the
        // dialog's start button is the last matching node in the tree.
        val startButtons = rule.onAllNodesWithText(str(com.vayunmathur.library.ui.R.string.new_game))
        startButtons[startButtons.fetchSemanticsNodes().lastIndex].performClick()

        assertEquals(2, stack.backStack.size)
        val route = assertIs<Route.Game>(stack.backStack.last())
        assertEquals(GameConfig(BoardSize.LARGE, Difficulty.EXPERT), route.config)
        val game = vm.uiState.value.game
        assertEquals(216, game?.cellCount)
        assertEquals(GameOutcome.PLAYING, game?.outcome)
    }

    @Test
    fun statCardsReflectAFieldClearedThroughTheViewModel() {
        val vm = MinesweeperViewModel(app)
        vm.newGame(GameConfig(BoardSize.SMALL, Difficulty.EASY))
        vm.tapCell(0)
        var guard = 0
        while (vm.uiState.value.game!!.outcome == GameOutcome.PLAYING && guard++ < 500) {
            val game = vm.uiState.value.game!!
            val next = game.revealed.indices.first { !game.revealed[it] && !game.mines[it] }
            vm.tapCell(next)
        }
        assertEquals(GameOutcome.WON, vm.uiState.value.game!!.outcome)

        rule.setContent { HomeScreen(backStack(), vm) }
        // SMALL was played once and cleared once; the other sizes stay at 0/0.
        rule.onAllNodesWithText("1/1").assertCountEquals(1)
        rule.onAllNodesWithText("0/0").assertCountEquals(2)
    }
}
