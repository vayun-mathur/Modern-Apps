package com.vayunmathur.games.solitaire.domain

import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.FreeCellState
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeDifficulty
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.PyramidState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SpiderState
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.data.createShuffledDeck
import com.vayunmathur.games.solitaire.data.createSpiderDeck
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Solvers on small positions with known answers, plus deal and table-format invariants. */
class SolitaireSolversTest {

    private fun suit(s: Suit, ranks: IntRange) = ranks.map { Card(s, Rank.entries[it - 1]) }
    private fun full(s: Suit) = suit(s, 1..13)
    private val budget = SolverBudget(dfsNodes = 50_000)

    private fun klondike(
        tableau: List<TableauPile>,
        stock: List<Card> = emptyList(),
        waste: List<Card> = emptyList(),
        draw: DrawMode = DrawMode.DRAW_ONE,
        redeals: Int = 0,
    ) = KlondikeState(
        stock = stock,
        waste = waste,
        tableauPiles = tableau + List(7 - tableau.size) { TableauPile() },
        // Foundations are suit-indexed (Suit.entries order): only hearts are left in play.
        foundations = listOf(emptyList(), full(Suit.DIAMONDS), full(Suit.SPADES), full(Suit.CLUBS)),
        drawMode = draw,
        redealsRemaining = redeals,
    )

    @Test fun klondikeDrawOneSolvesAndLineIsLegalShape() {
        // A♥ over 2♥ in a column, 3♥..K♥ in the stock with 3♥ on top.
        val state = klondike(
            tableau = listOf(TableauPile(faceDown = suit(Suit.HEARTS, 2..2), faceUp = suit(Suit.HEARTS, 1..1))),
            stock = suit(Suit.HEARTS, 3..13).reversed(),
        )
        val result = KlondikeSolver.solve(state, budget)
        assertEquals(SolveStatus.SOLVED, result.status)
        val moves = KlondikeSolver.decode(result.moves)
        assertEquals(11, moves.count { it == KlondikeMove.Draw })
        assertEquals(13, moves.count { it !is KlondikeMove.Draw })
    }

    @Test fun klondikeDrawThreeSameStockIsUnsolvableEvenWithUnlimitedPasses() {
        // Drawing three at a time only ever exposes 5♥, 8♥, J♥, K♥: 3♥ is never reachable.
        val tableau = listOf(TableauPile(faceDown = suit(Suit.HEARTS, 2..2), faceUp = suit(Suit.HEARTS, 1..1)))
        val stock = suit(Suit.HEARTS, 3..13).reversed()
        for (redeals in listOf(0, 2, Int.MAX_VALUE)) {
            val result = KlondikeSolver.solve(klondike(tableau, stock, draw = DrawMode.DRAW_THREE, redeals = redeals), budget)
            assertEquals(SolveStatus.UNSOLVABLE, result.status, "redeals=$redeals")
        }
    }

    @Test fun klondikeBuriedAceIsUnsolvable() {
        // 2♥ sits on the face-down A♥ and only a black 3 could take it: none exists.
        val state = klondike(
            tableau = listOf(TableauPile(faceDown = suit(Suit.HEARTS, 1..1), faceUp = suit(Suit.HEARTS, 2..2))),
            waste = suit(Suit.HEARTS, 3..13).reversed(),
            redeals = Int.MAX_VALUE,
        )
        assertEquals(SolveStatus.UNSOLVABLE, KlondikeSolver.solve(state, budget).status)
    }

    @Test fun klondikePassLimitDecidesWin() {
        // 3♥ is the waste bottom: reachable only after a recycle.
        val state = klondike(
            tableau = listOf(TableauPile(faceUp = suit(Suit.HEARTS, 1..1)), TableauPile(faceUp = suit(Suit.HEARTS, 2..2))),
            waste = suit(Suit.HEARTS, 3..13),
        )
        assertEquals(SolveStatus.UNSOLVABLE, KlondikeSolver.solve(state, budget).status)
        assertEquals(SolveStatus.SOLVED, KlondikeSolver.solve(state.copy(redealsRemaining = 1), budget).status)
    }

    private fun freeCell(columns: List<List<Card>>, cells: List<Card?>) = FreeCellState(
        tableauPiles = columns + List(8 - columns.size) { emptyList() },
        freeCells = cells,
        foundations = listOf(emptyList(), full(Suit.DIAMONDS), full(Suit.SPADES), full(Suit.CLUBS)),
    )

    @Test fun freeCellDigsOutBuriedAcesWithSupermoves() {
        // Hearts and spades in play, each Ace under a 12-card alternating run (K down to 2):
        // only a supermove into an empty column frees them.
        fun run(ace: Suit, kingSuit: Suit, other: Suit) =
            listOf(Card(ace, Rank.ACE)) + (13 downTo 2).map { r -> Card(if ((13 - r) % 2 == 0) kingSuit else other, Rank.entries[r - 1]) }
        val state = FreeCellState(
            tableauPiles = listOf(run(Suit.HEARTS, Suit.SPADES, Suit.HEARTS), run(Suit.SPADES, Suit.HEARTS, Suit.SPADES)) +
                List(6) { emptyList() },
            foundations = listOf(emptyList(), full(Suit.DIAMONDS), emptyList(), full(Suit.CLUBS)),
        )
        val result = FreeCellSolver.solve(state, budget)
        assertEquals(SolveStatus.SOLVED, result.status)
        assertTrue(FreeCellSolver.decode(result.moves).any { it is FreeCellMove.TableauToTableau })
    }

