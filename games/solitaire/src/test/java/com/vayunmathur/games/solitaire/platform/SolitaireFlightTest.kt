package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.FreeCellState
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tap-to-move flights: the move applies at once, the flight is visual, several fly at once,
 * and a card in the air cannot be grabbed in a way that corrupts state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolitaireFlightTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = SolitaireViewModel(app)
    private fun c(suit: Suit, rank: Rank) = Card(suit, rank)
    private val tap = Offset(10f, 20f)
    private val size = IntSize(60, 90)

    private fun setK(vm: SolitaireViewModel, state: KlondikeState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.KLONDIKE, klondike = state)
    }

    private fun setF(vm: SolitaireViewModel, state: FreeCellState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.FREECELL, freeCell = state)
    }

    private fun piles(vararg piles: TableauPile) = piles.toList() + List(7 - piles.size) { TableauPile() }

    @Test fun wasteAceMovesAtOnceAndFliesToFoundationSlot() {
        val vm = vm()
        val ace = c(Suit.HEARTS, Rank.ACE)
        setK(vm, KlondikeState(waste = listOf(ace)))
        assertTrue(vm.requestAutoMove("waste", tap, size))
        // Logical state is authoritative: the move is applied before the flight lands.
        val k = vm.uiState.value.klondike!!
        assertTrue(k.waste.isEmpty())
        assertEquals(listOf(ace), k.foundations[0])
        val flight = vm.flights.value.single()
        assertEquals(listOf(ace), flight.cards)
        assertEquals(tap, flight.from)
        assertEquals(size, flight.cardSize)
        assertEquals(FlightTarget.Slot("foundation_0", 0, 0), flight.target)
        assertEquals(PileRange("foundation_0", 0, listOf(ace)), flight.hides)
        // Landing only ends the visual.
        vm.landFlight(flight.id)
        assertTrue(vm.flights.value.isEmpty())
        assertEquals(k, vm.uiState.value.klondike)
    }

    @Test fun tableauFlightTargetsTheNextSlotPastFaceDownCards() {
        val vm = vm()
        val eight = c(Suit.SPADES, Rank.EIGHT)
        val seven = c(Suit.HEARTS, Rank.SEVEN)
        setK(
            vm,
            KlondikeState(
                waste = listOf(seven),
                tableauPiles = piles(
                    TableauPile(faceDown = listOf(c(Suit.CLUBS, Rank.TWO), c(Suit.CLUBS, Rank.THREE)), faceUp = listOf(eight)),
                ),
            ),
        )
        assertTrue(vm.requestAutoMove("waste", tap, size))
        assertEquals(listOf(eight, seven), vm.uiState.value.klondike!!.tableauPiles[0].faceUp)
        // Two face-down cards, then the seven at face-up index 1: the slot the column draws it at.
        assertEquals(FlightTarget.Slot("tableau_0", 2, 1), vm.flights.value.single().target)
    }

    @Test fun runFlightCarriesEveryCardOnceToTheFirstSlot() {
        val vm = vm()
        val run = listOf(c(Suit.HEARTS, Rank.QUEEN), c(Suit.CLUBS, Rank.JACK))
        val king = c(Suit.SPADES, Rank.KING)
        setK(
            vm,
            KlondikeState(
                tableauPiles = piles(
                    TableauPile(faceDown = listOf(c(Suit.CLUBS, Rank.TWO)), faceUp = run),
                    TableauPile(faceUp = listOf(king)),
                ),
            ),
        )
        assertTrue(vm.requestAutoMove("tableau_0_0", tap, size))
        val flight = vm.flights.value.single()
        assertEquals(run, flight.cards)
        assertEquals(FlightTarget.Slot("tableau_1", 0, 1), flight.target)
        assertEquals(PileRange("tableau_1", 1, run), flight.hides)
    }

    @Test fun requestRefusedWithoutLegalMove() {
        val vm = vm()
        setK(vm, KlondikeState(waste = emptyList()))
        assertFalse(vm.requestAutoMove("waste", tap))
        assertFalse(vm.requestAutoMove("nope", tap))
        assertTrue(vm.flights.value.isEmpty())
    }

    @Test fun secondTapDuringFlightKeepsFirstMoveAndFlight() {
        val vm = vm()
        val heartsAce = c(Suit.HEARTS, Rank.ACE)
        val spadesAce = c(Suit.SPADES, Rank.ACE)
        setK(vm, KlondikeState(waste = listOf(heartsAce), tableauPiles = piles(TableauPile(faceUp = listOf(spadesAce)))))
        assertTrue(vm.requestAutoMove("waste", tap, size))
        val first = vm.flights.value.single()
        assertTrue(vm.requestAutoMove("tableau_0_0", Offset(99f, 99f), size))
        // Both moves applied; both flights in the air, the first untouched.
        val k = vm.uiState.value.klondike!!
        assertEquals(listOf(heartsAce), k.foundations[0])
        assertEquals(listOf(spadesAce), k.foundations[2])
        assertEquals(2, vm.flights.value.size)
        assertEquals(first, vm.flights.value.first())
        assertEquals(2, k.moveCount)
        // Landing the second leaves the first flying.
        vm.landFlight(vm.flights.value.last().id)
        assertEquals(listOf(first), vm.flights.value)
        vm.landFlight(first.id)
        assertTrue(vm.flights.value.isEmpty())
        vm.landFlight(first.id) // a repeated landing is a no-op
        assertEquals(k, vm.uiState.value.klondike)
    }

    @Test fun tapOnCardInTheAirIsQueuedAndReplayedWhenItLands() {
        val vm = vm()
        val seven = c(Suit.HEARTS, Rank.SEVEN)
        setK(
            vm,
            KlondikeState(
                waste = listOf(seven),
                tableauPiles = piles(
                    TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.EIGHT))),
                    TableauPile(faceUp = listOf(c(Suit.CLUBS, Rank.EIGHT))),
                ),
            ),
        )
        assertTrue(vm.requestAutoMove("waste", tap, size))
        val first = vm.flights.value.single()
        assertEquals(seven, vm.uiState.value.klondike!!.tableauPiles[0].faceUp.last())
        // Tapping the seven where it is landing: queued, nothing moves, the flight keeps going.
        val before = vm.uiState.value
        assertTrue(vm.requestAutoMove("tableau_0_1", tap, size))
        assertEquals(before, vm.uiState.value)
        assertEquals(listOf(first), vm.flights.value)
        // Once it lands the queued tap replays against the live state.
        vm.registerCardPosition(seven, Offset(5f, 5f), size)
        vm.landFlight(first.id)
        val k = vm.uiState.value.klondike!!
        assertEquals(1, k.tableauPiles[0].faceUp.size)
        assertEquals(seven, k.tableauPiles[1].faceUp.last())
        assertEquals(Offset(5f, 5f), vm.flights.value.single().from)
    }

    @Test fun dragOfCardInTheAirIsRefusedButOthersMayDrag() {
        val vm = vm()
        val seven = c(Suit.HEARTS, Rank.SEVEN)
        val king = c(Suit.CLUBS, Rank.KING)
        setK(
            vm,
            KlondikeState(
                waste = listOf(seven),
                tableauPiles = piles(TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.EIGHT))), TableauPile(faceUp = listOf(king))),
            ),
        )
        assertTrue(vm.requestAutoMove("waste", tap, size))
        assertFalse(vm.startDrag("tableau_0_0", Offset.Zero, size)) // carries the airborne seven
        assertFalse(vm.startDrag("tableau_0_1", Offset.Zero, size))
        assertTrue(vm.startDrag("tableau_1_0", Offset.Zero, size))
        assertEquals(listOf(king), vm.dragInfo.value!!.cards)
    }

    @Test fun undoAndGiveUpClearFlights() {
        val vm = vm()
        setK(vm, KlondikeState(waste = listOf(c(Suit.HEARTS, Rank.ACE))))
        assertTrue(vm.requestAutoMove("waste", tap))
        vm.undo()
        assertTrue(vm.flights.value.isEmpty())
        assertEquals(listOf(c(Suit.HEARTS, Rank.ACE)), vm.uiState.value.klondike!!.waste)
        assertTrue(vm.requestAutoMove("waste", tap))
        vm.giveUp()
        assertTrue(vm.flights.value.isEmpty())
    }

    @Test fun freeCellAceFliesToFoundation() {
        val vm = vm()
        val ace = c(Suit.HEARTS, Rank.ACE)
        setF(vm, FreeCellState(tableauPiles = listOf(listOf(ace)) + List(7) { emptyList() }))
        assertTrue(vm.requestAutoMove("tableau_0_0", tap))
        assertEquals(FlightTarget.Slot("foundation_0", 0, 0), vm.flights.value.single().target)
        val f = vm.uiState.value.freeCell!!
        assertTrue(f.tableauPiles[0].isEmpty())
        assertEquals(listOf(ace), f.foundations[0])
    }

    @Test fun freeCellCardFliesToFlatColumnSlotAndCell() {
        val vm = vm()
        val six = c(Suit.HEARTS, Rank.SIX)
        setF(
            vm,
            FreeCellState(
                tableauPiles = listOf(listOf(c(Suit.CLUBS, Rank.TWO), six), listOf(c(Suit.DIAMONDS, Rank.FOUR), c(Suit.SPADES, Rank.SEVEN))) +
                    List(6) { emptyList() },
            ),
        )
        vm.dropTargets["tableau_1"] = Rect(0f, 0f, 1f, 1f)
        assertTrue(vm.requestAutoMove("tableau_0_1", tap))
        assertEquals(FlightTarget.Slot("tableau_1", 0, 2), vm.flights.value.single().target)
    }

    @Test fun pileRangeCoversOnlyItsOwnCardsAndSlots() {
        val a = c(Suit.HEARTS, Rank.ACE)
        val b = c(Suit.HEARTS, Rank.TWO)
        val range = PileRange("tableau_0", 2, listOf(a, b))
        assertTrue(range.covers("tableau_0", 2, a))
        assertTrue(range.covers("tableau_0", 3, b))
        assertFalse(range.covers("tableau_0", 3, a)) // right slot, wrong card
        assertFalse(range.covers("tableau_1", 2, a))
        assertFalse(range.covers("tableau_0", 1, a))
        assertTrue(range.overlaps(PileRange("tableau_0", 3, listOf(b))))
        assertFalse(range.overlaps(PileRange("tableau_0", 4, listOf(b))))
        assertFalse(range.overlaps(PileRange("tableau_1", 2, listOf(a))))
    }
}
