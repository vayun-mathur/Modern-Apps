package com.vayunmathur.games.nonogram.domain

import com.vayunmathur.games.nonogram.data.NonogramPuzzle
import kotlin.random.Random

/**
 * Paints a random pattern, derives its clues, and keeps it only if [NonogramSolver] can crack it
 * without guessing.
 *
 * Most random patterns fail that test, which is why this loops. Rejection sampling is cheap here
 * because the check is line propagation over a grid of at most 15 — a handful of milliseconds — so a
 * few dozen attempts still finish fast enough to run on a background dispatcher while a spinner
 * shows. This is the opposite trade to `games/pipes`, whose win condition needs a full-board
 * uniqueness proof too slow to attempt on device.
 */
object NonogramGenerator {

    /**
     * A puzzle of [size] that line logic alone can finish, or null if [attempts] candidates all
     * needed guesswork.
     *
     * Returning null rather than looping forever keeps the caller in control: it can widen the search
     * with a fresh seed instead of blocking the UI indefinitely on a bad starting point.
     */
    fun generate(
        size: Int,
        rng: Random,
        fillRatio: Double = DEFAULT_FILL_RATIO,
        attempts: Int = DEFAULT_ATTEMPTS,
    ): NonogramPuzzle? {
        repeat(attempts) {
            val candidate = paint(size, rng, fillRatio)
            // An all-blank grid is technically line-solvable and completely pointless to play.
            if (candidate.none { it }) return@repeat
            val puzzle = NonogramPuzzle.from(size, candidate)
            if (NonogramSolver.isLineSolvable(size, puzzle.rowClues, puzzle.colClues)) return puzzle
        }
        return null
    }

    /**
     * A puzzle for [size], retrying with derived seeds until one is found.
     *
     * Every seed is a pure function of [seed], so the same level number always yields the same
     * puzzle however many rounds it takes — that is what lets saved progress stay valid.
     */
    fun generateSeeded(size: Int, seed: Long, rounds: Int = DEFAULT_ROUNDS): NonogramPuzzle? {
        for (round in 0 until rounds) {
            generate(size, Random(seed + round * SEED_STRIDE))?.let { return it }
        }
        return null
    }

    /** Independent coin flips per cell. Simple, and the solver filters out the unfair results. */
    private fun paint(size: Int, rng: Random, fillRatio: Double): List<Boolean> =
        List(size * size) { rng.nextDouble() < fillRatio }

    /**
     * Roughly half filled. Much sparser and the picture is mostly blank; much denser and the clues
     * collapse into a few long runs that give the whole thing away.
     */
    const val DEFAULT_FILL_RATIO = 0.55

    private const val DEFAULT_ATTEMPTS = 60

    /** Rounds the seeded search runs per attempt; retries add more rounds beyond this base. */
    const val DEFAULT_ROUNDS = 8

    /** Extra rounds each generation retry adds, so a retry widens a search that found nothing. */
    const val RETRY_EXTRA_ROUNDS = 8

    /** Keeps the per-round seeds far apart so consecutive rounds do not repaint near-identical grids. */
    private const val SEED_STRIDE = 1_000_003L
}
