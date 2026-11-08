package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.compose.ui.geometry.Offset
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tap-to-move flights: staging, landing, and refusal cases. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolitaireFlightTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = SolitaireViewModel(app)
    private fun c(suit: Suit, rank: Rank) = Card(suit, rank)
    private val tap = Offset(10f, 20f)

    private fun setK(vm: SolitaireViewModel, state: KlondikeState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.KLONDIKE, klondike = state)
    }

    private fun setF(vm: SolitaireViewModel, state: FreeCellState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.FREECELL, freeCell = state)
    }

    @Test fun wasteAceStagesFoundationFlightAndLandsOnCommit() {
        val vm = vm()
        setK(vm, KlondikeState(waste = listOf(c(Suit.HEARTS, Rank.ACE))))
        assertTrue(vm.requestAutoMove("waste", tap))
        val flight = vm.flightInfo.value
        assertEquals(listOf(c(Suit.HEARTS, Rank.ACE)), flight?.cards)
        assertEquals("waste", flight?.sourceId)
        assertEquals("foundation_0", flight?.targetId)
        // Staging moves nothing: the card is still in the waste until landing.
        assertEquals(listOf(c(Suit.HEARTS, Rank.ACE)), vm.uiState.value.klondike!!.waste)
        vm.commitFlight()
        assertNull(vm.flightInfo.value)
        val k = vm.uiState.value.klondike!!
        assertTrue(k.waste.isEmpty())
        assertEquals(listOf(c(Suit.HEARTS, Rank.ACE)), k.foundations[0])
    }

    @Test fun wasteCardStagesTableauFlightWhenNoFoundationFits() {
        val vm = vm()
        val eightSpades = c(Suit.SPADES, Rank.EIGHT)
        setK(
            vm,
            KlondikeState(
                waste = listOf(c(Suit.HEARTS, Rank.SEVEN)),
                tableauPiles = listOf(TableauPile(faceUp = listOf(eightSpades))) +
                    List(6) { TableauPile() },
            ),
        )
        assertTrue(vm.requestAutoMove("waste", tap))
        assertEquals("tableau_0", vm.flightInfo.value?.targetId)
        vm.commitFlight()
        val k = vm.uiState.value.klondike!!
        assertTrue(k.waste.isEmpty())
        assertEquals(listOf(eightSpades, c(Suit.HEARTS, Rank.SEVEN)), k.tableauPiles[0].faceUp)
    }

    @Test fun requestRefusedWithoutLegalMoveOrWhileFlying() {
        val vm = vm()
        setK(vm, KlondikeState(waste = emptyList()))
        assertFalse(vm.requestAutoMove("waste", tap))
        assertFalse(vm.requestAutoMove("nope", tap))
        assertNull(vm.flightInfo.value)
        // A lone king with an empty board always has somewhere to go; the second
        // request while it is still staged is the one refused.
        setK(vm, KlondikeState(waste = listOf(c(Suit.SPADES, Rank.KING))))
        assertTrue(vm.requestAutoMove("waste", tap))
        assertFalse(vm.requestAutoMove("waste", tap))
    }

    @Test fun commitWithoutFlightIsNoOp() {
        val vm = vm()
        setK(vm, KlondikeState(waste = listOf(c(Suit.HEARTS, Rank.ACE))))
        vm.commitFlight()
        vm.cancelFlight()
        assertEquals(0, vm.uiState.value.klondike!!.moveCount)
        assertEquals(listOf(c(Suit.HEARTS, Rank.ACE)), vm.uiState.value.klondike!!.waste)
    }

    @Test fun undoAndGiveUpClearStagedFlight() {
        val vm = vm()
        setK(vm, KlondikeState(waste = listOf(c(Suit.HEARTS, Rank.ACE))))
        assertTrue(vm.requestAutoMove("waste", tap))
        vm.undo()
        assertNull(vm.flightInfo.value)
        assertTrue(vm.requestAutoMove("waste", tap))
        vm.giveUp()
        assertNull(vm.flightInfo.value)
    }

    @Test fun freeCellAceStagesFoundationFlight() {
        val vm = vm()
        val ace = c(Suit.HEARTS, Rank.ACE)
        setF(vm, FreeCellState(tableauPiles = listOf(listOf(ace)) + List(7) { emptyList() }))
        assertTrue(vm.requestAutoMove("tableau_0_0", tap))
        assertEquals("foundation_0", vm.flightInfo.value?.targetId)
        vm.commitFlight()
        val f = vm.uiState.value.freeCell!!
        assertTrue(f.tableauPiles[0].isEmpty())
        assertEquals(listOf(ace), f.foundations[0])
    }
}
