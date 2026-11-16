package com.vayunmathur.games.minesweeper.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.minesweeper.Navigation
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.Route
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.platform.MinesweeperViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** GameScreen's deal-if-missing behaviour and the top-level Navigation host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameScreenNavigationTest {

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
    fun gameScreenDealsAFieldWhenEnteredWithoutOne() {
        val vm = MinesweeperViewModel(app)
        rule.setContent {
            GameScreen(backStack(), vm, GameConfig(BoardSize.SMALL, Difficulty.EASY))
        }
        rule.waitForIdle()
        val game = vm.uiState.value.game
        assertNotNull(game)
        assertEquals(80, game.cellCount)
        rule.onNodeWithText(str(R.string.new_field)).assertExists()
        rule.onNodeWithText(str(R.string.give_up)).assertExists()
    }

    @Test
    fun gameScreenKeepsAFieldAlreadyInProgress() {
        val vm = MinesweeperViewModel(app)
        vm.newGame(GameConfig(BoardSize.LARGE, Difficulty.HARD))
        vm.tapCell(0)
        val before = vm.uiState.value.game
        rule.setContent {
            GameScreen(backStack(), vm, GameConfig(BoardSize.SMALL, Difficulty.EASY))
        }
        rule.waitForIdle()
        // Entering the route must not redeal over a live field, whatever the route carries.
        assertEquals(before, vm.uiState.value.game)
        assertEquals(216, vm.uiState.value.game!!.cellCount)
    }

    @Test
    fun navigationLandsOnHomeWithStatsAndNewGame() {
        val vm = MinesweeperViewModel(app)
        rule.setContent { Navigation(vm) }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.new_game)).assertExists()
        rule.onAllNodesWithText("0/0").fetchSemanticsNodes().let { assertEquals(3, it.size) }
    }

    @Test
    fun navigationOpensGameCenterAndBackReturnsHome() {
        val vm = MinesweeperViewModel(app)
        rule.setContent { Navigation(vm) }
        rule.onNodeWithContentDescription("Badges").performClick()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.title_game_center))
            .assertExists()
        rule.onNodeWithText("Swept").assertExists()
    }

    @Test
    fun navigationNewGameDealsAndShowsTheBoard() {
        val vm = MinesweeperViewModel(app)
        rule.setContent { Navigation(vm) }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        rule.onNodeWithText(str(R.string.field_size)).assertExists()
        val startButtons = rule.onAllNodesWithText(str(com.vayunmathur.library.ui.R.string.new_game))
        startButtons[startButtons.fetchSemanticsNodes().lastIndex].performClick()
        rule.waitForIdle()
        assertNotNull(vm.uiState.value.game)
        assertTrue(vm.hasActiveGame())
        rule.onNodeWithText(str(R.string.give_up)).assertExists()
    }

    @Test
    fun displayNamesCoverEverySizeAndDifficulty() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                BoardSize.entries.forEach { androidx.compose.material3.Text(it.displayName()) }
                Difficulty.entries.forEach { androidx.compose.material3.Text(it.displayName()) }
            }
        }
        rule.onNodeWithText(str(R.string.size_small)).assertExists()
        rule.onNodeWithText(str(R.string.size_large)).assertExists()
        rule.onNodeWithText(str(R.string.difficulty_expert)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.easy)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.hard)).assertExists()
        // Medium is shared by a size and a difficulty.
        assertEquals(
            2,
            rule.onAllNodesWithText(str(com.vayunmathur.library.ui.R.string.medium))
                .fetchSemanticsNodes().size,
        )
    }
}
