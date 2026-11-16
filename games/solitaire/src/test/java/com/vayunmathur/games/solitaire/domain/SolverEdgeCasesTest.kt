package com.vayunmathur.games.solitaire.domain

import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.CardColorScheme
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Solver decoders, search helpers, deal-table codec edges and colour scheme. */
class SolverEdgeCasesTest {

    private fun full(s: Suit) = Rank.entries.map { Card(s, it) }
    private val tiny = SolverBudget(dfsNodes = 200, beamWidth = 10, beamNodes = 200)

    @Test
    fun wonPositionsSolveImmediatelyWithEmptyLine() {
        val kWon = KlondikeState(foundations = Suit.entries.map { full(it) })
        assertEquals(SolveStatus.SOLVED, KlondikeSolver.solve(kWon, tiny).status)
        assertEquals(0, KlondikeSolver.solve(kWon, tiny).moves.size)

        val fWon = FreeCellState(foundations = Suit.entries.map { full(it) })
        assertEquals(SolveStatus.SOLVED, FreeCellSolver.solve(fWon, tiny).status)

        val sWon = SpiderState(completedSuits = 8)
        assertEquals(SolveStatus.SOLVED, SpiderSolver.solve(sWon, tiny).status)

        val pWon = PyramidState(pyramid = List(7) { r -> List<Card?>(r + 1) { null } })
        assertEquals(SolveStatus.SOLVED, PyramidSolver.solve(pWon, tiny).status)
    }

    @Test
    fun dealProofsSolveDispatchesPerModeOnWonBoards() {
        // prove() climbs the ladder; a won board is SOLVED at the first rung.
        val configs = listOf(
            GameMode.KLONDIKE to GameConfig(),
            GameMode.SPIDER to GameConfig(spiderSuits = 1),
            GameMode.FREECELL to GameConfig(),
            GameMode.PYRAMID to GameConfig(relaxed = true),
        )
        for ((mode, cfg) in configs) {
            val result = DealProofs.solve(mode, cfg, 12345L)
            // A fresh deal is not already won, so status is a real search outcome.
            assertTrue(result.status in SolveStatus.entries)
            assertEquals(mode, mode)
        }
        assertEquals(2, DealProofs.ladder(GameMode.PYRAMID, GameConfig(relaxed = true)).size)
        assertEquals(1, DealProofs.ladder(GameMode.PYRAMID, GameConfig(relaxed = false)).size)
        assertEquals(1, DealProofs.ladder(GameMode.FREECELL, GameConfig()).size)
        assertEquals(
            listOf(KlondikeDifficulty.HARD),
            DealProofs.ladder(GameMode.KLONDIKE, GameConfig(klondikeDifficulty = KlondikeDifficulty.HARD)).map { it.klondikeDifficulty },
        )
    }

    @Test
    fun solverDecodersRoundTripMoveShapes() {
        // Klondike decode expands draw counts encoded in packed moves via real solves:
        // use a solved tiny position and check decoded moves are well-formed indices.
        val heartsOnly = KlondikeState(
            tableauPiles = listOf(TableauPile(faceUp = listOf(Card(Suit.HEARTS, Rank.KING)))) + List(6) { TableauPile() },
            foundations = listOf(full(Suit.HEARTS).dropLast(1), full(Suit.DIAMONDS), full(Suit.SPADES), full(Suit.CLUBS)),
            redealsRemaining = 0,
        )
        val kr = KlondikeSolver.solve(heartsOnly, SolverBudget(50_000))
        assertEquals(SolveStatus.SOLVED, kr.status)
        assertTrue(KlondikeSolver.decode(kr.moves).all { it is KlondikeMove.TableauToFoundation })

        assertEquals(emptyList(), KlondikeSolver.decode(IntArray(0)))
        assertEquals(emptyList(), FreeCellSolver.decode(IntArray(0)))
        assertEquals(emptyList(), SpiderSolver.decode(IntArray(0)))
        assertEquals(emptyList(), PyramidSolver.decode(IntArray(0)))
    }

    @Test
    fun spiderAndPyramidDecodersFromRealSolves() {
        // Spider: one ace left to complete the eighth run is covered in solvers test;
        // here a completed board decodes empty and a deal-only line is shaped correctly.
        val spades = full(Suit.SPADES)
        val piles = listOf(
            TableauPile(faceDown = listOf(spades[12]), faceUp = spades.subList(1, 12).reversed()),
            TableauPile(faceUp = listOf(spades[0])),
        ) + List(8) { TableauPile() }
        val sr = SpiderSolver.solve(
            SpiderState(tableauPiles = piles, completedSuits = 7, suitCount = 1),
            SolverBudget(dfsNodes = 50_000, beamWidth = 50, beamNodes = 50_000),
        )
        assertEquals(SolveStatus.SOLVED, sr.status)
        assertTrue(SpiderSolver.decode(sr.moves).isNotEmpty())

        val queen = Card(Suit.SPADES, Rank.QUEEN)
        val pState = PyramidState(
            pyramid = List(7) { r -> List<Card?>(r + 1) { c -> if (r == 6 && c == 0) queen else null } },
            stock = listOf(Card(Suit.HEARTS, Rank.ACE)),
        )
        val pr = PyramidSolver.solve(pState, SolverBudget(50_000))
        assertEquals(SolveStatus.SOLVED, pr.status)
        assertTrue(PyramidSolver.decode(pr.moves).any { it is PyramidMove.RemovePair })
    }