    @Test fun freeCellTwelveSameColourCardsOverTheAceIsUnsolvable() {
        // All hearts in one column, Ace at the bottom: twelve cards that cannot stack on each
        // other need twelve free spots, and there are only eleven (4 cells + 7 columns).
        val state = freeCell(listOf(suit(Suit.HEARTS, 1..13)), List(4) { null })
        assertEquals(SolveStatus.UNSOLVABLE, FreeCellSolver.solve(state, budget).status)
    }

    @Test fun freeCellFullyBlockedIsUnsolvable() {
        // Cells hold 10-K, every column is occupied, and 9♥ covers the A♥ with nowhere to go.
        val columns = listOf(suit(Suit.HEARTS, 1..1) + suit(Suit.HEARTS, 9..9)) + (2..8).map { suit(Suit.HEARTS, it..it) }
        val state = freeCell(columns, suit(Suit.HEARTS, 10..13))
        assertEquals(SolveStatus.UNSOLVABLE, FreeCellSolver.solve(state, budget).status)
    }

    private fun pyramidWith(bottom: Card, stock: List<Card>, relaxed: Boolean) = PyramidState(
        pyramid = List(7) { r -> List<Card?>(r + 1) { c -> if (r == 6 && c == 0) bottom else null } },
        stock = stock,
        relaxed = relaxed,
    )

    @Test fun pyramidPairsWithWaste() {
        val queen = Card(Suit.SPADES, Rank.QUEEN)
        val ace = Card(Suit.HEARTS, Rank.ACE)
        val two = Card(Suit.HEARTS, Rank.TWO)
        val result = PyramidSolver.solve(pyramidWith(queen, listOf(ace, two), relaxed = false), budget)
        assertEquals(SolveStatus.SOLVED, result.status)
        assertEquals(
            listOf(PyramidMove.Deal, PyramidMove.Deal, PyramidMove.RemovePair("pyr_6_0", "waste")),
            PyramidSolver.decode(result.moves),
        )
        // Without a match in the stock it is a proof of failure, relaxed or not.
        for (relaxed in listOf(false, true)) {
            assertEquals(SolveStatus.UNSOLVABLE, PyramidSolver.solve(pyramidWith(queen, listOf(two), relaxed), budget).status)
        }
    }

    @Test fun pyramidRecycleNeededOnlyWhenRelaxed() {
        // Fresh, the Ace is dealt first and pairs. Once both are dealt the 2 covers the Ace and
        // cannot be removed: only a recycle (relaxed) brings the Ace back.
        val queen = Card(Suit.SPADES, Rank.QUEEN)
        val stock = listOf(Card(Suit.HEARTS, Rank.TWO), Card(Suit.HEARTS, Rank.ACE))
        // Both dealt already: the waste is [A, 2] with the 2 on top.
        val dealtPast = pyramidWith(queen, emptyList(), relaxed = false).copy(waste = stock.reversed())
        assertEquals(SolveStatus.SOLVED, PyramidSolver.solve(pyramidWith(queen, stock, relaxed = false), budget).status)
        assertEquals(SolveStatus.UNSOLVABLE, PyramidSolver.solve(dealtPast, budget).status)
        assertEquals(SolveStatus.SOLVED, PyramidSolver.solve(dealtPast.copy(relaxed = true), budget).status)
    }

    @Test fun spiderCompletesLastRunAcrossColumns() {
        // Seven suits done; K-2 of spades in one column (King face-down), the Ace alone in another.
        val spades = suit(Suit.SPADES, 1..13)
        val piles = listOf(
            TableauPile(faceDown = listOf(spades[12]), faceUp = spades.subList(1, 12).reversed()),
            TableauPile(faceUp = listOf(spades[0])),
        ) + List(8) { TableauPile() }
        val state = SpiderState(tableauPiles = piles, completedSuits = 7, suitCount = 1)
        val result = SpiderSolver.solve(state, SolverBudget(dfsNodes = 50_000, beamWidth = 50, beamNodes = 50_000))
        assertEquals(SolveStatus.SOLVED, result.status)
        assertTrue(SpiderSolver.decode(result.moves).all { it is SpiderMove.Move })
    }

