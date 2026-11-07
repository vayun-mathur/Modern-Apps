package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.GameConfig
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

/** Klondike moves, foundation rules, auto-flip and undo through the ViewModel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolitaireMovesTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = SolitaireViewModel(app)

    private fun setKlondike(vm: SolitaireViewModel, state: KlondikeState) {
        vm.uiStateInternal.value = SolitaireUiState(
            gameMode = com.vayunmathur.games.solitaire.data.GameMode.KLONDIKE, klondike = state,
        )
    }

    private fun piles(vararg tops: Card?): List<TableauPile> =
        (0 until 7).map { i -> TableauPile(faceUp = listOfNotNull(tops.getOrNull(i))) }

    @Test
    fun wasteToTableauLegalAndIllegal() {
        val vm = vm()
        val blackKing = Card(Suit.SPADES, Rank.KING)
        val redQueen = Card(Suit.HEARTS, Rank.QUEEN)
        setKlondike(vm, KlondikeState(waste = listOf(redQueen), tableauPiles = piles(blackKing)))
        vm.klondikeMoveWasteToTableau(0)
        var k = vm.uiState.value.klondike!!
        assertEquals(0, k.waste.size)
        assertEquals(listOf(blackKing, redQueen), k.tableauPiles[0].faceUp)
        assertEquals(1, k.moveCount)
        // Illegal: red queen onto red king-coloured pile top (same colour) is rejected.
        val redKing = Card(Suit.DIAMONDS, Rank.KING)
        setKlondike(vm, KlondikeState(waste = listOf(redQueen), tableauPiles = piles(redKing)))
        vm.klondikeMoveWasteToTableau(0)
        k = vm.uiState.value.klondike!!
        assertEquals(1, k.waste.size)
        assertEquals(0, k.moveCount)
    }

    @Test
    fun wasteToFoundationRequiresAceThenAscendingSameSuit() {
        val vm = vm()
        val aceHearts = Card(Suit.HEARTS, Rank.ACE)
        val twoHearts = Card(Suit.HEARTS, Rank.TWO)
        val twoSpades = Card(Suit.SPADES, Rank.TWO)
        // Foundation index 0 is Hearts (Suit.entries[0]).
        setKlondike(vm, KlondikeState(waste = listOf(twoHearts)))
        vm.klondikeMoveWasteToFoundation(0)
        assertEquals(1, vm.uiState.value.klondike!!.waste.size)
        setKlondike(vm, KlondikeState(waste = listOf(aceHearts)))
        vm.klondikeMoveWasteToFoundation(0)
        var k = vm.uiState.value.klondike!!
        assertEquals(listOf(aceHearts), k.foundations[0])
        // Wrong suit onto built foundation rejected; right suit ascending accepted.
        setKlondike(vm, k.copy(waste = listOf(twoSpades)))
        vm.klondikeMoveWasteToFoundation(0)
        assertEquals(1, vm.uiState.value.klondike!!.foundations[0].size)
        setKlondike(vm, vm.uiState.value.klondike!!.copy(waste = listOf(twoHearts)))
        vm.klondikeMoveWasteToFoundation(0)
        assertEquals(2, vm.uiState.value.klondike!!.foundations[0].size)
    }

    @Test
    fun tableauToTableauFlipsExposedCard() {
        val vm = vm()
        val down = Card(Suit.CLUBS, Rank.THREE)
        val mover = Card(Suit.HEARTS, Rank.QUEEN)
        val target = Card(Suit.SPADES, Rank.KING)
        val state = KlondikeState(
            tableauPiles = listOf(
                TableauPile(faceDown = listOf(down), faceUp = listOf(mover)),
                TableauPile(faceUp = listOf(target)),
            ) + List(5) { TableauPile() },
        )
        setKlondike(vm, state)
        vm.klondikeMoveTableauToTableau(0, 0, 1)
        val k = vm.uiState.value.klondike!!
        assertEquals(listOf(target, mover), k.tableauPiles[1].faceUp)
        // Source pile auto-flips its last face-down card.
        assertEquals(emptyList(), k.tableauPiles[0].faceDown)
        assertEquals(listOf(down), k.tableauPiles[0].faceUp)
    }

    @Test
    fun undoRestoresPreviousStateAndMarksUsedUndo() {
        val vm = vm()
        vm.newKlondikeGame(GameConfig())
        val before = vm.uiState.value.klondike!!
        vm.drawFromStock()
        assertEquals(before.stock.size - 1, vm.uiState.value.klondike!!.stock.size)
        vm.undo()
        val after = vm.uiState.value.klondike!!
        assertEquals(before.stock, after.stock)
        assertEquals(before.waste, after.waste)
        assertTrue(after.usedUndo)
    }

    @Test
    fun freeCellSequenceAndPlacement() {
        val vm = vm()
        val seq = listOf(Card(Suit.SPADES, Rank.SEVEN), Card(Suit.HEARTS, Rank.SIX), Card(Suit.CLUBS, Rank.FIVE))
        assertTrue(vm.isFreeCellSequence(seq))
        assertTrue(vm.isFreeCellSequence(listOf(seq[0])))
        assertTrue(vm.isFreeCellSequence(emptyList()))
        val broken = listOf(Card(Suit.SPADES, Rank.SEVEN), Card(Suit.SPADES, Rank.SIX))
        assertFalse(vm.isFreeCellSequence(broken))
        assertTrue(vm.canPlaceOnFreeCellTableau(Card(Suit.HEARTS, Rank.SIX), listOf(Card(Suit.SPADES, Rank.SEVEN))))
        assertFalse(vm.canPlaceOnFreeCellTableau(Card(Suit.CLUBS, Rank.SIX), listOf(Card(Suit.SPADES, Rank.SIX))))
        assertTrue(vm.canPlaceOnFreeCellTableau(Card(Suit.CLUBS, Rank.SIX), emptyList()))
    }

    @Test
    fun spiderSequenceRequiresDescendingSameSuitRuns() {
        val vm = vm()
        val run = listOf(Card(Suit.SPADES, Rank.NINE), Card(Suit.SPADES, Rank.EIGHT), Card(Suit.SPADES, Rank.SEVEN))
        assertTrue(vm.isSpiderSequence(run))
        val mixed = listOf(Card(Suit.SPADES, Rank.NINE), Card(Suit.HEARTS, Rank.EIGHT))
        assertFalse(vm.isSpiderSequence(mixed))
        // Any descending card may land on a tableau top one rank higher (suit-free).
        assertTrue(vm.canPlaceOnSpiderTableau(Card(Suit.HEARTS, Rank.EIGHT), TableauPile(faceUp = listOf(Card(Suit.SPADES, Rank.NINE)))))
        assertTrue(vm.canPlaceOnSpiderTableau(Card(Suit.HEARTS, Rank.EIGHT), TableauPile()))
        assertFalse(vm.canPlaceOnSpiderTableau(Card(Suit.HEARTS, Rank.NINE), TableauPile(faceUp = listOf(Card(Suit.SPADES, Rank.NINE)))))
    }
}
