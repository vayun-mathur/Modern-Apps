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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tap-to-move planning, flight bookkeeping and animated application. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolitaireAnimationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = SolitaireViewModel(app)
    private fun c(suit: Suit, rank: Rank) = Card(suit, rank)

    private fun setK(vm: SolitaireViewModel, state: KlondikeState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.KLONDIKE, klondike = state)
    }

    private fun sevenPiles(vararg piles: TableauPile): List<TableauPile> =
        piles.toList() + List(7 - piles.size) { TableauPile() }

    @Test fun klondikePlanPicksFoundationThenTableau() {
        val vm = vm()
        // Waste ace: foundation is the preferred destination.
        setK(vm, KlondikeState(waste = listOf(c(Suit.HEARTS, Rank.ACE))))
        var plan = assertNotNull(vm.planKlondikeAutoMove("waste"))
        assertEquals("foundation_0", plan.targetId)
        // Planning alone must not move anything.
        assertEquals(0, vm.uiState.value.klondike!!.moveCount)
        plan.apply.invoke()
        assertEquals(listOf(c(Suit.HEARTS, Rank.ACE)), vm.uiState.value.klondike!!.foundations[0])
        // Waste queen with no foundation spot: tableau onto the black king.
        setK(
            vm,
            KlondikeState(
                waste = listOf(c(Suit.HEARTS, Rank.QUEEN)),
                tableauPiles = sevenPiles(TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.KING)))),
            ),
        )
        plan = assertNotNull(vm.planKlondikeAutoMove("waste"))
        assertEquals("tableau_0", plan.targetId)
        // No legal move: no plan.
        setK(vm, KlondikeState(waste = listOf(c(Suit.HEARTS, Rank.QUEEN))))
        assertNull(vm.planKlondikeAutoMove("waste"))
        assertNull(vm.planKlondikeAutoMove("nonsense"))
    }

    @Test fun freeCellPlanCoversFoundationTableauAndCell() {
        val vm = vm()
        fun setF(state: FreeCellState) {
            vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.FREECELL, freeCell = state)
        }
        // Ace to its foundation.
        setF(FreeCellState(tableauPiles = listOf(listOf(c(Suit.HEARTS, Rank.ACE))) + List(7) { emptyList() }))
        assertEquals("foundation_0", assertNotNull(vm.planFreeCellAutoMove("tableau_0_0")).targetId)
        // Red seven onto a black eight.
        setF(
            FreeCellState(
                tableauPiles = listOf(listOf(c(Suit.HEARTS, Rank.SEVEN)), listOf(c(Suit.SPADES, Rank.EIGHT))) +
                    List(6) { emptyList() },
            ),
        )
        assertEquals("tableau_1", assertNotNull(vm.planFreeCellAutoMove("tableau_0_0")).targetId)
        // Nothing else fits: a lone card falls back to the first empty cell.
        setF(FreeCellState(tableauPiles = listOf(listOf(c(Suit.HEARTS, Rank.SEVEN))) + List(7) { emptyList() }))
        assertEquals("freecell_0", assertNotNull(vm.planFreeCellAutoMove("tableau_0_0")).targetId)
    }

    @Test fun animatedMoveHidesSourceAndStackBelow() {
        val flight = AnimatedMove(
            id = 1,
            cards = listOf(c(Suit.HEARTS, Rank.QUEEN)),
            sourceId = "tableau_2_1",
            targetId = "foundation_0",
            from = Rect(0f, 0f, 60f, 84f),
            to = Rect(100f, 0f, 160f, 84f),
            durationMillis = TAP_MOVE_MILLIS,
        )
        assertTrue(flight.hides("tableau_2_1"))
        assertTrue(flight.hides("tableau_2_3")) // below the grabbed card: travelling too
        assertFalse(flight.hides("tableau_2_0")) // above it: stays on the pile
        assertFalse(flight.hides("tableau_3_1"))
        assertFalse(flight.hides("waste"))
        val wasteFlight = flight.copy(sourceId = "waste")
        assertTrue(wasteFlight.hides("waste"))
        assertFalse(wasteFlight.hides("tableau_2_1"))
    }

    @Test fun landingRectForTableauAndFoundation() {
        val pile = Rect(10f, 20f, 70f, 300f)
        val card = Rect(0f, 0f, 60f, 84f)
        // Tableau: land on the pile's bottom edge.
        assertEquals(Rect(10f, 216f, 70f, 300f), landingRect("tableau_0", pile, card))
        // Foundation: land on the pile's top edge.
        assertEquals(Rect(10f, 20f, 70f, 104f), landingRect("foundation_0", pile, card))
        assertEquals(Rect(10f, 20f, 70f, 104f), landingRect("freecell_0", pile, card))
    }

    @Test fun animatedTapMoveFliesThenApplies() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = vm()
            val ace = c(Suit.HEARTS, Rank.ACE)
            setK(vm, KlondikeState(waste = listOf(ace)))
            vm.dropTargets["foundation_0"] = Rect(200f, 0f, 260f, 84f)
            vm.autoMoveAnimated("waste", Offset(0f, 0f), IntSize(60, 84))
            // The flight is up and the card has not moved yet.
            val flight = assertNotNull(vm.animatedMove.value)
            assertEquals(listOf(ace), flight.cards)
            assertEquals("foundation_0", flight.targetId)
            assertEquals(Rect(0f, 0f, 60f, 84f), flight.from)
            assertEquals(Rect(200f, 0f, 260f, 84f), flight.to)
            assertTrue(vm.uiState.value.klondike!!.waste.isNotEmpty())
            assertTrue(vm.isMoveAnimating())
            // A second animated move and an undo are ignored mid-flight.
            vm.autoMoveAnimated("waste", Offset(0f, 0f), IntSize(60, 84))
            advanceTimeBy(TAP_MOVE_MILLIS.toLong())
            runCurrent()
            // Landed: the move applied and the flight cleared.
            assertNull(vm.animatedMove.value)
            assertTrue(vm.uiState.value.klondike!!.waste.isEmpty())
            assertEquals(listOf(ace), vm.uiState.value.klondike!!.foundations[0])
            assertEquals(1, vm.uiState.value.klondike!!.moveCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** Foundations one king short per suit, kings in stock/waste/tableau. */
    private fun almostWonState(): KlondikeState {
        val foundations = Suit.entries.map { suit ->
            Rank.entries.filter { it != Rank.KING }.map { Card(suit, it) }
        }
        return KlondikeState(
            stock = listOf(c(Suit.SPADES, Rank.KING)),
            waste = listOf(c(Suit.HEARTS, Rank.KING)),
            tableauPiles = sevenPiles(
                TableauPile(faceUp = listOf(c(Suit.DIAMONDS, Rank.KING))),
                TableauPile(faceUp = listOf(c(Suit.CLUBS, Rank.KING))),
            ),
            foundations = foundations,
            elapsedSeconds = 42,
        )
    }

    @Test fun autoCompleteSimulationIsLegalStepSequence() {
        val vm = vm()
        val state = almostWonState()
        setK(vm, state)
        val (steps, finalState) = vm.simulateKlondikeAutoComplete(state)
        // Four kings fly: waste hearts, two tableau kings, then the stock spade.
        assertEquals(4, steps.size)
        assertEquals(listOf("waste", "tableau_0_0", "tableau_1_0", "waste"), steps.map { it.sourceId })
        assertEquals(listOf(0, 1, 3, 2), steps.map { it.foundationIndex })
        // Every step moves exactly the foundation count up by one and
        // chains: each step starts where the previous one ended.
        var previous = state
        for (step in steps) {
            val before = previous.foundations.sumOf { it.size }
            val after = step.stateAfter.foundations.sumOf { it.size }
            assertEquals(before + 1, after)
            assertEquals(step.card, step.stateAfter.foundations[step.foundationIndex].last())
            previous = step.stateAfter
        }
        assertTrue(finalState.foundations.all { it.size == 13 })
        assertTrue(finalState.stock.isEmpty() && finalState.waste.isEmpty())
        // The simulation agrees with the instant completion's end state.
        vm.klondikeAutoComplete()
        val instant = vm.uiState.value.klondike!!
        assertEquals(finalState.foundations, instant.foundations)
        assertEquals(finalState.moveCount, instant.moveCount)
        assertTrue(instant.isWon)
    }

    @Test fun animatedAutoCompleteCascadesAndUndoesAsOneStep() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = vm()
            val state = almostWonState()
            setK(vm, state)
            vm.cardPositions["waste"] = Rect(0f, 0f, 60f, 84f)
            vm.cardPositions["tableau_0_0"] = Rect(100f, 200f, 160f, 284f)
            vm.cardPositions["tableau_1_0"] = Rect(200f, 200f, 260f, 284f)
            for (fi in 0 until 4) vm.dropTargets["foundation_$fi"] = Rect(300f + fi * 70f, 0f, 360f + fi * 70f, 84f)
            vm.klondikeAutoCompleteAnimated()
            runCurrent()
            // First flight is up; nothing has landed yet, one history entry.
            assertNotNull(vm.animatedMove.value)
            assertEquals(12, vm.uiState.value.klondike!!.foundations[0].size)
            assertEquals(1, vm.uiState.value.history.size)
            // Let the whole cascade play out.
            advanceTimeBy((AUTO_COMPLETE_STEP_MILLIS + AUTO_COMPLETE_GAP_MILLIS) * 10L)
            runCurrent()
            val k = vm.uiState.value.klondike!!
            assertNull(vm.animatedMove.value)
            assertTrue(k.isWon)
            assertTrue(k.foundations.all { it.size == 13 })
            assertEquals(1, vm.uiState.value.history.size)
            // The cascade is a single undo step, like the instant completion.
            vm.undo()
            val restored = vm.uiState.value.klondike!!
            assertFalse(restored.isWon)
            assertEquals(state.foundations, restored.foundations)
            assertEquals(state.waste, restored.waste)
            assertTrue(restored.usedUndo)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun animatedTapMoveWithoutGeometryAppliesInstantly() {
        val vm = vm()
        val ace = c(Suit.HEARTS, Rank.ACE)
        setK(vm, KlondikeState(waste = listOf(ace)))
        // No drop targets registered (e.g. a preview): no flight, instant move.
        vm.autoMoveAnimated("waste", Offset.Zero, IntSize.Zero)
        assertNull(vm.animatedMove.value)
        assertEquals(listOf(ace), vm.uiState.value.klondike!!.foundations[0])
    }
}
