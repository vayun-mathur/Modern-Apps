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
        //
        // The budget is charged in thread CPU time, not wall clock: wall
        // clock under a fully loaded test host mostly measures scheduler
        // contention from the other test workers (this test measured
        // 33.75 s of wall time in a loaded full-suite run), which says
        // nothing about whether generation itself regressed. CPU time
        // alone is not host-independent either - the same generation
        // costs ~21 s of CPU on this host and several times less on a
        // faster one - so the budget is expressed in units of a fixed
        // reference workload (SHA-256 over a fixed buffer) timed in
        // the same process immediately beforehand. Host speed and
        // load move the reference and the ladder together, while a
        // generator regression moves only the ladder. On this host the
        // ladder costs ~18 reference units; the budget allows 55
        // (the same roughly-3x looseness the old absolute budget had).
        val refStart = threadCpuTimeNanos()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(65_536) { (it * 31).toByte() }
        repeat(REF_ITERATIONS) { digest.digest(buf) }
        val refMillis = (threadCpuTimeNanos() - refStart) / 1_000_000
        val cpu = threadCpuTimeNanos()
        val boards = ladder.count { generate(it) != null }
        val millis = (threadCpuTimeNanos() - cpu) / 1_000_000
        assertEquals(LADDER_DEPTH, boards)
        assertTrue(
            millis < refMillis * MAX_LADDER_REF_RATIO,
            "generating $LADDER_DEPTH boards took ${millis}ms of CPU " +
                "(${millis / refMillis.coerceAtLeast(1)}x the ${refMillis}ms reference, " +
                "budget ${MAX_LADDER_REF_RATIO}x)",
        )
    }

    /** Current thread's CPU time, falling back to wall clock where the bean is unsupported. */
    private fun threadCpuTimeNanos(): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        return if (bean.isCurrentThreadCpuTimeSupported) {
            bean.currentThreadCpuTime
        } else {
            System.nanoTime()
        }
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

        /** Fixed reference workload size for the affordability budget (see the test). */
        const val REF_ITERATIONS = 20_000

        /**
         * Ladder budget in reference-workload units: roughly three times the
         * measured ~18x ratio, so ordinary variation does not turn this red.
         */
        const val MAX_LADDER_REF_RATIO = 55L

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
