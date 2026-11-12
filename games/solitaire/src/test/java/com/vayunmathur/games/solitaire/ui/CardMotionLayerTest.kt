package com.vayunmathur.games.solitaire.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.platform.DragInfo
import com.vayunmathur.games.solitaire.platform.FlightTarget
import com.vayunmathur.games.solitaire.platform.SolitaireViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The board and the motion layer together: each card on screen once, flights on the board's slots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardMotionLayerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val king = Card(Suit.SPADES, Rank.KING)
    private val queen = Card(Suit.HEARTS, Rank.QUEEN)
    private val jack = Card(Suit.CLUBS, Rank.JACK)

    /** Text nodes a sighted player would see: not inside a card hidden for being in motion. */
    private fun visibleCount(text: String): Int = composeRule.onAllNodes(
        hasText(text) and !hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    @Test
    fun dragHidesEveryCarriedCardInTheSourceAndDrawsEachOnce() {
        val actions = RecordingActions()
        val pile = TableauPile(faceUp = listOf(king, queen, jack))
        composeRule.setContent {
            Box {
                TableauColumn(pile = pile, columnIndex = 0, actions = actions)
                CardMotionLayer(actions)
            }
        }
        listOf("K", "Q", "J").forEach { assertEquals(1, visibleCount(it), it) }
        // Grab the queen: the queen and the jack above her are carried.
        actions.dragFlow.value = DragInfo(
            cards = listOf(queen, jack),
            sourceId = "tableau_0_1",
            offset = Offset(300f, 400f),
            startPos = Offset.Zero,
            cardSize = IntSize(60, 84),
            pileId = "tableau_0",
            startIndex = 1,
        )
        composeRule.waitForIdle()
        // No stack left behind in the source, no duplicate under the finger.
        listOf("K", "Q", "J").forEach { assertEquals(1, visibleCount(it), it) }
        actions.dragFlow.value = null
        composeRule.waitForIdle()
        listOf("K", "Q", "J").forEach { assertEquals(1, visibleCount(it), it) }
    }

    @Test
    fun tapMoveFlightLandsExactlyOnTheSlotTheBoardDrawsTheCardAt() {
        val vm = SolitaireViewModel(app)
        val seven = Card(Suit.HEARTS, Rank.SEVEN)
        vm.uiStateInternal.value = SolitaireUiState(
            gameMode = GameMode.KLONDIKE,
            klondike = KlondikeState(
                waste = listOf(seven),
                tableauPiles = listOf(
                    TableauPile(
                        faceDown = listOf(Card(Suit.CLUBS, Rank.TWO), Card(Suit.CLUBS, Rank.THREE)),
                        faceUp = listOf(Card(Suit.SPADES, Rank.EIGHT)),
                    ),
                ) + List(6) { TableauPile() },
            ),
        )
        composeRule.setContent {
            val ui by vm.uiState.collectAsState()
            Box {
                TableauColumn(ui.klondike!!.tableauPiles[0], 0, vm, Modifier, 50.dp, 75.dp)
                CardMotionLayer(vm)
            }
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { assertTrue(vm.requestAutoMove("waste", Offset(400f, 10f), IntSize(50, 75))) }
        val slot = vm.flights.value.single().target as FlightTarget.Slot
        assertEquals(FlightTarget.Slot("tableau_0", 2, 1), slot)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertTrue(vm.flights.value.isEmpty(), "flight never landed")
        val pileOrigin = vm.dropTargets.getValue("tableau_0").topLeft
        val target = with(composeRule.density) { pileSlotPosition(pileOrigin, slot) }
        // Where the column actually drew the seven after the move.
        val drawn = vm.cardPositions.getValue(seven)
        assertEquals(drawn.x, target.x, absoluteTolerance = 1f)
        assertEquals(drawn.y, target.y, absoluteTolerance = 1f)
    }

    @Test
    fun secondTapDuringAFlightDoesNotCancelTheFirstMove() {
        val vm = SolitaireViewModel(app)
        val heartsAce = Card(Suit.HEARTS, Rank.ACE)
        val spadesAce = Card(Suit.SPADES, Rank.ACE)
        vm.uiStateInternal.value = SolitaireUiState(
            gameMode = GameMode.KLONDIKE,
            klondike = KlondikeState(
                waste = listOf(heartsAce),
                tableauPiles = listOf(TableauPile(faceUp = listOf(spadesAce))) + List(6) { TableauPile() },
            ),
        )
        composeRule.setContent {
            val ui by vm.uiState.collectAsState()
            Box {
                KlondikeBoard(ui.klondike!!, vm)
                CardMotionLayer(vm)
            }
        }
        composeRule.waitForIdle()
        val wasteAt = vm.cardPositions.getValue(heartsAce)
        val tableauAt = vm.cardPositions.getValue(spadesAce)
        val inset = Offset(10f, 10f)
        composeRule.mainClock.autoAdvance = false
        composeRule.onRoot().performTouchInput { click(wasteAt + inset) }
        composeRule.mainClock.advanceTimeBy(FRAME_MS * 3)
        assertEquals(1, vm.flights.value.size, "first tap did not fly")
        composeRule.onRoot().performTouchInput { click(tableauAt + inset) }
        composeRule.mainClock.advanceTimeBy(FRAME_MS * 3)
        // Both in the air, both applied.
        assertEquals(2, vm.flights.value.size)
        val mid = vm.uiState.value.klondike!!
        assertEquals(listOf(heartsAce), mid.foundations[0])
        assertEquals(listOf(spadesAce), mid.foundations[2])
        assertEquals(2, visibleCount("A")) // the two flying copies; their foundation tops are hidden
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertTrue(vm.flights.value.isEmpty())
        assertEquals(mid, vm.uiState.value.klondike)
        assertEquals(2, visibleCount("A")) // each ace on its foundation, once
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
