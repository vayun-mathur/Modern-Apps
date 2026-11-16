package com.vayunmathur.games.solitaire.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.Navigation
import com.vayunmathur.games.solitaire.Route
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.platform.SolitaireViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home screen mode picker, in-game screen wiring and top-level navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeAndGameScreenCoverageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = SolitaireViewModel(app)

    @Test
    fun homeScreenShowsStatsAndFreeCellStartsImmediately() {
        val viewModel = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        composeRule.setContent { HomeScreen(backStack, viewModel) }
        composeRule.onNodeWithText("Solitaire").assertIsDisplayed()
        composeRule.onNodeWithText("Klondike").assertIsDisplayed()
        composeRule.onNodeWithText("Spider").assertIsDisplayed()
        composeRule.onNodeWithText("New Game").performClick()
        composeRule.onNodeWithText("Select Mode").assertIsDisplayed()
        composeRule.onAllNodesWithText("FreeCell")[1].performClick()
        composeRule.waitForIdle()
        assertEquals(GameMode.FREECELL, viewModel.uiState.value.gameMode)
        assertTrue(backStack.backStack.contains(Route.Game(GameMode.FREECELL)))
    }

    @Test
    fun homeScreenKlondikePickerOpensConfigThenStarts() {
        val viewModel = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        composeRule.setContent { HomeScreen(backStack, viewModel) }
        composeRule.onNodeWithText("New Game").performClick()
        composeRule.onAllNodesWithText("Klondike")[1].performClick()
        // Config dialog for Klondike (the second New Game button is the dialog's start).
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("New Game").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun homeScreenContinueAppearsWithActiveGame() {
        val viewModel = vm()
        viewModel.selectMode(GameMode.FREECELL)
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        composeRule.setContent { HomeScreen(backStack, viewModel) }
        composeRule.onNodeWithText("Continue").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").performClick()
        assertTrue(backStack.backStack.contains(Route.Game(GameMode.FREECELL)))
    }

    @Test
    fun gameScreenSelectsModeAndRendersBoard() {
        val viewModel = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        composeRule.setContent { GameScreen(backStack, viewModel, GameMode.SPIDER) }
        composeRule.waitForIdle()
        assertEquals(GameMode.SPIDER, viewModel.uiState.value.gameMode)
        composeRule.onNodeWithText("Spider").assertIsDisplayed()
        composeRule.onNodeWithText("Completed: 0/8").assertIsDisplayed()
    }

    @Test
    fun navigationRendersHome() {
        val viewModel = vm()
        composeRule.setContent { Navigation(viewModel) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Solitaire").assertIsDisplayed()
        composeRule.onNodeWithText("New Game").assertIsDisplayed()
    }
}
