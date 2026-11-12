package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KLONDIKE_DIFFICULTY_ORDER
import com.vayunmathur.games.solitaire.data.KlondikeDifficulty
import com.vayunmathur.games.solitaire.data.passes
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.PyramidState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.data.alternatesColorWith
import com.vayunmathur.games.solitaire.data.createDeck
import com.vayunmathur.games.solitaire.data.createShuffledDeck
import com.vayunmathur.games.solitaire.data.createSpiderDeck
import com.vayunmathur.games.solitaire.data.isOneHigherThan
import com.vayunmathur.games.solitaire.data.redeals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deck construction, difficulty redeals, deal layouts and placement rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolitaireRulesTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = SolitaireViewModel(app)

    @Test
    fun deckIsCompleteAndOrdered() {
        val deck = createDeck()
        assertEquals(52, deck.size)
        assertEquals(52, deck.toSet().size)
        assertEquals(Card(Suit.HEARTS, Rank.ACE), deck.first())
        assertEquals(Card(Suit.CLUBS, Rank.KING), deck.last())
        assertEquals(deck, createShuffledDeck(42).sortedWith(compareBy({ it.suit }, { it.rank })))
        assertEquals(createShuffledDeck(7), createShuffledDeck(7))
        assertNotEquals(createShuffledDeck(1), createShuffledDeck(2))
    }

    @Test
    fun spiderDeckSuitCounts() {
        assertEquals(104, createSpiderDeck(1, 3).size)
        assertTrue(createSpiderDeck(1, 3).all { it.suit == Suit.SPADES })
        assertEquals(104, createSpiderDeck(2, 3).size)
        assertEquals(setOf(Suit.SPADES, Suit.HEARTS), createSpiderDeck(2, 3).map { it.suit }.toSet())
        assertEquals(104, createSpiderDeck(4, 3).size)
        assertEquals(4, createSpiderDeck(4, 3).map { it.suit }.toSet().size)
        // Unknown suit counts fall back to four suits.
        assertEquals(4, createSpiderDeck(99, 3).map { it.suit }.toSet().size)
    }

    @Test
    fun cardRelations() {
        val redFive = Card(Suit.HEARTS, Rank.FIVE)
        val blackSix = Card(Suit.SPADES, Rank.SIX)
        val redSix = Card(Suit.DIAMONDS, Rank.SIX)
        assertTrue(blackSix.isOneHigherThan(redFive))
        assertFalse(redFive.isOneHigherThan(blackSix))
        assertTrue(redFive.alternatesColorWith(blackSix))
        assertFalse(redFive.alternatesColorWith(redSix))
        assertEquals(Int.MAX_VALUE, KlondikeDifficulty.RELAXED.redeals())
        assertEquals(2, KlondikeDifficulty.REGULAR.redeals())
        assertEquals(0, KlondikeDifficulty.HARD.redeals())
    }

    @Test
    fun klondikePassesPerDifficulty() {
        // Names make up the stats key and must never change; new values are appended.
        assertEquals(
            listOf("RELAXED", "REGULAR", "HARD", "TWO_PASSES"),
            KlondikeDifficulty.entries.map { it.name },
        )
        assertEquals(1, KlondikeDifficulty.TWO_PASSES.redeals())
        assertEquals(null, KlondikeDifficulty.RELAXED.passes())
        assertEquals(3, KlondikeDifficulty.REGULAR.passes())
        assertEquals(2, KlondikeDifficulty.TWO_PASSES.passes())
        assertEquals(1, KlondikeDifficulty.HARD.passes())
        assertEquals(
            listOf(KlondikeDifficulty.RELAXED, KlondikeDifficulty.REGULAR, KlondikeDifficulty.TWO_PASSES, KlondikeDifficulty.HARD),
            KLONDIKE_DIFFICULTY_ORDER,
        )
    }

    @Test
    fun twoPassKlondikeRecyclesExactlyOnce() {
        val vm = vm()
        vm.newKlondikeGame(GameConfig(drawMode = DrawMode.DRAW_ONE, klondikeDifficulty = KlondikeDifficulty.TWO_PASSES))
        assertEquals("DRAW_ONE_TWO_PASSES", vm.uiState.value.klondike!!.variant)
        assertEquals(1, vm.uiState.value.klondike!!.redealsRemaining)
        repeat(24) { vm.drawFromStock() }
        vm.drawFromStock() // recycle: second pass begins
        var k = vm.uiState.value.klondike!!
        assertEquals(24, k.stock.size)
        assertEquals(0, k.redealsRemaining)
        repeat(24) { vm.drawFromStock() }
        vm.drawFromStock() // no recycles left: nothing happens
        k = vm.uiState.value.klondike!!
        assertEquals(0, k.stock.size)
        assertEquals(24, k.waste.size)
    }

    @Test
    fun tickStopsAtWin() {
        assertEquals(1, KlondikeState(elapsedSeconds = 0).tick().elapsedSeconds)
        val won = KlondikeState(isWon = true, elapsedSeconds = 9)
        assertEquals(9, won.tick().elapsedSeconds)
    }

    @Test
    fun newKlondikeGameDealsCorrectly() {
        val vm = vm()
        vm.newKlondikeGame(GameConfig(drawMode = DrawMode.DRAW_THREE, klondikeDifficulty = KlondikeDifficulty.HARD))
        val s = vm.uiState.value
        assertEquals(GameMode.KLONDIKE, s.gameMode)
        val k = s.klondike!!
        assertEquals(7, k.tableauPiles.size)
        k.tableauPiles.forEachIndexed { i, pile ->
            assertEquals(i, pile.faceDown.size)
            assertEquals(1, pile.faceUp.size)
        }
        // 52 - 28 tableau = 24 stock.
        assertEquals(24, k.stock.size)
        assertEquals(0, k.redealsRemaining)
        assertEquals(DrawMode.DRAW_THREE, k.drawMode)
        assertTrue(vm.hasActiveGame())
        assertEquals("DRAW_THREE_HARD", k.variant)
    }

    @Test
    fun drawFromStockHonoursDrawModeAndRecycles() {
        val vm = vm()
        vm.newKlondikeGame(GameConfig(drawMode = DrawMode.DRAW_ONE, klondikeDifficulty = KlondikeDifficulty.HARD))
        vm.drawFromStock()
        var k = vm.uiState.value.klondike!!
        assertEquals(1, k.waste.size)
        assertEquals(23, k.stock.size)
        assertEquals(1, k.moveCount)
    }

    @Test
    fun newFreeCellSpiderPyramidDeals() {
        val vm = vm()
        vm.newFreeCellGame()
        val f = vm.uiState.value.freeCell!!
        assertEquals(8, f.tableauPiles.size)
        assertEquals(52, f.tableauPiles.sumOf { it.size })
        assertEquals(listOf(7, 7, 7, 7, 6, 6, 6, 6), f.tableauPiles.map { it.size })
        assertEquals(4, f.freeCells.size)
        assertTrue(f.freeCells.all { it == null })

        vm.newSpiderGame(GameConfig(spiderSuits = 1))
        val sp = vm.uiState.value.spider!!
        assertEquals(10, sp.tableauPiles.size)
        assertEquals(listOf(6, 6, 6, 6, 5, 5, 5, 5, 5, 5), sp.tableauPiles.map { it.faceDown.size + it.faceUp.size })
        assertEquals(5, sp.stockGroups.size)
        assertTrue(sp.tableauPiles.all { it.faceUp.size == 1 })

        vm.newPyramidGame(GameConfig(relaxed = true))
        val p = vm.uiState.value.pyramid!!
        assertEquals(7, p.pyramid.size)
        assertEquals((1..7).toList(), p.pyramid.map { it.size })
        assertEquals(24, p.stock.size)
        assertEquals("RELAXED", p.variant)
        // Bottom row is fully exposed; the apex is covered.
        assertTrue(vm.isPyramidCardExposed(p, 6, 0))
        assertFalse(vm.isPyramidCardExposed(p, 0, 0))
    }

    @Test
    fun klondikePlacementRules() {
        val vm = vm()
        val king = Card(Suit.SPADES, Rank.KING)
        val queenRed = Card(Suit.HEARTS, Rank.QUEEN)
        val queenBlack = Card(Suit.CLUBS, Rank.QUEEN)
        assertTrue(vm.canPlaceOnKlondikeTableau(king, TableauPile()))
        assertFalse(vm.canPlaceOnKlondikeTableau(queenRed, TableauPile()))
        val pile = TableauPile(faceUp = listOf(king))
        assertTrue(vm.canPlaceOnKlondikeTableau(queenRed, pile))
        assertFalse(vm.canPlaceOnKlondikeTableau(queenBlack, pile))
        // Face-down-only pile accepts nothing.
        assertFalse(vm.canPlaceOnKlondikeTableau(king, TableauPile(faceDown = listOf(king))))
    }

    @Test
    fun autoCompleteVisibility() {
        // Offered only when the run can actually finish (see KlondikeAutoCompletePlanTest).
        val aceHearts = Card(Suit.HEARTS, Rank.ACE)
        val almostDone = KlondikeState(
            stock = emptyList(), waste = listOf(Card(Suit.HEARTS, Rank.KING)),
            foundations = Suit.entries.map { suit ->
                Rank.entries.filter { suit != Suit.HEARTS || it != Rank.KING }.map { Card(suit, it) }
            },
        )
        assertTrue(canKlondikeAutoComplete(almostDone))
        // Face-down cards hide the button even with stock remaining.
        val withDown = almostDone.copy(
            stock = listOf(aceHearts),
            tableauPiles = listOf(TableauPile(faceDown = listOf(aceHearts), faceUp = listOf(aceHearts))) + List(6) { TableauPile() },
        )
        assertFalse(canKlondikeAutoComplete(withDown))
        assertFalse(canKlondikeAutoComplete(almostDone.copy(isWon = true)))
        // A board that could make a move or two but never finish is no longer offered
        // (it used to be, and the run then stalled at the end of the stock).
        val partial = KlondikeState(
            stock = emptyList(), waste = listOf(aceHearts),
            tableauPiles = listOf(TableauPile(faceUp = listOf(Card(Suit.SPADES, Rank.TWO)))) + List(6) { TableauPile() },
        )
        assertFalse(canKlondikeAutoComplete(partial))
    }

    @Test
    fun pyramidPairRulesSumToThirteen() {
        val vm = vm()
        val king = Card(Suit.SPADES, Rank.KING)
        val queen = Card(Suit.HEARTS, Rank.QUEEN)
        val ace = Card(Suit.CLUBS, Rank.ACE)
        // Bottom row exposed: K alone removable via pair-with-self check is in ops;
        // pair Q+A = 13 across two exposed bottom cards.
        val state = PyramidState(
            pyramid = listOf(listOf(king), listOf(queen, ace)),
            stock = emptyList(), waste = emptyList(),
        )
        assertTrue(vm.pyramidCardAt(state, "pyr_1_0") == queen)
        assertTrue(vm.canPyramidRemovePair(state, "pyr_1_0", "pyr_1_1"))
        assertFalse(vm.canPyramidRemovePair(state, "pyr_0_0", "pyr_1_0"))
        assertEquals(0 to 0, vm.parsePyramidId("pyr_0_0"))
        assertEquals(6 to 3, vm.parsePyramidId("pyr_6_3"))
    }

    @Test
    fun selectModeAndCurrentConfigRoundTrip() {
        val vm = vm()
        assertFalse(vm.hasActiveGame())
        vm.selectMode(GameMode.KLONDIKE, GameConfig(drawMode = DrawMode.DRAW_THREE, klondikeDifficulty = KlondikeDifficulty.RELAXED))
        assertEquals(GameMode.KLONDIKE, vm.uiState.value.gameMode)
        val cfg = vm.currentConfig()
        assertEquals(DrawMode.DRAW_THREE, cfg.drawMode)
        assertEquals(KlondikeDifficulty.RELAXED, cfg.klondikeDifficulty)
        vm.selectMode(GameMode.SPIDER, GameConfig(spiderSuits = 2))
        assertEquals(2, vm.currentConfig().spiderSuits)
    }
}
