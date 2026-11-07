package com.vayunmathur.games.solitaire.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.FreeCellState
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.PyramidState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SpiderState
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.platform.DragInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The four boards and shared column/overlay components with scripted state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoardsComposeTest {
    @get:Rule val rule = createComposeRule()
    private fun c(suit: Suit, rank: Rank) = Card(suit, rank)

    @Test fun klondikeBoardShowsStockWasteFoundationsAndDraws() {
        val actions = RecordingActions()
        val state = KlondikeState(
            stock = listOf(c(Suit.CLUBS, Rank.THREE)),
            waste = listOf(c(Suit.HEARTS, Rank.QUEEN), c(Suit.SPADES, Rank.SEVEN), c(Suit.HEARTS, Rank.SIX)),
            tableauPiles = listOf(
                TableauPile(faceDown = listOf(c(Suit.CLUBS, Rank.TWO)), faceUp = listOf(c(Suit.SPADES, Rank.KING))),
            ) + List(6) { TableauPile() },
            foundations = listOf(listOf(c(Suit.HEARTS, Rank.ACE))) + List(3) { emptyList() },
            drawMode = DrawMode.DRAW_THREE,
        )
        rule.setContent { KlondikeBoard(state, actions) }
        // Waste top and the fanned card behind it both render in DRAW_THREE.
        rule.onNodeWithText("6").assertIsDisplayed()
        rule.onNodeWithText("K").assertIsDisplayed()
        rule.onNodeWithText("A").assertIsDisplayed()
        // First clickable node is the stock pile.
        rule.onAllNodes(hasClickAction())[0].performClick()
        assertEquals(listOf("drawFromStock"), actions.calls)
        // Drop targets were registered by foundations/columns during layout.
        assertTrue(actions.dropTargets.keys.any { it.startsWith("foundation_") }, "${actions.dropTargets.keys}")
        assertTrue(actions.dropTargets.keys.any { it.startsWith("tableau_") }, "${actions.dropTargets.keys}")
    }

    @Test fun klondikeBoardEmptyStockAndWaste() {
        val actions = RecordingActions()
        rule.setContent { KlondikeBoard(KlondikeState(), actions) }
        // Empty stock slot is still the draw target.
        rule.onAllNodes(hasClickAction())[0].performClick()
        assertEquals(listOf("drawFromStock"), actions.calls)
    }

    @Test fun klondikeWasteDrawOneShowsOnlyTop() {
        val actions = RecordingActions()
        val state = KlondikeState(
            waste = listOf(c(Suit.HEARTS, Rank.QUEEN), c(Suit.SPADES, Rank.SEVEN)),
            drawMode = DrawMode.DRAW_ONE,
        )
        rule.setContent { KlondikeBoard(state, actions) }
        rule.onNodeWithText("7").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Q").fetchSemanticsNodes().isEmpty())
    }

    @Test fun spiderBoardShowsProgressStockAndDeals() {
        val actions = RecordingActions()
        val state = SpiderState(
            tableauPiles = listOf(TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.NINE)))) + List(9) { TableauPile() },
            stockGroups = listOf(listOf(c(Suit.HEARTS, Rank.ACE)), listOf(c(Suit.HEARTS, Rank.TWO))),
            completedSuits = 3,
        )
        rule.setContent { SpiderBoard(state, actions) }
        rule.onNodeWithText("Completed: 3/8").assertIsDisplayed()
        rule.onNodeWithText("9").assertIsDisplayed()
        val clickables = rule.onAllNodes(hasClickAction())
        clickables[0].performClick()
        assertTrue(actions.calls.contains("dealSpiderStock"), "${actions.calls}")
    }

    @Test fun spiderBoardWithoutStock() {
        val actions = RecordingActions()
        rule.setContent { SpiderBoard(SpiderState(completedSuits = 8), actions) }
        rule.onNodeWithText("Completed: 8/8").assertIsDisplayed()
    }

    @Test fun freeCellBoardShowsCellsFoundationsAndColumns() {
        val actions = RecordingActions()
        val state = FreeCellState(
            tableauPiles = listOf(listOf(c(Suit.SPADES, Rank.SEVEN), c(Suit.HEARTS, Rank.SIX))) + List(7) { emptyList() },
            freeCells = listOf(c(Suit.CLUBS, Rank.KING), null, null, null),
            foundations = listOf(listOf(c(Suit.HEARTS, Rank.ACE))) + List(3) { emptyList() },
        )
        rule.setContent { FreeCellBoard(state, actions) }
        rule.onNodeWithText("K").assertIsDisplayed()
        rule.onNodeWithText("7").assertIsDisplayed()
        rule.onNodeWithText("6").assertIsDisplayed()
        assertTrue(actions.dropTargets.keys.any { it.startsWith("freecell_") })
    }

    @Test fun pyramidBoardTapsCardsStockAndWaste() {
        val actions = RecordingActions()
        val state = PyramidState(
            pyramid = listOf(
                listOf(c(Suit.DIAMONDS, Rank.FIVE)),
                listOf(c(Suit.HEARTS, Rank.QUEEN), c(Suit.CLUBS, Rank.ACE)),
            ),
            stock = listOf(c(Suit.SPADES, Rank.TWO)),
            waste = listOf(c(Suit.SPADES, Rank.KING)),
            selectedId = "pyr_1_0",
        )
        rule.setContent { PyramidBoard(state, actions) }
        rule.onNodeWithText("Q").performClick()
        assertTrue(actions.calls.contains("pyramidTapCard:pyr_1_0"), "${actions.calls}")
        rule.onNodeWithText("K").performClick()
        assertTrue(actions.calls.contains("pyramidTapCard:waste"), "${actions.calls}")
        // Clickables in composition order: pyramid cards, then stock, then waste.
        rule.onAllNodes(hasClickAction())[3].performClick()
        assertTrue(actions.calls.contains("pyramidDealStock"), "${actions.calls}")
    }

    @Test fun pyramidBoardEmptyStockRecycleLabelAndNoWaste() {
        val actions = RecordingActions()
        val state = PyramidState(
            pyramid = listOf(listOf(c(Suit.DIAMONDS, Rank.FIVE))),
            stock = emptyList(),
            waste = listOf(c(Suit.SPADES, Rank.KING)),
            relaxed = true,
        )
        rule.setContent { PyramidBoard(state, actions) }
        rule.onNodeWithText("↻").assertIsDisplayed()
        // Clickables: the pyramid card, then the stock slot, then the waste.
        rule.onAllNodes(hasClickAction())[1].performClick()
        assertTrue(actions.calls.contains("pyramidDealStock"), "${actions.calls}")
    }

    @Test fun tableauColumnsEmptyFaceDownAndFaceUp() {
        val actions = RecordingActions()
        rule.setContent {
            TableauColumn(
                pile = TableauPile(
                    faceDown = listOf(c(Suit.CLUBS, Rank.TWO), c(Suit.CLUBS, Rank.THREE)),
                    faceUp = listOf(c(Suit.SPADES, Rank.KING), c(Suit.HEARTS, Rank.QUEEN)),
                ),
                columnIndex = 2,
                actions = actions,
            )
        }
        rule.onNodeWithText("K").assertIsDisplayed()
        rule.onNodeWithText("Q").assertIsDisplayed()
        assertTrue(actions.dropTargets.containsKey("tableau_2"))
    }

    @Test fun freeCellTableauColumnEmpty() {
        val actions = RecordingActions()
        rule.setContent {
            FreeCellTableauColumn(pile = emptyList(), columnIndex = 4, actions = actions)
        }
        rule.waitForIdle()
        assertTrue(actions.dropTargets.containsKey("tableau_4"))
    }

    @Test fun dragOverlayShowsDraggedCardsAndHiddenWhenIdle() {
        val actions = RecordingActions()
        rule.setContent { DragOverlay(actions, CARD_WIDTH, CARD_HEIGHT) }
        rule.onNodeWithText("Q").assertDoesNotExist()
        actions.dragFlow.value = DragInfo(
            cards = listOf(c(Suit.HEARTS, Rank.QUEEN), c(Suit.SPADES, Rank.JACK)),
            sourceId = "tableau_0_0",
        )
        rule.waitForIdle()
        rule.onNodeWithText("Q").assertIsDisplayed()
        rule.onNodeWithText("J").assertIsDisplayed()
        actions.dragFlow.value = null
        rule.waitForIdle()
        rule.onNodeWithText("Q").assertDoesNotExist()
    }

    @Test fun draggableCardTapAutoMoves() {
        val actions = RecordingActions()
        rule.setContent {
            DraggableCard(card = c(Suit.HEARTS, Rank.QUEEN), sourceId = "waste", actions = actions) {
                CardFace(c(Suit.HEARTS, Rank.QUEEN))
            }
        }
        rule.onNodeWithText("Q").performTouchInput { click() }
        rule.waitForIdle()
        assertTrue(actions.calls.contains("autoMove:waste"), "${actions.calls}")
    }
}