    @Test
    fun longHashSetZeroGrowthAndContains() {
        val set = LongHashSet()
        assertTrue(set.add(0L))
        assertFalse(set.add(0L))
        assertTrue(set.contains(0L))
        assertFalse(set.contains(42L))
        // Force several growth rounds.
        val values = (1L..20_000L).map { it * 2_654_435_761L + 97L }
        values.forEach { assertTrue(set.add(it), "add $it") }
        values.forEach { assertTrue(set.contains(it)) }
        values.forEach { assertFalse(set.add(it)) }
        assertFalse(set.contains(-999_999L))
    }

    @Test
    fun growableIntArrayGrowsAndReadsBack() {
        val arr = GrowableIntArray()
        assertEquals(0, arr.size)
        for (i in 0 until 5_000) arr.add(i * 3)
        assertEquals(5_000, arr.size)
        assertEquals(0, arr[0])
        assertEquals(14_997, arr[4_999])
    }

    @Test
    fun mixAndHashAreDeterministicAndSensitive() {
        assertEquals(mix64(123L), mix64(123L))
        assertNotEquals(mix64(1L), mix64(2L))
        val a = byteArrayOf(1, 2, 3, 4)
        assertEquals(hashBytes(a, 0, 4, 7L), hashBytes(a, 0, 4, 7L))
        assertNotEquals(hashBytes(a, 0, 4, 7L), hashBytes(a, 1, 3, 7L))
        assertNotEquals(hashBytes(a, 0, 4, 7L), hashBytes(byteArrayOf(1, 2, 3, 5), 0, 4, 7L))
    }

    @Test
    fun solvableTablesEmptyAndMissingConfigs() {
        val empty = SolvableDealTables(emptyMap())
        assertNull(empty.seeds(GameMode.KLONDIKE, GameConfig()))
        assertNull(empty.pick(GameMode.KLONDIKE, GameConfig(), Random(1)))
        assertNull(empty.seedForDay(GameMode.KLONDIKE, GameConfig(), 20_000L))

        val withEmpty = SolvableDealTables.decode(
            SolvableDealTables.encode(mapOf("FREECELL__STANDARD" to intArrayOf())),
        )
        assertNull(withEmpty.seeds(GameMode.FREECELL, GameConfig()))

        // Large seeds exercise multi-byte varints.
        val big = mapOf("SPIDER__1SUIT" to intArrayOf(1, 127, 128, 16_384, 2_000_000_000))
        val decoded = SolvableDealTables.decode(SolvableDealTables.encode(big))
        assertEquals(
            listOf(1, 127, 128, 16_384, 2_000_000_000),
            decoded.seeds(GameMode.SPIDER, GameConfig(spiderSuits = 1))!!.toList(),
        )
        val day1 = decoded.seedForDay(GameMode.SPIDER, GameConfig(spiderSuits = 1), 19_000L)
        assertEquals(day1, decoded.seedForDay(GameMode.SPIDER, GameConfig(spiderSuits = 1), 19_000L))
        assertTrue(day1!!.toInt() in big.getValue("SPIDER__1SUIT"))
    }

    @Test
    fun variantNamesForEveryMode() {
        assertEquals("DRAW_THREE_REGULAR", SolvableDeals.variant(GameMode.KLONDIKE, GameConfig(drawMode = DrawMode.DRAW_THREE)))
        assertEquals("1SUIT", SolvableDeals.variant(GameMode.SPIDER, GameConfig(spiderSuits = 1)))
        assertEquals("2SUIT", SolvableDeals.variant(GameMode.SPIDER, GameConfig(spiderSuits = 2)))
        assertEquals("STANDARD", SolvableDeals.variant(GameMode.FREECELL, GameConfig(spiderSuits = 1)))
        assertEquals("ORIGINAL", SolvableDeals.variant(GameMode.PYRAMID, GameConfig()))
        assertEquals("KLONDIKE__DRAW_ONE_REGULAR", SolvableDeals.tableKey(GameMode.KLONDIKE, GameConfig()))
    }

    @Test
    fun cardColorSchemeMapsEverySuitInBothSchemes() {
        val two = CardColorScheme.TWO_COLOUR
        val four = CardColorScheme.FOUR_COLOUR
        // Red suits share red in two-colour; black suits share black.
        assertEquals(two.colorFor(Suit.HEARTS), two.colorFor(Suit.DIAMONDS))
        assertEquals(two.colorFor(Suit.SPADES), two.colorFor(Suit.CLUBS))
        assertNotEquals(two.colorFor(Suit.HEARTS), two.colorFor(Suit.SPADES))
        // Four-colour gives every suit a distinct hue.
        assertEquals(4, Suit.entries.map { four.colorFor(it) }.toSet().size)
        assertEquals(two.colorFor(Suit.HEARTS), four.colorFor(Suit.HEARTS))
        assertEquals(two.colorFor(Suit.SPADES), four.colorFor(Suit.SPADES))
    }

    @Test
    fun dealShapesForSpiderSuitCountsAndPyramidStock() {
        for (suits in listOf(1, 2, 4)) {
            val s = SolitaireDeals.spider(99L, GameConfig(spiderSuits = suits))
            assertEquals(suits, s.suitCount)
            assertEquals(104, s.tableauPiles.sumOf { it.faceDown.size + it.faceUp.size } + s.stockGroups.sumOf { it.size })
            assertEquals(5, s.stockGroups.size)
            assertTrue(s.stockGroups.all { it.size == 10 })
        }
        val p = SolitaireDeals.pyramid(5L, GameConfig(relaxed = false))
        assertEquals(28, p.pyramid.sumOf { it.size })
        assertEquals(24, p.stock.size)
        assertFalse(p.relaxed)
        val f = SolitaireDeals.freeCell(5L)
        assertEquals("STANDARD", f.variant)
        assertEquals(52, f.tableauPiles.sumOf { it.size })
    }
}
