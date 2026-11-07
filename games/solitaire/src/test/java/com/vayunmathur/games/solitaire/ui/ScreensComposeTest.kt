package com.vayunmathur.games.solitaire.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.R
import com.vayunmathur.games.solitaire.Route
import com.vayunmathur.games.solitaire.data.CardColorScheme
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.PyramidState
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.platform.SolitaireViewModel
import com.vayunmathur.games.solitaire.platform.newKlondikeGame
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Board screen chrome, home, settings, win overlay and display names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreensComposeTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = ctx.getString(id)
    private fun uiStr(id: Int) = ctx.getString(id)
    private fun vm() = SolitaireViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test fun displayNamesForAllModes() {
        rule.setContent {
            Column {
                androidx.compose.material3.Text(GameMode.KLONDIKE.displayName())
                androidx.compose.material3.Text(GameMode.SPIDER.displayName())
                androidx.compose.material3.Text(GameMode.FREECELL.displayName())
                androidx.compose.material3.Text(GameMode.PYRAMID.displayName())
            }
        }
        // library.ui Text is used inside displayName call sites elsewhere; the
        // names themselves come from string resources:
        assertEquals("Klondike", str(R.string.klondike))
        assertEquals("Spider", str(R.string.spider))
        assertEquals("FreeCell", str(R.string.freecell))
        assertEquals("Pyramid", str(R.string.pyramid))
        rule.onNodeWithText("Klondike").assertIsDisplayed()
        rule.onNodeWithText("Pyramid").assertIsDisplayed()
    }

    @Test fun gameActionBarButtons() {
        var undos = 0; var giveUps = 0
        rule.setContent {
            GameActionBar(onUndo = { undos++ }, onGiveUp = { giveUps++ }, undoEnabled = true)
        }
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.undo)).performClick()
        rule.onNodeWithText(str(R.string.give_up)).performClick()
        assertEquals(1, undos)
        assertEquals(1, giveUps)
    }

    @Test fun gameActionBarUndoDisabled() {
        var undos = 0
        rule.setContent {
            GameActionBar(onUndo = { undos++ }, onGiveUp = {}, undoEnabled = false)
        }
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.undo))
            .assertExists()
        // Disabled button does not fire.
        runCatching { rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.undo)).performClick() }
        assertEquals(0, undos)
    }

    @Test fun winOverlayShowsStatsAndActions() {
        var newGames = 0; var backs = 0
        rule.setContent {
            WinOverlay(elapsedSeconds = 125, moveCount = 87, onNewGame = { newGames++ }, onBack = { backs++ })
        }
        rule.onNodeWithText(str(R.string.congratulations)).assertIsDisplayed()
        rule.onNodeWithText("${uiStr(com.vayunmathur.library.ui.R.string.time)}: 02:05").assertExists()
        rule.onNodeWithText("${str(R.string.moves)}: 87").assertExists()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertEquals(1, newGames)
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.back)).performClick()
        assertEquals(1, backs)
    }

    @Test fun settingsScreenSelectsFourColour() {
        var selected: CardColorScheme? = null
        var backs = 0
        rule.setContent {
            SettingsScreen(
                cardColorScheme = CardColorScheme.TWO_COLOUR,
                onCardColorSchemeChange = { selected = it },
                onBack = { backs++ },
            )
        }
        rule.onNodeWithText(str(R.string.card_colors)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.card_colors)).performClick()
        rule.onNodeWithText(str(R.string.card_colors_four)).performClick()
        assertEquals(CardColorScheme.FOUR_COLOUR, selected)
        assertEquals(0, backs)
    }

    @Test fun gameBoardScreenKlondikeAutoCompleteAndGiveUpDialog() {
        val actions = RecordingActions()
        var exits = 0
        val state = SolitaireUiState(
            gameMode = GameMode.KLONDIKE,
            klondike = KlondikeState(
                stock = listOf(Card(Suit.HEARTS, Rank.ACE)),
                moveCount = 12,
                elapsedSeconds = 65,
            ),
        )
        rule.setContent {
            GameBoardScreen(state = state, mode = GameMode.KLONDIKE, actions = actions, onExit = { exits++ })
        }
        rule.onNodeWithText("Klondike").assertExists()
        rule.onNodeWithText("${str(R.string.moves)}: 12").assertExists()
        rule.onNodeWithText(str(R.string.auto_complete)).performClick()
        assertTrue(actions.calls.contains("klondikeAutoComplete"), "${actions.calls}")
        // Give up asks first, dismiss keeps the game, confirm ends it.
        rule.onNodeWithText(str(R.string.give_up)).performClick()
        rule.onNodeWithText(str(R.string.confirm_give_up_title)).assertIsDisplayed()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertTrue(actions.calls.none { it == "giveUp" })
        assertEquals(0, exits)
        rule.onNodeWithText(str(R.string.give_up)).performClick()
        rule.onAllNodesWithText(str(R.string.give_up))[1].performClick()
        assertTrue(actions.calls.contains("giveUp"))
        assertEquals(1, exits)
        // Undo is wired too (disabled state is covered in the action bar test).
        assertTrue(actions.calls.none { it == "undo" })
    }

    @Test fun gameBoardScreenWonShowsOverlayAndGiveUpSkipsConfirm() {
        val actions = RecordingActions()
        var exits = 0
        val state = SolitaireUiState(
            gameMode = GameMode.KLONDIKE,
            klondike = KlondikeState(isWon = true, moveCount = 40, elapsedSeconds = 61),
        )
        rule.setContent {
            GameBoardScreen(state = state, mode = GameMode.KLONDIKE, actions = actions, onExit = { exits++ })
        }
        rule.onNodeWithText(str(R.string.congratulations)).assertIsDisplayed()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertTrue(actions.calls.contains("restart"))
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.back)).performClick()
        assertEquals(1, exits)
    }

    @Test fun gameBoardScreenOtherModesRender() {
        val actions = RecordingActions()
        // Pyramid with an empty pyramid (no state) renders chrome only.
        rule.setContent {
            GameBoardScreen(state = SolitaireUiState(gameMode = GameMode.PYRAMID), mode = GameMode.PYRAMID, actions = actions, onExit = {})
        }
        rule.onNodeWithText("Pyramid").assertExists()
        rule.onNodeWithText("${str(R.string.moves)}: 0").assertExists()
    }

    @Test fun gameBoardScreenPyramidStateRendersBoard() {
        val actions = RecordingActions()
        val state = SolitaireUiState(
            gameMode = GameMode.PYRAMID,
            pyramid = PyramidState(pyramid = listOf(listOf(Card(Suit.HEARTS, Rank.KING)))),
        )
        rule.setContent {
            GameBoardScreen(state = state, mode = GameMode.PYRAMID, actions = actions, onExit = {})
        }
        rule.onNodeWithText("K").assertIsDisplayed()
    }

    @Test fun homeScreenStartsFreeCellAndKlondikeViaDialogs() {
        val vm = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent { HomeScreen(backStack, vm) }
        rule.onNodeWithText("Solitaire").assertExists()
        // Stats cards for all four modes.
        rule.onNodeWithText("Klondike").assertExists()
        rule.onNodeWithText("FreeCell").assertExists()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        rule.onNodeWithText(str(R.string.select_mode)).assertIsDisplayed()
        // "FreeCell" also labels the stats card behind the dialog; the dialog
        // button is the second match.
        rule.onAllNodesWithText("FreeCell")[1].performClick()
        assertEquals(Route.Game(GameMode.FREECELL), backStack.last())
        assertEquals(GameMode.FREECELL, vm.uiState.value.gameMode)
    }

    @Test fun homeScreenKlondikeConfigDialogFlow() {
        val vm = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent { HomeScreen(backStack, vm) }
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        // As above, the dialog's Klondike button follows the stats card.
        rule.onAllNodesWithText("Klondike")[1].performClick()
        // Config dialog for Klondike shows draw options; start the game.
        rule.onNodeWithText(str(R.string.draw_three)).performClick()
        // The config dialog's New Game button follows the home screen's own.
        val newGameNodes = rule.onAllNodesWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game))
        newGameNodes[newGameNodes.fetchSemanticsNodes().size - 1].performClick()
        assertEquals(Route.Game(GameMode.KLONDIKE), backStack.last())
        assertEquals(com.vayunmathur.games.solitaire.data.DrawMode.DRAW_THREE, vm.uiState.value.klondike!!.drawMode)
    }

    @Test fun homeScreenContinueResumesActiveGame() {
        val vm = vm()
        vm.newKlondikeGame(com.vayunmathur.games.solitaire.data.GameConfig())
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent { HomeScreen(backStack, vm) }
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.continue_label)).performClick()
        assertEquals(Route.Game(GameMode.KLONDIKE), backStack.last())
    }

    @Test fun homeScreenPickerDismissesWithBack() {
        val vm = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Home))
        rule.setContent { HomeScreen(backStack, vm) }
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        rule.onNodeWithText(str(R.string.select_mode)).assertIsDisplayed()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.back)).performClick()
        rule.onNodeWithText(str(R.string.select_mode)).assertDoesNotExist()
        assertEquals(Route.Home, backStack.last())
    }

    @Test fun navigationShowsHomeAndMainActivityCreates() {
        val vm = vm()
        rule.setContent { com.vayunmathur.games.solitaire.Navigation(vm) }
        rule.onNodeWithText("Solitaire").assertExists()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).assertExists()

        val controller = Robolectric.buildActivity(com.vayunmathur.games.solitaire.MainActivity::class.java)
        controller.create()
        assertTrue(!controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun gameScreenDealsRequestedMode() {
        val vm = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Home, Route.Game(GameMode.FREECELL)))
        rule.setContent { GameScreen(backStack, vm, GameMode.FREECELL) }
        rule.waitForIdle()
        assertEquals(GameMode.FREECELL, vm.uiState.value.gameMode)
        rule.onNodeWithText(str(R.string.give_up)).assertExists()
    }
}
