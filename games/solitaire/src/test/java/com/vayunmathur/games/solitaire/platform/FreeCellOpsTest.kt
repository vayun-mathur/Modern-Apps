package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.FreeCellState
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.Suit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** FreeCell moves, capacity rule, drops and auto-move. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeCellOpsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = SolitaireViewModel(app)
    private fun c(suit: Suit, rank: Rank) = Card(suit, rank)

    private fun setF(vm: SolitaireViewModel, state: FreeCellState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.FREECELL, freeCell = state)
    }

    private fun state(
        piles: List<List<Card>>,
        cells: List<Card?> = List(4) { null },
        foundations: List<List<Card>> = List(4) { emptyList() },
    ): FreeCellState = FreeCellState(
        tableauPiles = piles + List(8 - piles.size) { emptyList() },
        freeCells = cells,
        foundations = foundations,
    )

    @Test fun moveToAndFromFreeCell() {
        val vm = vm()
        val seven = c(Suit.SPADES, Rank.SEVEN)
        setF(vm, state(listOf(listOf(seven))))
        vm.freeCellMoveToFreeCell(0, 2)
        var f = vm.uiState.value.freeCell!!
        assertEquals(seven, f.freeCells[2])
        assertTrue(f.tableauPiles[0].isEmpty())
        // Occupied cell and empty source reject.
        setF(vm, state(listOf(listOf(c(Suit.HEARTS, Rank.SIX))), cells = listOf(seven, null, null, null)))
        vm.freeCellMoveToFreeCell(0, 0)
        assertEquals(0, vm.uiState.value.freeCell!!.moveCount)
        vm.freeCellMoveToFreeCell(5, 1)
        assertEquals(0, vm.uiState.value.freeCell!!.moveCount)
        // Back out onto a black seven.
        setF(vm, state(listOf(listOf(c(Suit.SPADES, Rank.SEVEN))), cells = listOf(c(Suit.HEARTS, Rank.SIX), null, null, null)))
        vm.freeCellMoveFromFreeCell(0, 0)
        f = vm.uiState.value.freeCell!!
        assertEquals(listOf(c(Suit.SPADES, Rank.SEVEN), c(Suit.HEARTS, Rank.SIX)), f.tableauPiles[0])
        assertEquals(null, f.freeCells[0])
        // Illegal placement (same colour sequence break) rejects; empty cell source rejects.
        setF(vm, state(listOf(listOf(c(Suit.CLUBS, Rank.SEVEN))), cells = listOf(c(Suit.SPADES, Rank.SIX), null, null, null)))
        vm.freeCellMoveFromFreeCell(0, 0)
        assertEquals(0, vm.uiState.value.freeCell!!.moveCount)
        vm.freeCellMoveFromFreeCell(3, 0)
        assertEquals(0, vm.uiState.value.freeCell!!.moveCount)
    }

    @Test fun freeCellAndTableauToFoundationAndWin() {
        val vm = vm()
        val ace = c(Suit.HEARTS, Rank.ACE)
        setF(vm, state(listOf(emptyList()), cells = listOf(ace, null, null, null)))
        vm.freeCellMoveFreeCellToFoundation(0, 0)
        var f = vm.uiState.value.freeCell!!
        assertEquals(listOf(ace), f.foundations[0])
        assertEquals(null, f.freeCells[0])
        // Wrong foundation rejects.
        setF(vm, state(listOf(listOf(c(Suit.HEARTS, Rank.TWO)))))
        vm.freeCellMoveTableauToFoundation(0, 1)
        assertEquals(0, vm.uiState.value.freeCell!!.moveCount)
        vm.freeCellMoveTableauToFoundation(0, 0)
        assertEquals(0, vm.uiState.value.freeCell!!.moveCount) // needs ace first
        setF(vm, state(listOf(listOf(ace))))
        vm.freeCellMoveTableauToFoundation(0, 0)
        assertEquals(listOf(ace), vm.uiState.value.freeCell!!.foundations[0])
        // Empty pile rejects.
        vm.freeCellMoveTableauToFoundation(4, 0)
        // Win: foundations all but one king complete, last king in a cell.
        val foundations = Suit.entries.map { suit ->
            if (suit == Suit.CLUBS) Rank.entries.filter { it != Rank.KING }.map { Card(suit, it) }
            else Rank.entries.map { Card(suit, it) }
        }
        setF(
            vm,
            state(listOf(emptyList()), cells = listOf(c(Suit.CLUBS, Rank.KING), null, null, null), foundations = foundations),
        )
        vm.freeCellMoveFreeCellToFoundation(0, 3)
        assertTrue(vm.uiState.value.freeCell!!.isWon)
    }

    @Test fun tableauToTableauSequenceAndCapacity() {
        val vm = vm()
        val run = listOf(c(Suit.SPADES, Rank.SEVEN), c(Suit.HEARTS, Rank.SIX), c(Suit.CLUBS, Rank.FIVE))
        val target = listOf(c(Suit.HEARTS, Rank.EIGHT))
        // With all 4 cells free, a 3-run can move.
        setF(vm, state(listOf(run, target)))
        vm.freeCellMoveTableauToTableau(0, 0, 1)
        var f = vm.uiState.value.freeCell!!
        assertEquals(target + run, f.tableauPiles[1])
        // Fill every free cell and every other column: capacity is 1, so the
        // same 3-run cannot move, but a single card still can.
        val full = listOf(c(Suit.CLUBS, Rank.ACE), c(Suit.CLUBS, Rank.TWO), c(Suit.CLUBS, Rank.THREE), c(Suit.CLUBS, Rank.FOUR))
        val fillers = List(6) { listOf(c(Suit.CLUBS, Rank.SEVEN)) }
        setF(vm, state(listOf(run, target) + fillers, cells = full.map { it as Card? }))
        vm.freeCellMoveTableauToTableau(0, 0, 1)
        assertEquals(0, vm.uiState.value.freeCell!!.moveCount)
        // A single card (the filler seven onto the red eight) still can.
        vm.freeCellMoveTableauToTableau(2, 0, 1)
        assertEquals(1, vm.uiState.value.freeCell!!.moveCount)
        // Broken sequence rejects even the sub-run start.
        val broken = listOf(c(Suit.SPADES, Rank.SEVEN), c(Suit.SPADES, Rank.SIX))
        setF(vm, state(listOf(broken, target)))
        vm.freeCellMoveTableauToTableau(0, 0, 1)
        assertEquals(0, vm.uiState.value.freeCell!!.moveCount)
        // Out-of-range indices reject.
        vm.freeCellMoveTableauToTableau(0, -1, 1)
        vm.freeCellMoveTableauToTableau(0, 9, 1)
        assertEquals(0, vm.uiState.value.freeCell!!.moveCount)
    }

    @Test fun capacityGrowsWithEmptyColumns() {
        val vm = vm()
        val run = listOf(c(Suit.SPADES, Rank.SEVEN), c(Suit.HEARTS, Rank.SIX))
        val target = listOf(c(Suit.HEARTS, Rank.EIGHT))
        // No free cells but one other empty column: maxMove = 1 * 2 = 2.
        val cells = List<Card?>(4) { c(Suit.CLUBS, Rank.ACE) }
        // Exactly one other column empty: maxMove = (1 + 0) * 2^1 = 2.
        val fillers = List(5) { listOf(c(Suit.CLUBS, Rank.KING)) }
        setF(vm, state(listOf(run, target) + fillers + listOf(emptyList()), cells = cells))
        vm.freeCellMoveTableauToTableau(0, 0, 1)
        assertEquals(1, vm.uiState.value.freeCell!!.moveCount)
    }

    @Test fun autoMoveFoundationTableauAndEmptyCellFallback() {
        val vm = vm()
        // Tableau ace prefers foundation.
        setF(vm, state(listOf(listOf(c(Suit.HEARTS, Rank.ACE)))))
        vm.autoMove("tableau_0_0")
        assertEquals(1, vm.uiState.value.freeCell!!.foundations[0].size)
        // Cell card to tableau.
        setF(vm, state(listOf(listOf(c(Suit.SPADES, Rank.SEVEN))), cells = listOf(c(Suit.HEARTS, Rank.SIX), null, null, null)))
        vm.autoMove("freecell_0")
        var f = vm.uiState.value.freeCell!!
        assertEquals(2, f.tableauPiles[0].size)
        assertEquals(null, f.freeCells[0])
        // Tableau run to another tableau.
        val run = listOf(c(Suit.SPADES, Rank.SEVEN), c(Suit.HEARTS, Rank.SIX))
        setF(vm, state(listOf(run, listOf(c(Suit.HEARTS, Rank.EIGHT)))))
        vm.autoMove("tableau_0_0")
        f = vm.uiState.value.freeCell!!
        assertTrue(f.tableauPiles[0].isEmpty())
        assertEquals(3, f.tableauPiles[1].size)
        // No foundation/tableau spot (every other column topped by a queen,
        // the rest empty is not the case here): the top card parks in a cell.
        val pile = listOf(c(Suit.SPADES, Rank.KING), c(Suit.HEARTS, Rank.QUEEN))
        val blockers = List(7) { listOf(c(Suit.CLUBS, Rank.QUEEN)) }
        setF(vm, state(listOf(pile) + blockers))
        vm.autoMove("tableau_0_1")
        f = vm.uiState.value.freeCell!!
        assertEquals(c(Suit.HEARTS, Rank.QUEEN), f.freeCells[0])
        // Bad sources no-op.
        vm.autoMove("waste")
        vm.autoMove("tableau_x")
        vm.autoMove("freecell_9")
        assertEquals(1, vm.uiState.value.freeCell!!.moveCount)
    }

    @Test fun wonGameIgnoresMoves() {
        val vm = vm()
        val won = state(listOf(listOf(c(Suit.HEARTS, Rank.ACE)))).copy(isWon = true)
        setF(vm, won)
        vm.freeCellMoveToFreeCell(0, 0)
        vm.autoMove("tableau_0_0")
        assertEquals(0, vm.uiState.value.freeCell!!.moveCount)
        assertEquals(null, vm.uiState.value.freeCell!!.freeCells[0])
    }
}
