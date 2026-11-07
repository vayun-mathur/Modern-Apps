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
import com.vayunmathur.games.solitaire.data.PyramidState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.SpiderState
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

/** Drag lifecycle, drop-target ranking, drop dispatch and ViewModel shell. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DragAndViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = SolitaireViewModel(app)
    private fun c(suit: Suit, rank: Rank) = Card(suit, rank)

    private fun setK(vm: SolitaireViewModel, state: KlondikeState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.KLONDIKE, klondike = state)
    }

    @Test fun dragLifecycleStartsUpdatesEndsAndCancels() {
        val vm = vm()
        val queen = c(Suit.HEARTS, Rank.QUEEN)
        val king = c(Suit.SPADES, Rank.KING)
        setK(
            vm,
            KlondikeState(
                waste = listOf(queen),
                tableauPiles = listOf(TableauPile(faceUp = listOf(king))) + List(6) { TableauPile() },
            ),
        )
        assertFalse(vm.startDrag("tableau_3_0", Offset.Zero)) // empty source
        assertNull(vm.dragInfo.value)
        assertTrue(vm.startDrag("waste", Offset(10f, 20f), IntSize(60, 84)))
        var info = vm.dragInfo.value!!
        assertEquals(listOf(queen), info.cards)
        assertEquals("waste", info.sourceId)
        vm.updateDrag(Offset(50f, 60f))
        info = vm.dragInfo.value!!
        assertEquals(Offset(50f, 60f), info.offset)
        // Drop over the king's column (large overlap) moves the queen.
        vm.dropTargets["tableau_0"] = Rect(0f, 0f, 100f, 100f)
        vm.endDrag(Offset(50f, 50f), IntSize(60, 84))
        assertNull(vm.dragInfo.value)
        val k = vm.uiState.value.klondike!!
        assertTrue(k.waste.isEmpty())
        assertEquals(queen, k.tableauPiles[0].faceUp.last())
        // Cancel clears without moving.
        assertTrue(vm.startDrag("tableau_0_1", Offset.Zero, IntSize(60, 84)))
        vm.cancelDrag()
        assertNull(vm.dragInfo.value)
        assertEquals(1, vm.uiState.value.klondike!!.moveCount)
        // EndDrag with nothing in flight is a no-op.
        vm.endDrag(Offset.Zero, IntSize.Zero)
        vm.updateDrag(Offset(1f, 1f))
        assertNull(vm.dragInfo.value)
    }

    @Test fun endDragFallsBackToDragStartCardSize() {
        val vm = vm()
        val queen = c(Suit.HEARTS, Rank.QUEEN)
        setK(
            vm,
            KlondikeState(
                waste = listOf(queen),
                tableauPiles = listOf(TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.KING)))) + List(6) { TableauPile() },
            ),
        )
        vm.dropTargets["tableau_0"] = Rect(0f, 0f, 100f, 100f)
        assertTrue(vm.startDrag("waste", Offset.Zero, IntSize(60, 84)))
        // Zero size at drop: the size recorded at drag start is used.
        vm.endDrag(Offset(50f, 50f), IntSize.Zero)
        assertTrue(vm.uiState.value.klondike!!.waste.isEmpty())
    }

    @Test fun dropTargetRankingByOverlapThenPointFallback() {
        val vm = vm()
        vm.dropTargets["tableau_0"] = Rect(0f, 0f, 100f, 100f)
        vm.dropTargets["tableau_1"] = Rect(50f, 0f, 150f, 100f)
        vm.dropTargets["foundation_0"] = Rect(500f, 500f, 560f, 584f)
        // Card centred at (75,50) size 100x100 overlaps both columns equally-ish:
        // tableau_0 overlap 75 wide, tableau_1 overlap 75 wide — order is by area.
        val ranked = vm.candidateDropTargets(Offset(75f, 50f), IntSize(100, 100))
        assertEquals(setOf("tableau_0", "tableau_1"), ranked.toSet())
        assertEquals(2, ranked.size)
        // Zero-size card falls back to point containment.
        assertEquals(listOf("foundation_0"), vm.candidateDropTargets(Offset(530f, 540f), IntSize.Zero))
        assertEquals(emptyList(), vm.candidateDropTargets(Offset(999f, 999f), IntSize.Zero))
        // Overlap area helper directly.
        assertEquals(2500f, overlapArea(Rect(0f, 0f, 100f, 100f), Rect(50f, 50f, 150f, 150f)))
        assertEquals(0f, overlapArea(Rect(0f, 0f, 10f, 10f), Rect(20f, 20f, 30f, 30f)))
        assertEquals(0f, overlapArea(Rect(0f, 0f, 10f, 10f), Rect(10f, 0f, 20f, 10f)))
    }

    @Test fun tryMoveByDragFallsThroughToAcceptingTarget() {
        val vm = vm()
        val queen = c(Suit.HEARTS, Rank.QUEEN)
        // tableau_0 (red king) cannot take the red queen; tableau_1 (black king) can.
        // Give the illegal target the larger overlap so ranking tries it first.
        setK(
            vm,
            KlondikeState(
                waste = listOf(queen),
                tableauPiles = listOf(
                    TableauPile(faceUp = listOf(c(Suit.DIAMONDS, Rank.KING))),
                    TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.KING))),
                ) + List(5) { TableauPile() },
            ),
        )
        vm.dropTargets["tableau_0"] = Rect(0f, 0f, 200f, 200f)
        vm.dropTargets["tableau_1"] = Rect(0f, 0f, 100f, 100f)
        vm.tryMoveByDrag("waste", Offset(50f, 50f), IntSize(50, 50))
        val k = vm.uiState.value.klondike!!
        assertTrue(k.waste.isEmpty())
        assertEquals(queen, k.tableauPiles[1].faceUp.last())
    }

    @Test fun tryMoveByDragFoundationAndSpiderAndPyramidModes() {
        val vm = vm()
        val ace = c(Suit.HEARTS, Rank.ACE)
        setK(vm, KlondikeState(waste = listOf(ace)))
        vm.dropTargets["foundation_0"] = Rect(0f, 0f, 100f, 100f)
        vm.tryMoveByDrag("waste", Offset(50f, 50f), IntSize(50, 50))
        assertEquals(listOf(ace), vm.uiState.value.klondike!!.foundations[0])
        // Spider: tableau to tableau by drag.
        vm.uiStateInternal.value = SolitaireUiState(
            gameMode = GameMode.SPIDER,
            spider = SpiderState(
                tableauPiles = listOf(
                    TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.EIGHT))),
                    TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.NINE))),
                ) + List(8) { TableauPile() },
            ),
        )
        vm.dropTargets.clear()
        vm.dropTargets["tableau_1"] = Rect(0f, 0f, 100f, 100f)
        vm.tryMoveByDrag("tableau_0_0", Offset(50f, 50f), IntSize(50, 50))
        assertEquals(2, vm.uiState.value.spider!!.tableauPiles[1].faceUp.size)
        // Pyramid ignores drags entirely.
        val pyr = PyramidState(pyramid = listOf(listOf(ace)))
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.PYRAMID, pyramid = pyr)
        vm.tryMoveByDrag("pyr_0_0", Offset(50f, 50f), IntSize(50, 50))
        assertEquals(pyr, vm.uiState.value.pyramid)
        // No mode: no-op.
        vm.uiStateInternal.value = SolitaireUiState()
        vm.tryMoveByDrag("waste", Offset.Zero, IntSize.Zero)
    }

    @Test fun freeCellDropDispatchByIds() {
        val vm = vm()
        val seven = c(Suit.SPADES, Rank.SEVEN)
        val six = c(Suit.HEARTS, Rank.SIX)
        vm.uiStateInternal.value = SolitaireUiState(
            gameMode = GameMode.FREECELL,
            freeCell = FreeCellState(
                tableauPiles = listOf(listOf(seven), listOf(c(Suit.CLUBS, Rank.EIGHT))) + List(6) { emptyList() },
            ),
        )
        vm.dropTargets["freecell_1"] = Rect(0f, 0f, 100f, 100f)
        vm.tryMoveByDrag("tableau_0_0", Offset(50f, 50f), IntSize(50, 50))
        assertEquals(seven, vm.uiState.value.freeCell!!.freeCells[1])
        // Cell -> tableau via drag.
        vm.dropTargets.clear()
        vm.dropTargets["tableau_1"] = Rect(0f, 0f, 100f, 100f)
        // Put a red seven into the cell first via state, then drag it onto
        // the black eight.
        vm.uiStateInternal.value = vm.uiStateInternal.value.copy(
            freeCell = vm.uiStateInternal.value.freeCell!!.copy(
                freeCells = listOf(c(Suit.HEARTS, Rank.SEVEN), null, null, null),
            ),
        )
        vm.tryMoveByDrag("freecell_0", Offset(50f, 50f), IntSize(50, 50))
        var f = vm.uiState.value.freeCell!!
        assertEquals(c(Suit.HEARTS, Rank.SEVEN), f.tableauPiles[1].last())
        // Tableau ace -> foundation via drag.
        vm.uiStateInternal.value = vm.uiStateInternal.value.copy(
            freeCell = f.copy(tableauPiles = listOf(listOf(c(Suit.HEARTS, Rank.ACE))) + List(7) { emptyList() }),
        )
        vm.dropTargets.clear()
        vm.dropTargets["foundation_0"] = Rect(0f, 0f, 100f, 100f)
        vm.tryMoveByDrag("tableau_0_0", Offset(50f, 50f), IntSize(50, 50))
        f = vm.uiState.value.freeCell!!
        assertEquals(1, f.foundations[0].size)
    }

    @Test fun klondikeDropFromTableauToFoundationAndTableau() {
        val vm = vm()
        val ace = c(Suit.DIAMONDS, Rank.ACE) // foundation index 1
        setK(
            vm,
            KlondikeState(
                tableauPiles = listOf(
                    TableauPile(faceUp = listOf(ace)),
                    TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.KING))),
                ) + List(5) { TableauPile() },
            ),
        )
        vm.dropTargets["foundation_1"] = Rect(0f, 0f, 100f, 100f)
        vm.tryMoveByDrag("tableau_0_0", Offset(50f, 50f), IntSize(50, 50))
        assertEquals(listOf(ace), vm.uiState.value.klondike!!.foundations[1])
        // A multi-card drag onto a foundation is ignored (single-card only),
        // but the same drag onto a tableau works at the run level below.
        val runTop = c(Suit.HEARTS, Rank.QUEEN)
        setK(
            vm,
            KlondikeState(
                tableauPiles = listOf(
                    TableauPile(faceUp = listOf(c(Suit.CLUBS, Rank.KING), runTop)),
                    TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.KING))),
                ) + List(5) { TableauPile() },
            ),
        )
        vm.dropTargets.clear()
        vm.dropTargets["foundation_0"] = Rect(0f, 0f, 100f, 100f)
        vm.tryMoveByDrag("tableau_0_0", Offset(50f, 50f), IntSize(50, 50))
        assertTrue(vm.uiState.value.klondike!!.foundations[0].isEmpty())
        assertEquals(2, vm.uiState.value.klondike!!.tableauPiles[0].faceUp.size)
    }

    @Test fun draggedCardsReadsLiveStatePerMode() {
        val vm = vm()
        setK(
            vm,
            KlondikeState(
                waste = listOf(c(Suit.HEARTS, Rank.QUEEN)),
                tableauPiles = listOf(TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.SEVEN), c(Suit.HEARTS, Rank.SIX)))) + List(6) { TableauPile() },
            ),
        )
        assertEquals(listOf(c(Suit.HEARTS, Rank.QUEEN)), vm.draggedCards("waste"))
        assertEquals(2, vm.draggedCards("tableau_0_0").size)
        assertEquals(listOf(c(Suit.HEARTS, Rank.SIX)), vm.draggedCards("tableau_0_1"))
        assertEquals(emptyList(), vm.draggedCards("tableau_0_9"))
        assertEquals(emptyList(), vm.draggedCards("tableau_z_0"))
        assertEquals(emptyList(), vm.draggedCards("freecell_x"))
        assertEquals(emptyList(), vm.draggedCards("pyr_0_0"))
        assertTrue(vm.isSingleCardDrag("waste"))
        assertFalse(vm.isSingleCardDrag("tableau_0_0"))
        // FreeCell tableau drags come from the flat pile lists.
        vm.uiStateInternal.value = SolitaireUiState(
            gameMode = GameMode.FREECELL,
            freeCell = FreeCellState(
                tableauPiles = listOf(listOf(c(Suit.SPADES, Rank.SEVEN), c(Suit.HEARTS, Rank.SIX))) + List(7) { emptyList() },
                freeCells = listOf(c(Suit.CLUBS, Rank.ACE), null, null, null),
            ),
        )
        assertEquals(listOf(c(Suit.CLUBS, Rank.ACE)), vm.draggedCards("freecell_0"))
        assertEquals(2, vm.draggedCards("tableau_0_0").size)
        // Spider tableau drags too.
        vm.uiStateInternal.value = SolitaireUiState(
            gameMode = GameMode.SPIDER,
            spider = SpiderState(tableauPiles = listOf(TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.NINE)))) + List(9) { TableauPile() }),
        )
        assertEquals(1, vm.draggedCards("tableau_0_0").size)
    }

    @Test fun viewModelShellTimerRestartGiveUpAndUndoPerMode() {
        val vm = vm()
        // Timer with no game is a no-op; each mode ticks.
        vm.incrementTimer()
        assertEquals(SolitaireUiState(), vm.uiState.value)
        vm.selectMode(GameMode.FREECELL)
        assertEquals(GameMode.FREECELL, vm.uiState.value.gameMode)
        vm.incrementTimer()
        assertEquals(1, vm.uiState.value.freeCell!!.elapsedSeconds)
        assertTrue(vm.hasActiveGame())
        // Undo with empty history no-ops; restart redeals same mode.
        vm.undo()
        vm.restart()
        assertEquals(GameMode.FREECELL, vm.uiState.value.gameMode)
        assertEquals(0, vm.uiState.value.freeCell!!.elapsedSeconds)
        // Give up clears the board and records the loss.
        vm.giveUp()
        assertEquals(SolitaireUiState(), vm.uiState.value)
        assertFalse(vm.hasActiveGame())
        vm.giveUp() // no mode: returns quietly
        // Undo restores each variant type.
        vm.selectMode(GameMode.SPIDER, com.vayunmathur.games.solitaire.data.GameConfig(spiderSuits = 2))
        vm.incrementTimer()
        vm.undo() // history empty (deal doesn't save)
        val before = vm.uiState.value.spider!!
        vm.dealSpiderStock()
        vm.undo()
        assertEquals(before.stockGroups, vm.uiState.value.spider!!.stockGroups)
        assertTrue(vm.uiState.value.spider!!.usedUndo)
        assertEquals(2, vm.currentConfig().spiderSuits)
    }

    @Test fun undoRestoresPyramidAndFreeCellStates() {
        val vm = vm()
        vm.selectMode(GameMode.PYRAMID, com.vayunmathur.games.solitaire.data.GameConfig(relaxed = true))
        val p0 = vm.uiState.value.pyramid!!
        vm.pyramidDealStock()
        assertEquals(1, vm.uiState.value.pyramid!!.waste.size)
        vm.undo()
        assertEquals(p0.stock, vm.uiState.value.pyramid!!.stock)
        assertTrue(vm.uiState.value.pyramid!!.usedUndo)
        vm.selectMode(GameMode.FREECELL)
        val f0 = vm.uiState.value.freeCell!!
        // Move any tableau top card into a free cell, then undo.
        val col = f0.tableauPiles.indexOfFirst { it.isNotEmpty() }
        vm.freeCellMoveToFreeCell(col, 0)
        assertEquals(1, vm.uiState.value.history.size)
        vm.undo()
        assertEquals(f0.tableauPiles, vm.uiState.value.freeCell!!.tableauPiles)
        assertTrue(vm.uiState.value.freeCell!!.usedUndo)
    }

    @Test fun actionsInterfaceDefaultsAndNoop() {
        val noop = SolitaireActions.Noop
        assertNull(noop.dragInfo.value)
        assertFalse(noop.startDrag("waste", Offset.Zero))
        // Defaults swallow every call.
        noop.updateDrag(Offset.Zero); noop.endDrag(Offset.Zero); noop.cancelDrag()
        noop.drawFromStock(); noop.klondikeAutoComplete(); noop.dealSpiderStock()
        noop.pyramidTapCard("x"); noop.pyramidDealStock(); noop.autoMove("x")
        noop.undo(); noop.restart(); noop.giveUp()
        noop.dropTargets["probe"] = Rect(0f, 0f, 1f, 1f)
        assertTrue(noop.dropTargets.containsKey("probe"))
        noop.dropTargets.remove("probe")
    }

    @Test fun dragInfoDataClass() {
        val info = DragInfo(listOf(c(Suit.HEARTS, Rank.ACE)), "waste")
        assertEquals(Offset.Zero, info.offset)
        assertEquals(IntSize.Zero, info.cardSize)
        assertEquals(info, info.copy(offset = Offset(1f, 2f)).copy(offset = Offset.Zero))
    }
}
