package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Animated Klondike auto-complete: the run stages one fast flight per step while a
 * card position is known, applies the rest instantly, and any tap mid-run skips to
 * the finished board. Every path must end on the same legal won board the instant
 * loop produces, with a single history entry so one undo restores the pre-run board.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolitaireAutoCompleteTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = SolitaireViewModel(app)

    private fun setK(vm: SolitaireViewModel, state: KlondikeState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.KLONDIKE, klondike = state)
    }

    private fun sevenPiles(vararg piles: TableauPile): List<TableauPile> =
        piles.toList() + List(7 - piles.size) { TableauPile() }

    /** Foundations A-Q per suit (indexed by Suit.entries); four Kings left out. */
    private fun almostWon() = KlondikeState(
        stock = listOf(Card(Suit.SPADES, Rank.KING)),
        waste = listOf(Card(Suit.HEARTS, Rank.KING)),
        tableauPiles = sevenPiles(
            TableauPile(faceUp = listOf(Card(Suit.DIAMONDS, Rank.KING))),
            TableauPile(faceUp = listOf(Card(Suit.CLUBS, Rank.KING))),
        ),
        foundations = Suit.entries.map { suit ->
            Rank.entries.filter { it != Rank.KING }.map { Card(suit, it) }
        },
        elapsedSeconds = 42,
    )

    @Test fun firstHopIsAppliedAndFlownWithOneHistoryEntry() {
        val vm = vm()
        setK(vm, almostWon())
        val heartsK = Card(Suit.HEARTS, Rank.KING)
        vm.registerCardPosition(heartsK, Offset(10f, 10f))
        vm.klondikeAutoComplete()
        assertTrue(vm.autoCompleteRunning.value)
        val flight = vm.flights.value.single()
        assertEquals(listOf(heartsK), flight.cards)
        assertEquals(FlightTarget.Slot("foundation_0", 0, 12), flight.target)
        assertTrue(flight.fast)
        // Logical state leads: the King is on its foundation while it flies.
        assertTrue(vm.uiState.value.klondike!!.waste.isEmpty())
        assertEquals(13, vm.uiState.value.klondike!!.foundations[0].size)
        assertEquals(1, vm.uiState.value.history.size)
    }

    @Test fun noPositionsRunsInstantlyToWin() {
        val vm = vm()
        setK(vm, almostWon())
        vm.klondikeAutoComplete()
        assertFalse(vm.autoCompleteRunning.value)
        assertTrue(vm.flights.value.isEmpty())
        val k = vm.uiState.value.klondike!!
        assertTrue(k.isWon, "state=$k")
        assertTrue(k.foundations.all { it.size == 13 })
        assertTrue(k.stock.isEmpty() && k.waste.isEmpty())
    }

    @Test fun animatedRunFliesKnownCardsAndDrawsStockInstantlyToWin() {
        val vm = vm()
        setK(vm, almostWon())
        vm.registerCardPosition(Card(Suit.HEARTS, Rank.KING), Offset(10f, 10f))
        vm.registerCardPosition(Card(Suit.DIAMONDS, Rank.KING), Offset(20f, 20f))
        vm.registerCardPosition(Card(Suit.CLUBS, Rank.KING), Offset(30f, 30f))
        vm.klondikeAutoComplete()
        val flown = mutableListOf<Card>()
        var guard = 0
        while (vm.autoCompleteRunning.value) {
            if (guard++ > 10) fail("run did not finish")
            val flight = vm.flights.value.singleOrNull() ?: fail("running without a hop in the air")
            assertTrue(flight.fast, "flight=$flight")
            assertFalse(vm.uiState.value.klondike!!.isWon, "won before the last hop landed")
            flown += flight.cards
            vm.landFlight(flight.id)
        }
        // The stock spades King was never laid out, so its draw and landing apply
        // instantly between the flown hops rather than flying a fourth.
        assertEquals(
            listOf(Card(Suit.HEARTS, Rank.KING), Card(Suit.DIAMONDS, Rank.KING), Card(Suit.CLUBS, Rank.KING)),
            flown,
        )
        val k = vm.uiState.value.klondike!!
        assertTrue(k.isWon, "state=$k")
        assertTrue(k.foundations.all { it.size == 13 })
    }

    @Test fun tapMidRunSkipsToWin() {
        val vm = vm()
        setK(vm, almostWon())
        vm.registerCardPosition(Card(Suit.HEARTS, Rank.KING), Offset(10f, 10f))
        vm.klondikeAutoComplete()
        assertTrue(vm.autoCompleteRunning.value)
        assertFalse(vm.requestAutoMove("waste", Offset.Zero))
        assertFalse(vm.autoCompleteRunning.value)
        // The hop already in the air is left to land; its move is applied.
        assertEquals(1, vm.flights.value.size)
        assertTrue(vm.uiState.value.klondike!!.isWon)
    }

    @Test fun dragMidRunSkipsToWin() {
        val vm = vm()
        setK(vm, almostWon())
        vm.registerCardPosition(Card(Suit.HEARTS, Rank.KING), Offset(10f, 10f))
        vm.klondikeAutoComplete()
        assertTrue(vm.autoCompleteRunning.value)
        assertFalse(vm.startDrag("waste", Offset.Zero))
        assertFalse(vm.autoCompleteRunning.value)
        assertTrue(vm.uiState.value.klondike!!.isWon)
    }

    @Test fun stockTapMidRunSkipsToWin() {
        val vm = vm()
        setK(vm, almostWon())
        vm.registerCardPosition(Card(Suit.HEARTS, Rank.KING), Offset(10f, 10f))
        vm.klondikeAutoComplete()
        assertTrue(vm.autoCompleteRunning.value)
        vm.drawFromStock()
        assertFalse(vm.autoCompleteRunning.value)
        assertTrue(vm.uiState.value.klondike!!.isWon)
    }

    @Test fun buttonRetapMidRunSkipsToWin() {
        val vm = vm()
        setK(vm, almostWon())
        vm.registerCardPosition(Card(Suit.HEARTS, Rank.KING), Offset(10f, 10f))
        vm.klondikeAutoComplete()
        assertTrue(vm.autoCompleteRunning.value)
        vm.klondikeAutoComplete()
        assertFalse(vm.autoCompleteRunning.value)
        assertEquals(1, vm.flights.value.size)
        assertTrue(vm.uiState.value.klondike!!.isWon)
    }

    @Test fun undoRestoresPreRunBoard() {
        val vm = vm()
        setK(vm, almostWon())
        vm.klondikeAutoComplete()
        assertTrue(vm.uiState.value.klondike!!.isWon)
        vm.undo()
        val k = vm.uiState.value.klondike!!
        assertFalse(k.isWon)
        assertEquals(listOf(Card(Suit.HEARTS, Rank.KING)), k.waste)
        assertEquals(listOf(Card(Suit.SPADES, Rank.KING)), k.stock)
        assertTrue(vm.uiState.value.history.isEmpty())
    }

    @Test fun noOpWhenNotCompletable() {
        val vm = vm()
        setK(
            vm,
            KlondikeState(
                tableauPiles = sevenPiles(
                    TableauPile(
                        faceDown = listOf(Card(Suit.HEARTS, Rank.ACE)),
                        faceUp = listOf(Card(Suit.HEARTS, Rank.TWO)),
                    ),
                ),
            ),
        )
        vm.klondikeAutoComplete()
        assertFalse(vm.autoCompleteRunning.value)
        assertTrue(vm.flights.value.isEmpty())
        assertTrue(vm.uiState.value.history.isEmpty())
    }
}
