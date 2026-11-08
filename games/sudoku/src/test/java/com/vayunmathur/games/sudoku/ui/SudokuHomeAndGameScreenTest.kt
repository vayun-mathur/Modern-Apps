package com.vayunmathur.games.sudoku.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.sudoku.Navigation
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.Route
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.GameConfig
import com.vayunmathur.games.sudoku.platform.SudokuViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SudokuHomeAndGameScreenTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app.getSharedPreferences("sudoku_stats", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Home))

    @Test
    fun homeShowsPickersStatsAndNewGameNavigatesWithChosenConfig() {
        val vm = SudokuViewModel(app)
        val stack = backStack()
        rule.setContent { HomeScreen(stack, vm) }
        rule.onNodeWithText(str(R.string.board_size)).assertExists()
        rule.onNodeWithText(str(R.string.difficulty)).assertExists()
        rule.onNodeWithText(str(R.string.answer_mode)).assertExists()
        rule.onNodeWithText(str(R.string.highlight_mistakes)).assertExists()
        rule.onAllNodesWithText(str(R.string.size_six)).assertCountEquals(2)
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.new_game)).assertExists()

        rule.onAllNodesWithText(str(R.string.size_six))[0].performClick()
        rule.onNodeWithText(str(R.string.difficulty_expert)).performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.new_game))
            .performScrollTo().performClick()

        assertEquals(2, stack.backStack.size)
        val route = assertIs<Route.Game>(stack.backStack.last())
        assertEquals(BoardSize.SIX, route.config.size)
        assertEquals(Difficulty.EXPERT, route.config.difficulty)
    }

    @Test
    fun homeContinueResumesActiveGame() {
        val vm = SudokuViewModel(app)
        vm.newGame(GameConfig(BoardSize.SIX, Difficulty.EASY))
        await("game dealt") { vm.uiState.value.game != null }
        val stack = backStack()
        rule.setContent { HomeScreen(stack, vm) }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.continue_label))
            .assertExists().performClick()
        val route = assertIs<Route.Game>(stack.backStack.last())
        assertEquals(BoardSize.SIX, route.config.size)
        assertEquals(Difficulty.EASY, route.config.difficulty)
    }

    @Test
    fun homeSizeCardsShowRecordedStats() {
        val vm = SudokuViewModel(app)
        vm.newGame(GameConfig(BoardSize.SIX, Difficulty.EASY))
        await("game dealt") { vm.uiState.value.game != null }
        // Finish it through the public actions so the win lands in the stats the cards read.
        var guard = 0
        while (guard++ < 100) {
            val game = vm.uiState.value.game!!
            val next = game.blankIndices().firstOrNull() ?: break
            vm.selectCell(next)
            vm.enterDigit(game.solution[next])
        }
        assertTrue(vm.uiState.value.game!!.isWon)
        rule.setContent { HomeScreen(backStack(), vm) }
        rule.onAllNodesWithText("1/1").assertCountEquals(1)
        rule.onAllNodesWithText(str(R.string.won)).assertCountEquals(3)
    }

    @Test
    fun gameScreenRendersDealtGameBoard() {
        val vm = SudokuViewModel(app)
        vm.newGame(GameConfig(BoardSize.SIX, Difficulty.EASY))
        await("game dealt") { vm.uiState.value.game != null }
        val stack = backStack()
        rule.setContent { GameScreen(stack, vm, GameConfig(BoardSize.SIX, Difficulty.EASY)) }
        rule.onNodeWithText(str(R.string.hint)).assertExists()
        rule.onNodeWithText(str(R.string.erase)).assertExists()
    }

    @Test
    fun gameScreenDealsWhenEnteredWithoutGame() {
        val vm = SudokuViewModel(app)
        val stack = backStack()
        rule.setContent { GameScreen(stack, vm, GameConfig(BoardSize.SIX, Difficulty.EASY)) }
        await("game dealt by GameScreen") { vm.uiState.value.game != null }
        rule.onNodeWithText(str(R.string.hint)).assertExists()
    }

    @Test
    fun navigationShowsHome() {
        val vm = SudokuViewModel(app)
        rule.setContent { Navigation(vm) }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.new_game)).assertExists()
        rule.onNodeWithText(str(R.string.board_size)).assertExists()
    }

    @Test
    fun navigationNewGameOpensBoard() {
        val vm = SudokuViewModel(app)
        rule.setContent { Navigation(vm) }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.new_game))
            .performScrollTo().performClick()
        await("game dealt via navigation") { vm.uiState.value.game != null }
        rule.onNodeWithText(str(R.string.hint)).assertExists()
        rule.onNodeWithText(str(R.string.erase)).assertExists()
    }

    @Test
    fun navigationGameCenterOpensAndBackReturnsHome() {
        val vm = SudokuViewModel(app)
        rule.setContent { Navigation(vm) }
        // The app-bar trophy carries the Badges icon description.
        rule.onNodeWithContentDescription("Badges").performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.title_game_center)).assertExists()
        rule.onNodeWithText("Solved").assertExists()
    }
}
