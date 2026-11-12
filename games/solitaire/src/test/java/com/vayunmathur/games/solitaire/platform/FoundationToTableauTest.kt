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

/** Klondike: dragging the top foundation card back down onto the tableau. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoundationToTableauTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val size = IntSize(60, 90)

    /** Hearts A-5 on foundation 0 (Suit.entries order), clubs A on foundation 3. */
    private val foundations = listOf(
        Rank.entries.take(5).map { Card(Suit.HEARTS, it) },
        emptyList(),
        emptyList(),
        listOf(Card(Suit.CLUBS, Rank.ACE)),
    )

    private fun state(vararg piles: TableauPile) = KlondikeState(
        tableauPiles = piles.toList() + List(7 - piles.size) { TableauPile() },
        foundations = foundations,
    )

    private fun vm(state: KlondikeState) = SolitaireViewModel(app).also {
        it.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.KLONDIKE, klondike = state)
    }

    @Test fun ruleIsAlternateColourOneRankLowerOrKingOnEmpty() {
        val s = state(
            TableauPile(faceUp = listOf(Card(Suit.SPADES, Rank.SIX))),
            TableauPile(faceUp = listOf(Card(Suit.DIAMONDS, Rank.SIX))),
            TableauPile(faceUp = listOf(Card(Suit.SPADES, Rank.SEVEN))),
            TableauPile(faceDown = listOf(Card(Suit.SPADES, Rank.SIX))),
        )
        assertTrue(canMoveKlondikeFoundationToTableau(s, 0, 0)) // 5♥ on 6♠
        assertFalse(canMoveKlondikeFoundationToTableau(s, 0, 1)) // same colour
        assertFalse(canMoveKlondikeFoundationToTableau(s, 0, 2)) // two ranks apart
        assertFalse(canMoveKlondikeFoundationToTableau(s, 0, 3)) // face-down only
        assertFalse(canMoveKlondikeFoundationToTableau(s, 0, 4)) // empty column takes only a King
        assertFalse(canMoveKlondikeFoundationToTableau(s, 1, 0)) // empty foundation
        assertFalse(canMoveKlondikeFoundationToTableau(s, 9, 0))
        assertFalse(canMoveKlondikeFoundationToTableau(s, 0, 9))
        val kingUp = s.copy(foundations = listOf(Rank.entries.map { Card(Suit.HEARTS, it) }) + s.foundations.drop(1))
        assertTrue(canMoveKlondikeFoundationToTableau(kingUp, 0, 4))
    }

    @Test fun dragDownIsANormalMoveWithUndo() {
        val six = Card(Suit.SPADES, Rank.SIX)
        val vm = vm(state(TableauPile(faceUp = listOf(six))))
        vm.dropTargets["foundation_0"] = Rect(0f, 0f, 60f, 90f)
        vm.dropTargets["tableau_0"] = Rect(0f, 200f, 60f, 290f)
        assertTrue(vm.startDrag("foundation_0", Offset.Zero, size))
        assertEquals(PileRange("foundation_0", 4, listOf(Card(Suit.HEARTS, Rank.FIVE))), vm.dragInfo.value!!.range)
        vm.updateDrag(Offset(0f, 210f))
        vm.endDrag(Offset(30f, 255f), size)
        val k = vm.uiState.value.klondike!!
        assertEquals(listOf(six, Card(Suit.HEARTS, Rank.FIVE)), k.tableauPiles[0].faceUp)
        assertEquals(4, k.foundations[0].size)
        assertEquals(1, k.moveCount)
        assertEquals(1, vm.uiState.value.history.size)
        assertEquals(FlightTarget.Slot("tableau_0", 0, 1), vm.flights.value.single().target)
        vm.undo()
        assertEquals(5, vm.uiState.value.klondike!!.foundations[0].size)
        assertEquals(listOf(six), vm.uiState.value.klondike!!.tableauPiles[0].faceUp)
    }

    @Test fun illegalDropGoesBackToTheFoundation() {
        val vm = vm(state(TableauPile(faceUp = listOf(Card(Suit.DIAMONDS, Rank.SIX)))))
        vm.dropTargets["tableau_0"] = Rect(0f, 200f, 60f, 290f)
        assertTrue(vm.startDrag("foundation_0", Offset(1f, 2f), size))
        vm.endDrag(Offset(30f, 255f), size)
        assertEquals(5, vm.uiState.value.klondike!!.foundations[0].size)
        assertEquals(0, vm.uiState.value.klondike!!.moveCount)
        assertEquals(FlightTarget.Point(Offset(1f, 2f)), vm.flights.value.single().target)
    }

    @Test fun emptyFoundationAndFreeCellFoundationsCannotBeDragged() {
        val vm = vm(state())
        assertFalse(vm.startDrag("foundation_1", Offset.Zero, size))
        // Tap on a foundation card has no move to make (unchanged behaviour).
        assertFalse(vm.requestAutoMove("foundation_0", Offset.Zero, size))
        vm.uiStateInternal.value = SolitaireUiState(
            gameMode = GameMode.FREECELL,
            freeCell = FreeCellState(foundations = foundations),
        )
        assertFalse(vm.startDrag("foundation_0", Offset.Zero, size))
    }
}
