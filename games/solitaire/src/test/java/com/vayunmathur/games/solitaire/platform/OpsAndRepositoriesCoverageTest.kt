package com.vayunmathur.games.solitaire.platform

import android.app.Application
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.CardColorScheme
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.FreeCellState
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.PyramidState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireSettingsRepository
import com.vayunmathur.games.solitaire.data.SolitaireStatsRepository
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

/** Ops guards/branches not hit by happy-path tests, plus stats/settings repositories. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpsAndRepositoriesCoverageTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = SolitaireViewModel(app)
    private fun c(suit: Suit, rank: Rank) = Card(suit, rank)

    private fun setK(vm: SolitaireViewModel, s: KlondikeState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.KLONDIKE, klondike = s)
    }
    private fun setF(vm: SolitaireViewModel, s: FreeCellState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.FREECELL, freeCell = s)
    }
    private fun setS(vm: SolitaireViewModel, s: SpiderState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.SPIDER, spider = s)
    }
    private fun setP(vm: SolitaireViewModel, s: PyramidState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.PYRAMID, pyramid = s)
    }
    private fun kPiles(vararg p: TableauPile) = p.toList() + List(7 - p.size) { TableauPile() }
    private fun sPiles(vararg p: TableauPile) = p.toList() + List(10 - p.size) { TableauPile() }

    // ---- Klondike guards ----

    @Test
    fun klondikeGuardsRejectWonEmptyAndIllegalMoves() {
        val vm = vm()
        val won = KlondikeState(isWon = true, waste = listOf(c(Suit.HEARTS, Rank.ACE)), tableauPiles = kPiles())
        setK(vm, won)
        vm.klondikeMoveWasteToFoundation(0)
        vm.drawFromStock()
        assertEquals(0, vm.uiState.value.klondike!!.moveCount)

        // Empty waste / empty stock+waste: no-ops, no history.
        setK(vm, KlondikeState(tableauPiles = kPiles()))
        vm.klondikeMoveWasteToTableau(0)
        vm.klondikeMoveWasteToFoundation(0)
        vm.drawFromStock()
        assertTrue(vm.uiState.value.history.isEmpty())

        // Wrong foundation suit for an Ace, and non-Ace on empty foundation.
        setK(vm, KlondikeState(waste = listOf(c(Suit.SPADES, Rank.ACE)), tableauPiles = kPiles()))
        vm.klondikeMoveWasteToFoundation(0) // foundation 0 is hearts
        assertEquals(1, vm.uiState.value.klondike!!.waste.size)
        vm.klondikeMoveWasteToFoundation(2) // spades foundation
        assertEquals(0, vm.uiState.value.klondike!!.waste.size)

        // Tableau-to-tableau with out-of-range card index.
        setK(vm, KlondikeState(tableauPiles = kPiles(TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.FIVE))))))
        vm.klondikeMoveTableauToTableau(0, 5, 1)
        vm.klondikeMoveTableauToTableau(0, -1, 1)
        assertEquals(0, vm.uiState.value.klondike!!.moveCount)

        // Tableau to foundation from empty pile.
        vm.klondikeMoveTableauToFoundation(1, 0)
        assertEquals(0, vm.uiState.value.klondike!!.moveCount)
    }

    @Test
    fun klondikeDrawThreeDrawsUpToThreeAndRecycleExhaustion() {
        val vm = vm()
        val stock = listOf(c(Suit.HEARTS, Rank.ACE), c(Suit.HEARTS, Rank.TWO))
        setK(vm, KlondikeState(stock = stock, drawMode = DrawMode.DRAW_THREE, redealsRemaining = 0, tableauPiles = kPiles()))
        vm.drawFromStock()
        var k = vm.uiState.value.klondike!!
        assertEquals(2, k.waste.size) // only two available
        assertEquals(listOf(c(Suit.HEARTS, Rank.TWO), c(Suit.HEARTS, Rank.ACE)), k.waste)
        vm.drawFromStock() // recycle attempt with 0 redeals: no-op
        k = vm.uiState.value.klondike!!
        assertEquals(2, k.waste.size)
        assertEquals(1, k.moveCount)
    }

    @Test
    fun foundationToTableauPureRuleEdges() {
        val king = c(Suit.SPADES, Rank.KING)
        val queenRed = c(Suit.HEARTS, Rank.QUEEN)
        val state = KlondikeState(
            foundations = listOf(listOf(queenRed), emptyList(), emptyList(), emptyList()),
            tableauPiles = kPiles(TableauPile(faceUp = listOf(king)), TableauPile()),
        )
        // Queen red onto black King: legal.
        assertTrue(canMoveKlondikeFoundationToTableau(state, 0, 0))
        // Queen onto empty column: only Kings may land there.
        assertFalse(canMoveKlondikeFoundationToTableau(state, 0, 1))
        // Empty foundation and out-of-range indices.
        assertFalse(canMoveKlondikeFoundationToTableau(state, 1, 0))
        assertFalse(canMoveKlondikeFoundationToTableau(state, 9, 0))
        assertFalse(canMoveKlondikeFoundationToTableau(state, 0, 9))
        // Overlap geometry.
        assertEquals(100f, overlapArea(Rect(0f, 0f, 10f, 10f), Rect(0f, 0f, 10f, 10f)))
        assertEquals(25f, overlapArea(Rect(0f, 0f, 10f, 10f), Rect(5f, 5f, 15f, 15f)))
        assertEquals(0f, overlapArea(Rect(0f, 0f, 10f, 10f), Rect(20f, 0f, 30f, 10f)))
        assertEquals(0f, overlapArea(Rect(0f, 0f, 10f, 10f), Rect(10f, 0f, 20f, 10f)))
    }

    // ---- FreeCell branches ----

    @Test
    fun freeCellCellMovesAndSupermoveLimit() {
        val vm = vm()
        val king = c(Suit.SPADES, Rank.KING)
        val queen = c(Suit.HEARTS, Rank.QUEEN)
        val jack = c(Suit.CLUBS, Rank.JACK)
        // Move tableau top to an empty cell, reject occupied cell, move back legally/illegally.
        setF(vm, FreeCellState(
            tableauPiles = listOf(listOf(king)) + List(7) { emptyList() },
        ))
        vm.freeCellMoveToFreeCell(0, 0)
        assertEquals(king, vm.uiState.value.freeCell!!.freeCells[0])
        // Occupied cell rejects.
        setF(vm, vm.uiState.value.freeCell!!.copy(tableauPiles = listOf(listOf(queen)) + List(7) { emptyList() }))
        vm.freeCellMoveToFreeCell(0, 0)
        assertEquals(queen, vm.uiState.value.freeCell!!.tableauPiles[0].last())
        // Cell -> empty tableau is allowed for any card in FreeCell.
        vm.freeCellMoveFromFreeCell(0, 1)
        assertNull(vm.uiState.value.freeCell!!.freeCells[0])
        assertEquals(king, vm.uiState.value.freeCell!!.tableauPiles[1].last())
        // Empty cell to tableau is a no-op.
        vm.freeCellMoveFromFreeCell(0, 0)
        // Cell to foundation: Ace only on its suit foundation.
        setF(vm, FreeCellState(freeCells = listOf(c(Suit.DIAMONDS, Rank.ACE), null, null, null),
            tableauPiles = List(8) { emptyList() }))
        vm.freeCellMoveFreeCellToFoundation(0, 0)
        assertEquals(c(Suit.DIAMONDS, Rank.ACE), vm.uiState.value.freeCell!!.freeCells[0])
        vm.freeCellMoveFreeCellToFoundation(0, 1)
        assertNull(vm.uiState.value.freeCell!!.freeCells[0])

        // Supermove: 2-card run with no free cells and no empty columns exceeds (1+0)*1=1.
        val run = listOf(queen, jack) // Q on? tableau bottom->top: need sequence descending alt
        val cols = listOf(run, listOf(king)) + List(6) { listOf(c(Suit.SPADES, Rank.TWO)) }
        setF(vm, FreeCellState(tableauPiles = cols, freeCells = listOf(king, king, king, king)))
        val before = vm.uiState.value.freeCell
        vm.freeCellMoveTableauToTableau(0, 0, 1)
        assertEquals(before, vm.uiState.value.freeCell)
        // Same run with all cells free: limit (1+4)=5, but target King accepts Queen run.
        setF(vm, FreeCellState(tableauPiles = cols, freeCells = List(4) { null }))
        vm.freeCellMoveTableauToTableau(0, 0, 1)
        assertEquals(listOf(king, queen, jack), vm.uiState.value.freeCell!!.tableauPiles[1])
        // Non-sequence run rejected even with capacity.
        setF(vm, FreeCellState(
            tableauPiles = listOf(listOf(queen, queen)) + List(7) { emptyList() },
        ))
        vm.freeCellMoveTableauToTableau(0, 0, 1)
        assertEquals(2, vm.uiState.value.freeCell!!.tableauPiles[0].size)
    }

    @Test
    fun freeCellAutoMovePrefersFoundationThenColumnThenCell() {
        val vm = vm()
        // Ace on tableau: auto-move goes to foundation.
        setF(vm, FreeCellState(tableauPiles = listOf(listOf(c(Suit.HEARTS, Rank.ACE))) + List(7) { emptyList() }))
        vm.autoMove("tableau_0_0")
        assertEquals(1, vm.uiState.value.freeCell!!.foundations[0].size)
        // Lone card with no foundation/column move: falls back to empty cell.
        setF(vm, FreeCellState(tableauPiles = listOf(listOf(c(Suit.HEARTS, Rank.SEVEN))) + List(7) { listOf(c(Suit.HEARTS, Rank.TWO)) }))
        vm.autoMove("tableau_0_0")
        assertEquals(c(Suit.HEARTS, Rank.SEVEN), vm.uiState.value.freeCell!!.freeCells[0])
        // Unknown source: no-op.
        val before = vm.uiState.value
        vm.autoMove("bogus")
        assertEquals(before, vm.uiState.value)
        // Spider/Pyramid auto-move is a no-op by design.
        setS(vm, SpiderState(tableauPiles = sPiles(TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.KING))))))
        vm.autoMove("tableau_0_0")
        assertEquals(0, vm.uiState.value.spider!!.moveCount)
    }

    // ---- Spider branches ----

    @Test
    fun spiderDealBlockedByEmptyColumnAndIncompleteRunDoesNotClear() {
        val vm = vm()
        val piles = sPiles(TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.KING))), TableauPile())
        setS(vm, SpiderState(tableauPiles = piles, stockGroups = listOf(List(10) { c(Suit.HEARTS, Rank.TWO) })))
        vm.dealSpiderStock()
        assertEquals(1, vm.uiState.value.spider!!.stockGroups.size) // blocked: a column is empty

        // Almost-run K..2 (missing Ace) placed: does not clear.
        val run = Rank.entries.reversed().drop(1).map { c(Suit.SPADES, it) } // K..2
        val cols = sPiles(
            TableauPile(faceUp = run.dropLast(1)),
            TableauPile(faceUp = listOf(run.last())),
        )
        setS(vm, SpiderState(tableauPiles = cols))
        // Move 2 onto 3? run's top is 3 in col0? construct directly: move single 2 onto 3
        // col0 faceUp ends with THREE, col1 has TWO of same suit -> legal rank placement.
        vm.spiderMoveCards(1, 0, 0)
        val after = vm.uiState.value.spider!!
        assertEquals(0, after.completedSuits)
        assertFalse(after.isWon)
        // Illegal: mixed-suit sequence cannot move as a run.
        setS(vm, SpiderState(tableauPiles = sPiles(
            TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.QUEEN), c(Suit.HEARTS, Rank.JACK))),
            TableauPile(faceUp = listOf(c(Suit.CLUBS, Rank.KING))),
        )))
        vm.spiderMoveCards(0, 0, 1)
        assertEquals(0, vm.uiState.value.spider!!.moveCount)
        // Any card onto empty column is allowed in Spider.
        setS(vm, SpiderState(tableauPiles = sPiles(
            TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.FIVE))), TableauPile(),
        )))
        vm.spiderMoveCards(0, 0, 1)
        assertEquals(1, vm.uiState.value.spider!!.moveCount)
    }

    // ---- Pyramid branches ----

    @Test
    fun pyramidSelectionToggleWasteKingAndCoveredTap() {
        val vm = vm()
        val queen = c(Suit.HEARTS, Rank.QUEEN)
        val five = c(Suit.SPADES, Rank.FIVE)
        val king = c(Suit.CLUBS, Rank.KING)
        fun state(selected: String? = null, waste: List<Card> = emptyList()) = PyramidState(
            pyramid = listOf(listOf(c(Suit.CLUBS, Rank.ACE)), listOf(queen, five), listOf(king, queen, five)),
            waste = waste, selectedId = selected,
        )
        // Tap bottom Queen selects, tapping again deselects.
        setP(vm, state())
        vm.pyramidTapCard("pyr_2_1")
        assertEquals("pyr_2_1", vm.uiState.value.pyramid!!.selectedId)
        vm.pyramidTapCard("pyr_2_1")
        assertNull(vm.uiState.value.pyramid!!.selectedId)
        // Covered apex tap does nothing.
        vm.pyramidTapCard("pyr_0_0")
        assertNull(vm.uiState.value.pyramid!!.selectedId)
        // Bottom King removes alone and counts a move.
        vm.pyramidTapCard("pyr_2_0")
        assertNull(vm.uiState.value.pyramid!!.pyramid[2][0])
        assertEquals(1, vm.uiState.value.pyramid!!.moveCount)
        // Waste King removes alone.
        setP(vm, state(waste = listOf(king)))
        vm.pyramidTapCard("waste")
        assertTrue(vm.uiState.value.pyramid!!.waste.isEmpty())
        // Unknown id / empty waste: no-ops.
        setP(vm, state())
        vm.pyramidTapCard("bogus")
        vm.pyramidTapCard("waste")
        assertEquals(0, vm.uiState.value.pyramid!!.moveCount)
        // Helpers: cardAt for unknown, exposed checks for removed slot.
        val s = vm.uiState.value.pyramid!!
        assertNull(vm.pyramidCardAt(s, "bogus"))
        assertNull(vm.pyramidCardAt(s, "pyr_9_9"))
        assertFalse(vm.isPyramidPlayable(s, "bogus"))
        assertFalse(vm.isPyramidSoleCover(s, "waste", "pyr_1_0"))
        assertFalse(vm.canPyramidRemovePair(s, "pyr_2_1", "pyr_2_1"))
    }

    @Test
    fun pyramidWinAndTickAndWonGuards() {
        val vm = vm()
        val king = c(Suit.CLUBS, Rank.KING)
        setP(vm, PyramidState(pyramid = listOf(listOf(king)), stock = listOf(c(Suit.HEARTS, Rank.TWO))))
        vm.pyramidTapCard("pyr_0_0")
        assertTrue(vm.uiState.value.pyramid!!.isWon)
        // Won game ignores stock taps.
        vm.pyramidDealStock()
        assertEquals(1, vm.uiState.value.pyramid!!.stock.size)
        assertEquals(9, PyramidState(isWon = true, elapsedSeconds = 9).tick().elapsedSeconds)
        assertEquals(1, SpiderState(elapsedSeconds = 0).tick().elapsedSeconds)
        assertEquals(1, FreeCellState(elapsedSeconds = 0).tick().elapsedSeconds)
    }

    // ---- Drag / VM shell branches ----

    @Test
    fun dragInfoFlightTargetsAndTimerModes() {
        val vm = vm()
        setK(vm, KlondikeState(tableauPiles = kPiles(TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.KING))))))
        // candidateDropTargets: zero-size falls back to containing rect; sized ranks by overlap.
        vm.dropTargets["tableau_1"] = Rect(0f, 0f, 100f, 100f)
        vm.dropTargets["tableau_2"] = Rect(50f, 0f, 150f, 100f)
        assertEquals(listOf("tableau_1"), vm.candidateDropTargets(Offset(10f, 10f), IntSize.Zero))
        val sized = vm.candidateDropTargets(Offset(75f, 50f), IntSize(100, 100))
        assertEquals("tableau_1", sized.first()) // equal overlap -> insertion order stable? both 50x100
        // Pyramid tryMoveByDrag returns null.
        setP(vm, PyramidState(pyramid = listOf(listOf(c(Suit.HEARTS, Rank.KING)))))
        assertNull(vm.tryMoveByDrag("pyr_0_0", Offset.Zero))
        // Timer with no game is a no-op state-wise.
        vm.uiStateInternal.value = SolitaireUiState()
        vm.incrementTimer()
        assertNull(vm.uiState.value.gameMode)
        vm.undo() // empty history no-op
        assertTrue(vm.uiState.value.history.isEmpty())
        assertFalse(vm.hasActiveGame())
        assertEquals(com.vayunmathur.games.solitaire.data.GameConfig(), vm.currentConfig())
    }

    // ---- Repositories ----

    @Test
    fun statsRepositoryAggregatesVariantsStreaksAndTotals() {
        app.getSharedPreferences("solitaire_stats", Context.MODE_PRIVATE).edit().clear().commit()
        val repo = SolitaireStatsRepository(app)
        assertEquals(0, repo.getTotalGamesWon())
        assertEquals(0, repo.getBestWinStreak())
        assertEquals(Int.MAX_VALUE, repo.getModeStats(GameMode.KLONDIKE).bestTimeSeconds)

        repo.recordGamePlayed(GameMode.KLONDIKE, "DRAW_ONE_REGULAR")
        repo.recordGamePlayed(GameMode.KLONDIKE, "DRAW_ONE_REGULAR")
        repo.recordGamePlayed(GameMode.KLONDIKE, "DRAW_THREE_HARD")
        repo.recordGameWon(GameMode.KLONDIKE, "DRAW_ONE_REGULAR", 200)
        repo.recordGameWon(GameMode.KLONDIKE, "DRAW_ONE_REGULAR", 120)
        repo.recordGameWon(GameMode.KLONDIKE, "DRAW_THREE_HARD", 300)

        val variant = repo.getStats(GameMode.KLONDIKE, "DRAW_ONE_REGULAR")
        assertEquals(2, variant.gamesPlayed)
        assertEquals(2, variant.gamesWon)
        assertEquals(2, variant.currentWinStreak)
        assertEquals(120, variant.bestTimeSeconds)

        val mode = repo.getModeStats(GameMode.KLONDIKE)
        assertEquals(3, mode.gamesPlayed)
        assertEquals(3, mode.gamesWon)
        assertEquals(120, mode.bestTimeSeconds)
        assertEquals(3, repo.getTotalGamesWon())
        assertEquals(2, repo.getBestWinStreak())

        repo.recordGameLost(GameMode.KLONDIKE, "DRAW_ONE_REGULAR")
        assertEquals(0, repo.getStats(GameMode.KLONDIKE, "DRAW_ONE_REGULAR").currentWinStreak)
        assertEquals(2, repo.getStats(GameMode.KLONDIKE, "DRAW_ONE_REGULAR").bestWinStreak)
        // Other modes untouched.
        assertEquals(0, repo.getModeStats(GameMode.SPIDER).gamesPlayed)
    }

    @Test
    fun settingsRepositoryDefaultsPersistsAndRecoversFromBadValue() {
        val prefs = app.getSharedPreferences("solitaire_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val repo = SolitaireSettingsRepository(app)
        assertEquals(CardColorScheme.TWO_COLOUR, repo.getCardColorScheme())
        repo.setCardColorScheme(CardColorScheme.FOUR_COLOUR)
        assertEquals(CardColorScheme.FOUR_COLOUR, SolitaireSettingsRepository(app).getCardColorScheme())
        prefs.edit().putString("card_color_scheme", "NOT_A_SCHEME").commit()
        assertEquals(CardColorScheme.TWO_COLOUR, repo.getCardColorScheme())
    }
}