    @Test fun dealsMatchTheLegacyShuffleExactly() {
        // The deal function must reproduce the pre-table deal for any seed, card for card.
        for (seed in listOf(1L, 42L, 1_794_515_229_000L)) {
            val deck = createShuffledDeck(seed)
            val k = SolitaireDeals.klondike(seed, GameConfig())
            assertEquals(deck, k.tableauPiles.flatMap { it.faceDown + it.faceUp } + k.stock)
            assertEquals(deck.take(28), SolitaireDeals.pyramid(seed, GameConfig()).pyramid.flatten())
            assertEquals(deck.drop(28), SolitaireDeals.pyramid(seed, GameConfig()).stock)
            val fc = SolitaireDeals.freeCell(seed)
            assertEquals(deck, (0 until 52).map { fc.tableauPiles[it % 8][it / 8] })
            val spiderDeck = createSpiderDeck(2, seed)
            val sp = SolitaireDeals.spider(seed, GameConfig(spiderSuits = 2))
            assertEquals(spiderDeck, sp.tableauPiles.flatMap { it.faceDown + it.faceUp } + sp.stockGroups.flatten())
            assertEquals(seed, k.seed)
        }
    }

    @Test fun klondikeTableauIsTheDeckPrefixColumnByColumn() {
        val deck = createShuffledDeck(7)
        val k = SolitaireDeals.klondike(7, GameConfig())
        assertEquals(deck.take(28), k.tableauPiles.flatMap { it.faceDown + it.faceUp })
        assertEquals((0 until 7).map { it }, k.tableauPiles.map { it.faceDown.size })
    }

    @Test fun tableFormatRoundTripsAndPicksOnlyTableSeeds() {
        val tables = mapOf("KLONDIKE__DRAW_ONE_HARD" to intArrayOf(3, 1, 900_000, 77), "SPIDER__4SUIT" to intArrayOf(5))
        val decoded = SolvableDealTables.decode(SolvableDealTables.encode(tables))
        val config = GameConfig(drawMode = DrawMode.DRAW_ONE, klondikeDifficulty = KlondikeDifficulty.HARD)
        assertEquals(listOf(1, 3, 77, 900_000), decoded.seeds(GameMode.KLONDIKE, config)!!.toList())
        val random = Random(1)
        repeat(200) { assertTrue(decoded.pick(GameMode.KLONDIKE, config, random)!!.toInt() in tables.getValue("KLONDIKE__DRAW_ONE_HARD")) }
        assertEquals(5L, decoded.pick(GameMode.SPIDER, GameConfig(spiderSuits = 4), random))
        assertNull(decoded.pick(GameMode.FREECELL, GameConfig(), random))
    }

    @Test fun dailySeedIsDeterministicAndFromTheTable() {
        val seeds = IntArray(1000) { it * 7 + 1 }
        val tables = SolvableDealTables(mapOf("FREECELL__STANDARD" to seeds))
        val again = SolvableDealTables.decode(SolvableDealTables.encode(mapOf("FREECELL__STANDARD" to seeds)))
        val days = (20_000L until 20_400L).map { tables.seedForDay(GameMode.FREECELL, GameConfig(), it)!! }
        assertEquals(days, (20_000L until 20_400L).map { again.seedForDay(GameMode.FREECELL, GameConfig(), it)!! })
        assertTrue(days.all { it.toInt() in seeds })
        // Scattered, not walking the table: 400 days hit many distinct entries.
        assertTrue(days.toSet().size > 300)
    }

    @Test fun tableKeysAreTheStatsVariantsForEveryDialogConfig() {
        assertEquals(14, SolvableDeals.allConfigs.size)
        assertEquals(14, SolvableDeals.allConfigs.map { (m, c) -> SolvableDeals.tableKey(m, c) }.toSet().size)
        assertEquals("KLONDIKE__DRAW_THREE_TWO_PASSES", SolvableDeals.tableKey(GameMode.KLONDIKE, GameConfig(DrawMode.DRAW_THREE, KlondikeDifficulty.TWO_PASSES)))
        assertEquals("SPIDER__4SUIT", SolvableDeals.tableKey(GameMode.SPIDER, GameConfig(spiderSuits = 4)))
        assertEquals("PYRAMID__ORIGINAL", SolvableDeals.tableKey(GameMode.PYRAMID, GameConfig(relaxed = false)))
        assertEquals("FREECELL__STANDARD", SolvableDeals.tableKey(GameMode.FREECELL, GameConfig()))
    }

    @Test fun ladderNestsPassLimits() {
        val hardest = DealProofs.ladder(GameMode.KLONDIKE, GameConfig(DrawMode.DRAW_THREE, KlondikeDifficulty.RELAXED))
        assertEquals(
            listOf(KlondikeDifficulty.HARD, KlondikeDifficulty.TWO_PASSES, KlondikeDifficulty.REGULAR, KlondikeDifficulty.RELAXED),
            hardest.map { it.klondikeDifficulty },
        )
        assertTrue(hardest.all { it.drawMode == DrawMode.DRAW_THREE })
        assertEquals(listOf(false, true), DealProofs.ladder(GameMode.PYRAMID, GameConfig(relaxed = true)).map { it.relaxed })
        assertEquals(1, DealProofs.ladder(GameMode.SPIDER, GameConfig(spiderSuits = 2)).size)
    }
}
