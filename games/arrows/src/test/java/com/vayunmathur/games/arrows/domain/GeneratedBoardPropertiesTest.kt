package com.vayunmathur.games.arrows.domain

import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.data.STARTING_HEARTS
import com.vayunmathur.games.arrows.data.arrowCountForLevel
import com.vayunmathur.games.arrows.data.availabilityCapFor
import com.vayunmathur.games.arrows.data.boardSizeForLevel
import com.vayunmathur.games.arrows.data.mirrorCountForLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The properties every board in the level ladder has to hold, checked against the real level
 * configuration rather than hand-built fixtures — these are the boards players actually get.
 */
class GeneratedBoardPropertiesTest {

    private val ladder = 1..LADDER_DEPTH

    private fun boardFor(level: Int): ArrowsPuzzle? = BOARDS[level]

    @Test
    fun everyLevelInTheLadderProducesABoard() {
        // A null here is a level the player cannot pass, so this is the one result that must never regress.
        val missing = ladder.filter { boardFor(it) == null }
        assertTrue(missing.isEmpty(), "no board generated for levels $missing")
    }

    @Test
    fun everyMirrorIsOnSomeArrowsRoute() {
        // The point of the exercise: a redirector nothing routes through is scenery.
        for (level in ladder) {
            val puzzle = boardFor(level) ?: continue
            val unused = puzzle.mirrors.keys.filter { mirror ->
                puzzle.pieces.none { piece ->
                    ArrowsRules.exitPath(puzzle, piece.head, piece.direction)?.contains(mirror) == true
                }
            }
            assertTrue(unused.isEmpty(), "level $level has mirrors no arrow uses: $unused")
        }
    }

    @Test
    fun mirrorsAreDroppedRatherThanLeftUnused() {
        // Relaxation may place fewer mirrors than asked for, but never more than it is allowed to drop.
        for (level in ladder) {
            val puzzle = boardFor(level) ?: continue
            val asked = mirrorCountForLevel(level)
            val floor = (asked - ArrowsGenerator.MAX_MIRRORS_DROPPED).coerceAtLeast(0)
            assertTrue(
                puzzle.mirrors.size in floor..asked,
                "level $level asked for $asked mirrors and got ${puzzle.mirrors.size}",
            )
        }
    }

    @Test
    fun noBoardOpensWithEveryArrowLaunchable() {
        // The complaint this was built to answer: if everything can go on move one it is not a puzzle.
        for (level in ladder) {
            val puzzle = boardFor(level) ?: continue
            val open = openCount(puzzle)
            assertTrue(
                open < puzzle.pieces.size,
                "level $level opens with all ${puzzle.pieces.size} arrows launchable",
            )
        }
    }

    @Test
    fun theOpeningStaysWithinTheAvailabilityCap() {
        for (level in ladder) {
            val puzzle = boardFor(level) ?: continue
            val cap = availabilityCapFor(puzzle.pieces.size) + ArrowsGenerator.MAX_OPEN_SLACK
            val open = openCount(puzzle)
            assertTrue(open <= cap, "level $level opens $open arrows, over the $cap ceiling")
        }
    }

    @Test
    fun theOpeningIsTightOnTheOverwhelmingMajorityOfLevels() {
        // The ceiling above is the hard guarantee; this pins the typical case, so a change that quietly
        // relies on the slack for every board fails here rather than shipping a ladder of loose openings.
        val strict = ladder.count { level ->
            val puzzle = boardFor(level) ?: return@count false
            openCount(puzzle) <= availabilityCapFor(puzzle.pieces.size)
        }
        assertTrue(
            strict >= LADDER_DEPTH * 8 / 10,
            "only $strict of $LADDER_DEPTH levels met the strict cap",
        )
    }

    @Test
    fun everyBoardCanActuallyBeCleared() {
        // Solvability is structural — arrows are added in the reverse of a working removal order — but
        // mirrors can bend a route back into the arrow that owns it, which would strand it forever. This
        // is what proves the guarantee survived turning mirrors on.
        for (level in ladder) {
            val puzzle = boardFor(level) ?: continue
            val order = ArrowsRules.solve(puzzle)
            assertNotNull(order, "level $level cannot be cleared")
            assertEquals(
                puzzle.pieces.size,
                order.size,
                "level $level solution leaves arrows behind",
            )
        }
    }

    @Test
    fun boardsGrowWithTheLevelAndThenStop() {
        val areas = (1..80).map { boardSizeForLevel(it).let { (c, r) -> c * r } }
        assertTrue(
            areas.zipWithNext().all { (a, b) -> b >= a },
            "board area must never shrink as levels advance",
        )
        assertEquals(
            boardSizeForLevel(200),
            boardSizeForLevel(80),
            "the board should stop growing rather than run away",
        )
    }

    @Test
    fun generatingTheWholeLadderStaysAffordable() {
        // Generation is off the main thread behind a spinner, so this is about not leaving the player
        // watching it. Deliberately loose: it is a guard against a pathological regression, not a target.
        // Generates fresh rather than reusing the shared ladder, since the point is to time the work.
        val started = System.nanoTime()
        val boards = ladder.count { generate(it) != null }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(LADDER_DEPTH, boards)
        assertTrue(millis < BUDGET_MILLIS, "generating $LADDER_DEPTH boards took ${millis}ms")
    }

    /**
     * Arrows that could leave immediately.
     *
     * Deliberately written from [ArrowsRules] rather than reusing the generator's own bookkeeping, so a
     * bug in that bookkeeping cannot hide behind agreeing with itself.
     */
    private fun openCount(puzzle: ArrowsPuzzle): Int {
        val state = ArrowsGameState(
            puzzle = puzzle,
            removed = emptySet(),
            hearts = STARTING_HEARTS,
            mode = GameMode.CASUAL,
            level = 1,
        )
        return ArrowsRules.clearableNow(state).size
    }

    private companion object {
        /** Deep enough to cover every board size and mirror count the ladder reaches. */
        const val LADDER_DEPTH = 120

        const val LEVEL_SEED = 104_729L

        /** Roughly three times the measured cost, so ordinary variation does not turn this red. */
        const val BUDGET_MILLIS = 25_000L

        fun generate(level: Int): ArrowsPuzzle? = ArrowsGenerator.generateSeeded(
            cols = boardSizeForLevel(level).first,
            rows = boardSizeForLevel(level).second,
            targetPieces = arrowCountForLevel(level),
            mirrorCount = mirrorCountForLevel(level),
            seed = level.toLong() * LEVEL_SEED,
        )

        /**
         * The whole ladder, built once.
         *
         * JUnit makes a fresh instance per test method, so without this every test would rebuild all 120
         * boards and the suite would spend over a minute generating the same thing eight times.
         */
        val BOARDS: Map<Int, ArrowsPuzzle?> by lazy {
            (1..LADDER_DEPTH).associateWith { generate(it) }
        }
    }
}
