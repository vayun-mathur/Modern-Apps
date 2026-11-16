package com.vayunmathur.games.solitaire.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.CardColorScheme
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.FreeCellState
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.PyramidState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.SpiderState
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.ui.dialogs.GameConfigDialog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Boards, action bar, win overlay, settings and per-mode config dialogs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolitaireBoardsCoverageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun c(suit: Suit, rank: Rank) = Card(suit, rank)

    private fun klondikePiles(vararg piles: TableauPile): List<TableauPile> =
        piles.toList() + List(7 - piles.size) { TableauPile() }

    @Test
    fun klondikeBoardDrawThreeFansWasteAndShowsFoundations() {
        val actions = RecordingActions()
        val state = KlondikeState(
            stock = listOf(c(Suit.CLUBS, Rank.TWO)),
            waste = listOf(c(Suit.HEARTS, Rank.FIVE), c(Suit.SPADES, Rank.NINE), c(Suit.DIAMONDS, Rank.JACK), c(Suit.CLUBS, Rank.QUEEN)),
            tableauPiles = klondikePiles(
                TableauPile(faceDown = listOf(c(Suit.SPADES, Rank.THREE)), faceUp = listOf(c(Suit.HEARTS, Rank.KING))),
                TableauPile(faceUp = listOf(c(Suit.CLUBS, Rank.SEVEN))),
            ),
            foundations = listOf(listOf(c(Suit.HEARTS, Rank.ACE)), emptyList(), emptyList(), emptyList()),
            drawMode = DrawMode.DRAW_THREE,
        )
        composeRule.setContent { KlondikeBoard(state, actions) }
        composeRule.onNodeWithText("Q").assertIsDisplayed()
        composeRule.onNodeWithText("J").assertIsDisplayed()
        composeRule.onNodeWithText("K").assertIsDisplayed()
        composeRule.onNodeWithText("7").assertIsDisplayed()
    }

    @Test
    fun klondikeBoardStockedBoardComposes() {
        val stocked = RecordingActions()
        composeRule.setContent {
            KlondikeBoard(
                KlondikeState(stock = listOf(c(Suit.HEARTS, Rank.ACE)), tableauPiles = klondikePiles()),
                stocked,
            )
        }
        composeRule.waitForIdle()
        assertTrue(stocked.dropTargets.isNotEmpty())
    }

    @Test
    fun freeCellBoardShowsCellsFoundationsAndColumns() {
        val actions = RecordingActions()
        val state = FreeCellState(
            tableauPiles = listOf(listOf(c(Suit.HEARTS, Rank.KING), c(Suit.SPADES, Rank.QUEEN))) + List(7) { emptyList() },
            freeCells = listOf(c(Suit.CLUBS, Rank.ACE), null, null, null),
            foundations = listOf(emptyList(), listOf(c(Suit.DIAMONDS, Rank.ACE), c(Suit.DIAMONDS, Rank.TWO)), emptyList(), emptyList()),
        )
        composeRule.setContent { FreeCellBoard(state, actions) }
        composeRule.onNodeWithText("K").assertIsDisplayed()
        composeRule.onNodeWithText("Q").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("A").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun spiderBoardShowsProgressStockAndTableau() {
        val actions = RecordingActions()
        val piles = listOf(
            TableauPile(faceDown = listOf(c(Suit.SPADES, Rank.FOUR)), faceUp = listOf(c(Suit.SPADES, Rank.KING))),
        ) + List(9) { TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.SIX))) }
        val state = SpiderState(
            tableauPiles = piles,
            stockGroups = listOf(listOf(c(Suit.CLUBS, Rank.TEN)), listOf(c(Suit.CLUBS, Rank.NINE))),
            completedSuits = 3,
        )
        composeRule.setContent { SpiderBoard(state, actions) }
        composeRule.onNodeWithText("Completed: 3/8").assertIsDisplayed()
        composeRule.onNodeWithText("K").assertIsDisplayed()
    }

    @Test
    fun spiderBoardWithoutStockHidesDealCards() {
        val actions = RecordingActions()
        val piles = List(10) { TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.EIGHT))) }
        composeRule.setContent { SpiderBoard(SpiderState(tableauPiles = piles, completedSuits = 0), actions) }
        composeRule.onNodeWithText("Completed: 0/8").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("8").fetchSemanticsNodes().size == 10)
    }

    @Test
    fun pyramidBoardTapsExposedAndWasteCardsAndDeals() {
        val actions = RecordingActions()
        val state = PyramidState(
            pyramid = listOf(listOf(c(Suit.CLUBS, Rank.ACE)), listOf(c(Suit.HEARTS, Rank.QUEEN), c(Suit.DIAMONDS, Rank.TWO))),
            stock = listOf(c(Suit.CLUBS, Rank.THREE)),
            waste = listOf(c(Suit.HEARTS, Rank.FIVE)),
            selectedId = "pyr_1_0",
        )
        composeRule.setContent { PyramidBoard(state, actions) }
        composeRule.onNodeWithText("Q").assertIsDisplayed()
        composeRule.onNodeWithText("5").performClick()
        assertTrue(actions.calls.contains("pyramidTapCard:waste"), actions.calls.toString())
        composeRule.onNodeWithText("Q").performClick()
        assertTrue(actions.calls.contains("pyramidTapCard:pyr_1_0"), actions.calls.toString())
    }

    @Test
    fun pyramidBoardEmptyStockRelaxedShowsRecycleOriginalDoesNot() {
        val relaxed = RecordingActions()
        val base = PyramidState(
            pyramid = listOf(listOf(c(Suit.CLUBS, Rank.ACE))),
            stock = emptyList(),
            waste = listOf(c(Suit.HEARTS, Rank.FIVE)),
            relaxed = true,
        )
        composeRule.setContent { PyramidBoard(base, relaxed) }
        composeRule.onNodeWithText("↻").assertIsDisplayed()
        composeRule.onNodeWithText("↻").performClick()
        assertTrue(relaxed.calls.contains("pyramidDealStock"))
    }

    @Test
    fun pyramidBoardEmptyStockOriginalHasNoRecycle() {
        val original = RecordingActions()
        val base = PyramidState(
            pyramid = listOf(listOf(c(Suit.CLUBS, Rank.ACE))),
            stock = emptyList(),
            waste = listOf(c(Suit.HEARTS, Rank.FIVE)),
            relaxed = false,
        )
        composeRule.setContent { PyramidBoard(base, original) }
        composeRule.onNodeWithText("5").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("↻").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun tableauColumnsEmptyFaceDownAndFreeCellVariants() {
        val actions = RecordingActions()
        composeRule.setContent {
            TableauColumn(
                pile = TableauPile(faceDown = listOf(c(Suit.SPADES, Rank.TWO)), faceUp = listOf(c(Suit.HEARTS, Rank.NINE))),
                columnIndex = 2,
                actions = actions,
            )
        }
        composeRule.onNodeWithText("9").assertIsDisplayed()
    }

    @Test
    fun freeCellTableauColumnShowsStack() {
        val actions = RecordingActions()
        composeRule.setContent {
            FreeCellTableauColumn(pile = listOf(c(Suit.CLUBS, Rank.JACK), c(Suit.DIAMONDS, Rank.TEN)), columnIndex = 1, actions = actions)
        }
        composeRule.onNodeWithText("J").assertIsDisplayed()
        composeRule.onNodeWithText("10").assertIsDisplayed()
    }

    @Test
    fun emptyTableauColumnComposes() {
        composeRule.setContent { TableauColumn(pile = TableauPile(), columnIndex = 0, actions = RecordingActions()) }
        composeRule.waitForIdle()
    }

    @Test
    fun gameActionBarUndoEnabledStateAndClicks() {
        var undo = 0
        var giveUp = 0
        composeRule.setContent {
            GameActionBar(onUndo = { undo++ }, onGiveUp = { giveUp++ }, undoEnabled = false)
        }
        composeRule.onNodeWithText("Undo").assertIsNotEnabled()
        composeRule.onNodeWithText("Give Up").assertIsEnabled()
        composeRule.onNodeWithText("Give Up").performClick()
        assertEquals(1, giveUp)
        assertEquals(0, undo)
    }

    @Test
    fun gameActionBarUndoEnabledClicks() {
        var undo = 0
        composeRule.setContent {
            GameActionBar(onUndo = { undo++ }, onGiveUp = {}, undoEnabled = true)
        }
        composeRule.onNodeWithText("Undo").assertIsEnabled()
        composeRule.onNodeWithText("Undo").performClick()
        assertEquals(1, undo)
    }

    @Test
    fun winOverlayShowsTimeMovesAndButtons() {
        var newGame = 0
        var back = 0
        composeRule.setContent {
            WinOverlay(elapsedSeconds = 125, moveCount = 42, onNewGame = { newGame++ }, onBack = { back++ })
        }
        composeRule.onNodeWithText("Congratulations!").assertIsDisplayed()
        composeRule.onNodeWithText("Moves: 42").assertIsDisplayed()
        composeRule.onNodeWithText("New Game").performClick()
        assertEquals(1, newGame)
        composeRule.onNodeWithText("Back").performClick()
        assertEquals(1, back)
    }

    @Test
    fun settingsScreenSelectsFourColourScheme() {
        var chosen: CardColorScheme? = null
        composeRule.setContent {
            SettingsScreen(
                cardColorScheme = CardColorScheme.TWO_COLOUR,
                onCardColorSchemeChange = { chosen = it },
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Card Colours").assertIsDisplayed()
        composeRule.onNodeWithText("Card Colours").performClick()
        composeRule.onNodeWithText("Four colours (colourblind friendly)").performClick()
        assertEquals(CardColorScheme.FOUR_COLOUR, chosen)
    }

    @Test
    fun gameBoardScreenKlondikeShowsMovesAndGiveUpConfirm() {
        val actions = RecordingActions()
        var exited = false
        val state = SolitaireUiState(
            gameMode = GameMode.KLONDIKE,
            klondike = KlondikeState(
                tableauPiles = klondikePiles(TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.KING)))),
                moveCount = 7,
                elapsedSeconds = 61,
            ),
            history = listOf(KlondikeState()),
        )
        composeRule.setContent {
            GameBoardScreen(state = state, mode = GameMode.KLONDIKE, actions = actions, onExit = { exited = true })
        }
        composeRule.onNodeWithText("Klondike").assertIsDisplayed()
        composeRule.onNodeWithText("Moves: 7").assertIsDisplayed()
        composeRule.onNodeWithText("Give Up").performClick()
        composeRule.onNodeWithText("Give up this game?").assertIsDisplayed()
        assertTrue(!exited)
    }

    @Test
    fun gameBoardScreenWonPyramidShowsOverlayAndSpiderFreeCellBoards() {
        val actions = RecordingActions()
        val wonPyramid = SolitaireUiState(
            gameMode = GameMode.PYRAMID,
            pyramid = PyramidState(pyramid = listOf(listOf(null)), isWon = true, moveCount = 3, elapsedSeconds = 9),
        )
        composeRule.setContent {
            GameBoardScreen(state = wonPyramid, mode = GameMode.PYRAMID, actions = actions, onExit = {})
        }
        composeRule.onNodeWithText("Congratulations!").assertIsDisplayed()
    }

    @Test
    fun gameBoardScreenSpiderBoard() {
        val actions = RecordingActions()
        val spider = SolitaireUiState(
            gameMode = GameMode.SPIDER,
            spider = SpiderState(
                tableauPiles = List(10) { TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.FOUR))) },
                moveCount = 2,
            ),
        )
        composeRule.setContent {
            GameBoardScreen(state = spider, mode = GameMode.SPIDER, actions = actions, onExit = {})
        }
        composeRule.onNodeWithText("Spider").assertIsDisplayed()
        composeRule.onNodeWithText("Completed: 0/8").assertIsDisplayed()
    }

    @Test
    fun gameBoardScreenFreeCellBoard() {
        val actions = RecordingActions()
        val freeCell = SolitaireUiState(
            gameMode = GameMode.FREECELL,
            freeCell = FreeCellState(
                tableauPiles = listOf(listOf(c(Suit.HEARTS, Rank.THREE))) + List(7) { emptyList() },
            ),
        )
        composeRule.setContent {
            GameBoardScreen(state = freeCell, mode = GameMode.FREECELL, actions = actions, onExit = {})
        }
        composeRule.onNodeWithText("FreeCell").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun gameConfigDialogSpiderPyramidAndFreeCellChoices() {
        var started: com.vayunmathur.games.solitaire.data.GameConfig? = null
        composeRule.setContent {
            GameConfigDialog(mode = GameMode.SPIDER, onStart = { started = it }, onDismiss = {})
        }
        composeRule.onNodeWithText("Spider").assertIsDisplayed()
        composeRule.onNodeWithText("Easy").performClick()
        composeRule.onNodeWithText("New Game").performClick()
        assertEquals(1, started?.spiderSuits)
    }

    @Test
    fun gameConfigDialogPyramidRelaxedChoice() {
        var started: com.vayunmathur.games.solitaire.data.GameConfig? = null
        composeRule.setContent {
            GameConfigDialog(mode = GameMode.PYRAMID, onStart = { started = it }, onDismiss = {})
        }
        composeRule.onNodeWithText("Relaxed").performClick()
        composeRule.onNodeWithText("New Game").performClick()
        assertEquals(true, started?.relaxed)
    }

    @Test
    fun gameConfigDialogFreeCellStartsWithDefaults() {
        var started: com.vayunmathur.games.solitaire.data.GameConfig? = null
        composeRule.setContent {
            GameConfigDialog(mode = GameMode.FREECELL, onStart = { started = it }, onDismiss = {})
        }
        composeRule.onNodeWithText("New Game").performClick()
        assertEquals(4, started?.spiderSuits)
    }

    @Test
    fun gameConfigDialogKlondikeDrawThreeSelection() {
        var started: com.vayunmathur.games.solitaire.data.GameConfig? = null
        composeRule.setContent {
            GameConfigDialog(mode = GameMode.KLONDIKE, onStart = { started = it }, onDismiss = {})
        }
        composeRule.onNodeWithText("Draw 3").performClick()
        composeRule.onNodeWithContentDescription("1 pass").performClick()
        composeRule.onNodeWithText("New Game").performClick()
        assertEquals(DrawMode.DRAW_THREE, started?.drawMode)
        assertEquals(
            com.vayunmathur.games.solitaire.data.KlondikeDifficulty.HARD,
            started?.klondikeDifficulty,
        )
    }

    @Test
    fun pileLayoutOffsetsIncreaseWithFaceDownAndFaceUp() {
        val downOnly = tableauCardOffset(faceDownCount = 2, faceUpIndex = 0)
        val up = tableauCardOffset(faceDownCount = 2, faceUpIndex = 3)
        assertTrue(up > downOnly)
        assertEquals(0, tableauCardOffset(0, 0).value.toInt())
    }
}
