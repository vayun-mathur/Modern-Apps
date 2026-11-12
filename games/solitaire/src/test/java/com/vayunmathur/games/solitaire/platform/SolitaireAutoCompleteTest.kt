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
 * Animated Klondike auto-complete: the run applies each step and flies it while a card
 * position is known, and applies the rest instantly. Board input mid-run stops it where
 * it is, the button re-tap skips to the finished board, and merely offering it locks
 * nothing. A run to the end lands on the same legal won board as the instant loop, and
 * a single history entry lets one undo restore the pre-run board.
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

    /** Starts a run whose first hop (the waste King) is in the air. */
    private fun startedRun(): SolitaireViewModel {
        val vm = vm()
        setK(vm, almostWon())
        vm.registerCardPosition(Card(Suit.HEARTS, Rank.KING), Offset(10f, 10f))
        vm.registerCardPosition(Card(Suit.DIAMONDS, Rank.KING), Offset(20f, 20f))
        vm.klondikeAutoComplete()
        assertTrue(vm.autoCompleteRunning.value)
        return vm
    }

    /** After a stop: the hop that was flying lands, nothing else moves, the board plays on. */
    private fun assertStoppedAfterFirstHop(vm: SolitaireViewModel) {
        assertFalse(vm.autoCompleteRunning.value)
        val hop = vm.flights.value.single()
        vm.landFlight(hop.id)
        assertTrue(vm.flights.value.isEmpty())
        val k = vm.uiState.value.klondike!!
        assertFalse(k.isWon)
        assertEquals(13, k.foundations[0].size) // the hearts King that was flying
        assertEquals(listOf(Card(Suit.DIAMONDS, Rank.KING)), k.tableauPiles[0].faceUp)
        assertEquals(1, vm.uiState.value.history.size)
        // The offer is back, and the board takes ordinary moves again.
        assertTrue(canKlondikeAutoComplete(k))
        assertTrue(vm.requestAutoMove("tableau_0_0", Offset(1f, 1f)))
        assertEquals(13, vm.uiState.value.klondike!!.foundations[1].size)
    }

    @Test fun tapMidRunStopsTheRun() {
        val vm = startedRun()
        assertFalse(vm.requestAutoMove("tableau_1_0", Offset.Zero)) // the tap is the stop
        assertStoppedAfterFirstHop(vm)
    }

    @Test fun dragMidRunStopsTheRun() {
        val vm = startedRun()
        assertFalse(vm.startDrag("tableau_1_0", Offset.Zero))
        assertStoppedAfterFirstHop(vm)
    }

    @Test fun stockTapMidRunStopsTheRun() {
        val vm = startedRun()
        vm.drawFromStock()
        assertEquals(listOf(Card(Suit.SPADES, Rank.KING)), vm.uiState.value.klondike!!.stock)
        assertStoppedAfterFirstHop(vm)
    }

    @Test fun offeredButNotStartedLocksNothing() {
        val vm = vm()
        setK(vm, almostWon())
        assertTrue(canKlondikeAutoComplete(vm.uiState.value.klondike!!))
        // Tap, stock and drag all play normally; no run starts.
        assertTrue(vm.requestAutoMove("waste", Offset.Zero))
        assertTrue(vm.uiState.value.klondike!!.waste.isEmpty())
        vm.drawFromStock()
        assertEquals(listOf(Card(Suit.SPADES, Rank.KING)), vm.uiState.value.klondike!!.waste)
        assertTrue(vm.startDrag("tableau_0_0", Offset.Zero))
        vm.cancelDrag()
        assertFalse(vm.autoCompleteRunning.value)
        assertEquals(2, vm.uiState.value.klondike!!.moveCount)
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
