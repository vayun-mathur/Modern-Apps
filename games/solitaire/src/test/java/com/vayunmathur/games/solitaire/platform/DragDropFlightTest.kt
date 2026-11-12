package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stack drags: the carried range, and the hand-off to a landing or returning flight. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DragDropFlightTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val king = Card(Suit.SPADES, Rank.KING)
    private val queen = Card(Suit.HEARTS, Rank.QUEEN)
    private val jack = Card(Suit.CLUBS, Rank.JACK)
    private val size = IntSize(60, 90)

    private fun vm(vararg piles: TableauPile): SolitaireViewModel {
        val vm = SolitaireViewModel(app)
        vm.uiStateInternal.value = SolitaireUiState(
            gameMode = GameMode.KLONDIKE,
            klondike = KlondikeState(tableauPiles = piles.toList() + List(7 - piles.size) { TableauPile() }),
        )
        return vm
    }

    @Test fun dragCarriesTheRangeAboveTheGrabbedCard() {
        val vm = vm(TableauPile(faceDown = listOf(Card(Suit.CLUBS, Rank.TWO)), faceUp = listOf(king, queen, jack)))
        assertTrue(vm.startDrag("tableau_0_1", Offset(5f, 30f), size))
        val range = vm.dragInfo.value!!.range
        assertEquals(PileRange("tableau_0", 1, listOf(queen, jack)), range)
        // Every carried card is hidden in the source; the one beneath is not.
        assertTrue(range.covers("tableau_0", 1, queen))
        assertTrue(range.covers("tableau_0", 2, jack))
        assertFalse(range.covers("tableau_0", 0, king))
    }

    @Test fun rejectedDropFliesTheWholeStackBackHome() {
        val vm = vm(TableauPile(faceUp = listOf(king, queen, jack)))
        val home = Offset(5f, 30f)
        assertTrue(vm.startDrag("tableau_0_1", home, size))
        vm.updateDrag(Offset(200f, 300f))
        val before = vm.uiState.value
        vm.endDrag(Offset(230f, 345f), size) // no drop targets at all
        assertNull(vm.dragInfo.value)
        assertEquals(before, vm.uiState.value)
        val flight = vm.flights.value.single()
        assertEquals(listOf(queen, jack), flight.cards)
        assertEquals(Offset(200f, 300f), flight.from)
        assertEquals(FlightTarget.Point(home), flight.target)
        assertEquals(PileRange("tableau_0", 1, listOf(queen, jack)), flight.hides)
        vm.landFlight(flight.id)
        assertTrue(vm.flights.value.isEmpty())
    }

    @Test fun acceptedStackDropLandsOnTheDestinationsNextSlot() {
        val redKing = Card(Suit.DIAMONDS, Rank.KING)
        val vm = vm(
            TableauPile(faceUp = listOf(redKing, Card(Suit.SPADES, Rank.QUEEN), Card(Suit.HEARTS, Rank.JACK))),
            TableauPile(faceUp = listOf(Card(Suit.HEARTS, Rank.KING))),
        )
        vm.dropTargets["tableau_1"] = Rect(100f, 0f, 160f, 90f)
        assertTrue(vm.startDrag("tableau_0_1", Offset(5f, 30f), size))
        vm.updateDrag(Offset(110f, 20f))
        vm.endDrag(Offset(140f, 65f), size)
        assertEquals(1, vm.uiState.value.klondike!!.tableauPiles[0].faceUp.size)
        val flight = vm.flights.value.single()
        assertEquals(Offset(110f, 20f), flight.from)
        assertEquals(FlightTarget.Slot("tableau_1", 0, 1), flight.target)
        assertEquals(flight.cards, vm.uiState.value.klondike!!.tableauPiles[1].faceUp.drop(1))
    }
}
