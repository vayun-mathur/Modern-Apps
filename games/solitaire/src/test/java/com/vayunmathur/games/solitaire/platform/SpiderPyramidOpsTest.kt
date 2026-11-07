package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
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

/** Spider moves/deal/completion and Pyramid tap/pair/deal rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpiderPyramidOpsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = SolitaireViewModel(app)
    private fun c(suit: Suit, rank: Rank) = Card(suit, rank)

    private fun setS(vm: SolitaireViewModel, state: SpiderState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.SPIDER, spider = state)
    }

    private fun tenPiles(vararg piles: TableauPile): List<TableauPile> =
        piles.toList() + List(10 - piles.size) { TableauPile() }

    // ---- Spider ----

    @Test fun spiderMoveSequencesAndPlacement() {
        val vm = vm()
        val run = listOf(c(Suit.SPADES, Rank.NINE), c(Suit.SPADES, Rank.EIGHT))
        val down = c(Suit.CLUBS, Rank.TWO)
        setS(
            vm,
            SpiderState(
                tableauPiles = tenPiles(
                    TableauPile(faceDown = listOf(down), faceUp = run),
                    TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.TEN))),
                    TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.NINE))),
                ),
            ),
        )
        // Mixed-suit landing is fine in Spider (rank only).
        vm.spiderMoveCards(0, 0, 1)
        var s = vm.uiState.value.spider!!
        assertEquals(listOf(down), s.tableauPiles[0].faceUp) // auto-flipped
        assertEquals(3, s.tableauPiles[1].faceUp.size)
        // Same-rank landing rejects; out-of-range rejects; mixed run cannot move as a unit.
        setS(
            vm,
            SpiderState(
                tableauPiles = tenPiles(
                    TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.NINE), c(Suit.HEARTS, Rank.EIGHT))),
                    TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.NINE))),
                ),
            ),
        )
        vm.spiderMoveCards(0, 0, 1)
        assertEquals(0, vm.uiState.value.spider!!.moveCount)
        // But its top card alone can move.
        vm.spiderMoveCards(0, 1, 1)
        assertEquals(1, vm.uiState.value.spider!!.moveCount)
        vm.spiderMoveCards(0, -1, 1)
        vm.spiderMoveCards(0, 42, 1)
        assertEquals(1, vm.uiState.value.spider!!.moveCount)
        // Empty column accepts anything.
        vm.spiderMoveCards(1, 0, 5)
        assertEquals(2, vm.uiState.value.spider!!.moveCount)
    }

    @Test fun spiderDealStockGuardsAndDeals() {
        val vm = vm()
        val group = (0 until 10).map { c(Suit.SPADES, Rank.entries[it % 13]) }
        // Empty column blocks dealing.
        setS(vm, SpiderState(stockGroups = listOf(group)))
        vm.dealSpiderStock()
        assertEquals(1, vm.uiState.value.spider!!.stockGroups.size)
        // Every column occupied: one card each, group consumed.
        val piles = tenPiles(*Array(10) { TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.ACE))) })
        setS(vm, SpiderState(tableauPiles = piles, stockGroups = listOf(group, group)))
        vm.dealSpiderStock()
        var s = vm.uiState.value.spider!!
        assertEquals(1, s.stockGroups.size)
        assertTrue(s.tableauPiles.all { it.faceUp.size == 2 })
        assertEquals(group[3], s.tableauPiles[3].faceUp.last())
        // No stock left: no-op. Won: no-op.
        setS(vm, SpiderState(tableauPiles = piles, stockGroups = emptyList()))
        vm.dealSpiderStock()
        assertEquals(0, vm.uiState.value.spider!!.moveCount)
    }

    @Test fun spiderRunClearsOnMoveCompletion() {
        val vm = vm()
        val run = Rank.entries.reversed().map { c(Suit.SPADES, it) } // K..A, 13 cards
        val down = c(Suit.CLUBS, Rank.FOUR)
        // Move the intact K..A run onto an empty column: it lands whole and clears.
        setS(
            vm,
            SpiderState(
                tableauPiles = tenPiles(
                    TableauPile(faceDown = listOf(down), faceUp = run),
                    TableauPile(),
                ),
                completedSuits = 0,
            ),
        )
        vm.spiderMoveCards(0, 0, 1)
        val s = vm.uiState.value.spider!!
        assertEquals(1, s.completedSuits)
        assertTrue(s.tableauPiles[1].faceUp.isEmpty())
        assertEquals(listOf(down), s.tableauPiles[0].faceUp)
        assertFalse(s.isWon)
    }

    @Test fun spiderEighthSuitWins() {
        val vm = vm()
        val run = Rank.entries.reversed().map { c(Suit.HEARTS, it) }
        setS(
            vm,
            SpiderState(
                tableauPiles = tenPiles(TableauPile(faceUp = run), TableauPile()),
                completedSuits = 7,
                elapsedSeconds = 99,
            ),
        )
        vm.spiderMoveCards(0, 0, 1)
        val s = vm.uiState.value.spider!!
        assertEquals(8, s.completedSuits)
        assertTrue(s.isWon)
    }

    @Test fun spiderNewGameSuitVariants() {
        val vm = vm()
        vm.newSpiderGame(GameConfig(spiderSuits = 2))
        assertEquals("2SUIT", vm.uiState.value.spider!!.variant)
        assertEquals(2, vm.currentConfig().spiderSuits)
        vm.newSpiderGame(GameConfig(spiderSuits = 1))
        assertEquals("1SUIT", vm.uiState.value.spider!!.variant)
    }

    // ---- Pyramid ----

    private fun setP(vm: SolitaireViewModel, state: PyramidState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.PYRAMID, pyramid = state)
    }

    private fun pyramidState(
        rows: List<List<Card?>>,
        stock: List<Card> = emptyList(),
        waste: List<Card> = emptyList(),
        relaxed: Boolean = false,
        selectedId: String? = null,
    ) = PyramidState(pyramid = rows, stock = stock, waste = waste, relaxed = relaxed, selectedId = selectedId)

    @Test fun pyramidExposureAndSoleCover() {
        val vm = vm()
        val a = c(Suit.SPADES, Rank.ACE); val b = c(Suit.HEARTS, Rank.TWO); val cc = c(Suit.CLUBS, Rank.THREE)
        val state = pyramidState(listOf(listOf(a), listOf(b, cc)))
        assertFalse(vm.isPyramidCardExposed(state, 0, 0))
        assertTrue(vm.isPyramidCardExposed(state, 1, 0))
        assertTrue(vm.isPyramidPlayable(state, "pyr_1_1"))
        assertFalse(vm.isPyramidPlayable(state, "pyr_0_0"))
        assertFalse(vm.isPyramidPlayable(state, "waste"))
        assertFalse(vm.isPyramidPlayable(state, "bogus"))
        // b is sole cover of a once cc is gone.
        val cleared = pyramidState(listOf(listOf(a), listOf(b, null)))
        assertTrue(vm.isPyramidSoleCover(cleared, "pyr_1_0", "pyr_0_0"))
        assertFalse(vm.isPyramidSoleCover(state, "pyr_1_0", "pyr_0_0"))
        assertFalse(vm.isPyramidSoleCover(state, "pyr_0_0", "pyr_1_0"))
        assertFalse(vm.isPyramidSoleCover(state, "waste", "pyr_0_0"))
        // Removed card is not exposed.
        assertFalse(vm.isPyramidCardExposed(pyramidState(listOf(listOf(null))), 0, 0))
    }

    @Test fun pyramidTapSelectsPairsAndKings() {
        val vm = vm()
        val queen = c(Suit.HEARTS, Rank.QUEEN); val ace = c(Suit.CLUBS, Rank.ACE); val king = c(Suit.SPADES, Rank.KING)
        setP(vm, pyramidState(listOf(listOf(c(Suit.DIAMONDS, Rank.FIVE)), listOf(queen, ace))))
        // Tap queen (exposed bottom) selects it.
        vm.pyramidTapCard("pyr_1_0")
        assertEquals("pyr_1_0", vm.uiState.value.pyramid!!.selectedId)
        // Tapping it again deselects.
        vm.pyramidTapCard("pyr_1_0")
        assertNull(vm.uiState.value.pyramid!!.selectedId)
        // Select queen then tap ace: pair removes both.
        vm.pyramidTapCard("pyr_1_0")
        vm.pyramidTapCard("pyr_1_1")
        var p = vm.uiState.value.pyramid!!
        assertEquals(listOf(null, null), p.pyramid[1])
        assertEquals(1, p.moveCount)
        assertNull(p.selectedId)
        // King on the bottom row removes alone. The 5 above is covered, so use
        // a fresh single-row pyramid.
        setP(vm, pyramidState(listOf(listOf(king))))
        vm.pyramidTapCard("pyr_0_0")
        p = vm.uiState.value.pyramid!!
        assertEquals(listOf(null), p.pyramid[0])
        assertTrue(p.isWon)
    }

    @Test fun pyramidTapCoveredCardAndWastePair() {
        val vm = vm()
        val top = c(Suit.DIAMONDS, Rank.FIVE)
        val queen = c(Suit.HEARTS, Rank.QUEEN); val ace = c(Suit.CLUBS, Rank.ACE)
        // Covered card tap does nothing.
        setP(vm, pyramidState(listOf(listOf(top), listOf(queen, ace))))
        vm.pyramidTapCard("pyr_0_0")
        assertNull(vm.uiState.value.pyramid!!.selectedId)
        assertEquals(0, vm.uiState.value.pyramid!!.moveCount)
        // Waste queen pairs with pyramid ace.
        setP(vm, pyramidState(listOf(listOf(top), listOf(queen, ace)), waste = listOf(c(Suit.SPADES, Rank.QUEEN))))
        vm.pyramidTapCard("waste")
        assertEquals("waste", vm.uiState.value.pyramid!!.selectedId)
        vm.pyramidTapCard("pyr_1_1")
        val p = vm.uiState.value.pyramid!!
        assertTrue(p.waste.isEmpty())
        assertEquals(null, p.pyramid[1][1])
        assertEquals(queen, p.pyramid[1][0])
        // Unknown id and empty waste tap do nothing.
        vm.pyramidTapCard("bogus_id")
        vm.pyramidTapCard("waste")
        assertEquals(1, vm.uiState.value.pyramid!!.moveCount)
    }

    @Test fun pyramidSoleCoverPairRemoval() {
        val vm = vm()
        // Apex 5 covered solely by queen (ace slot cleared); Q + 8? Use ranks
        // summing to 13: apex is 8, cover is 5 — 8+5=13, cover exposed.
        val eight = c(Suit.DIAMONDS, Rank.EIGHT); val five = c(Suit.HEARTS, Rank.FIVE)
        setP(vm, pyramidState(listOf(listOf(eight), listOf(five, null))))
        // Five is exposed and is sole cover of the eight: pair is removable
        // even though the eight itself is not exposed.
        assertTrue(vm.canPyramidRemovePair(vm.uiState.value.pyramid!!, "pyr_1_0", "pyr_0_0"))
        vm.pyramidTapCard("pyr_1_0")
        vm.pyramidTapCard("pyr_0_0")
        val p = vm.uiState.value.pyramid!!
        assertEquals(listOf(null), p.pyramid[0])
        assertEquals(listOf(null, null), p.pyramid[1])
    }

    @Test fun pyramidDealAndRecycleModes() {
        val vm = vm()
        val a = c(Suit.SPADES, Rank.ACE); val b = c(Suit.HEARTS, Rank.TWO)
        setP(vm, pyramidState(listOf(listOf(a)), stock = listOf(a, b)))
        vm.pyramidDealStock()
        var p = vm.uiState.value.pyramid!!
        assertEquals(listOf(b), p.waste)
        assertEquals(1, p.stock.size)
        vm.pyramidDealStock()
        p = vm.uiState.value.pyramid!!
        assertEquals(2, p.waste.size)
        // Original: no recycle.
        vm.pyramidDealStock()
        assertEquals(2, vm.uiState.value.pyramid!!.waste.size)
        // Relaxed: recycle reverses waste back to stock.
        setP(vm, pyramidState(listOf(listOf(a)), stock = emptyList(), waste = listOf(a, b), relaxed = true))
        vm.pyramidDealStock()
        p = vm.uiState.value.pyramid!!
        assertEquals(listOf(b, a), p.stock)
        assertTrue(p.waste.isEmpty())
        // Relaxed with empty waste and stock: no-op.
        setP(vm, pyramidState(listOf(listOf(a)), relaxed = true))
        vm.pyramidDealStock()
        assertEquals(0, vm.uiState.value.pyramid!!.moveCount)
    }

    @Test fun pyramidCardAtAndRemoveAndParse() {
        val vm = vm()
        val a = c(Suit.SPADES, Rank.ACE); val b = c(Suit.HEARTS, Rank.TWO)
        val state = pyramidState(listOf(listOf(a), listOf(b, null)), waste = listOf(b))
        assertEquals(a, vm.pyramidCardAt(state, "pyr_0_0"))
        assertEquals(b, vm.pyramidCardAt(state, "waste"))
        assertNull(vm.pyramidCardAt(state, "pyr_5_5"))
        assertNull(vm.pyramidCardAt(state, "other"))
        assertNull(vm.pyramidCardAt(state, "pyr_1_1"))
        val removed = vm.pyramidRemove(state, "pyr_0_0")
        assertNull(removed.pyramid[0][0])
        assertEquals(state, vm.pyramidRemove(state, "other"))
        assertTrue(vm.pyramidRemove(state, "waste").waste.isEmpty())
        assertEquals(3 to 2, vm.parsePyramidId("pyr_3_2"))
        assertFalse(vm.canPyramidRemovePair(state, "pyr_0_0", "pyr_0_0"))
        assertFalse(vm.canPyramidRemovePair(state, "pyr_0_0", "missing"))
    }

    @Test fun pyramidNewGameModes() {
        val vm = vm()
        vm.newPyramidGame(GameConfig(relaxed = false))
        assertEquals("ORIGINAL", vm.uiState.value.pyramid!!.variant)
        assertFalse(vm.currentConfig().relaxed)
        vm.newPyramidGame(GameConfig(relaxed = true))
        assertTrue(vm.currentConfig().relaxed)
    }
}
