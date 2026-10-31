package com.vayunmathur.todo.domain

import kotlin.random.Random

/**
 * Fractional ordering keys for manually sorted rows.
 *
 * A move only rewrites the moved row's key, so it syncs as one record. Two devices inserting
 * between the same neighbours would otherwise pick the identical midpoint; [between] jitters
 * inside the middle half of the gap so concurrent inserts keep a strict order.
 */
object Positions {
    const val STEP = 1024.0
    const val MIN_GAP = 1e-6
    private const val JITTER_SPAN = 0.25

    fun after(last: Double?): Double = (last ?: 0.0) + STEP

    fun before(first: Double?): Double = (first ?: 0.0) - STEP

    fun between(prev: Double?, next: Double?, random: Random = Random.Default): Double = when {
        prev == null && next == null -> 0.0
        prev == null -> before(next)
        next == null -> after(prev)
        else -> {
            val gap = next - prev
            prev + gap / 2 + gap * random.nextDouble(-JITTER_SPAN, JITTER_SPAN)
        }
    }

    /**
     * Key for the row at [from] after it is moved to [to] in [sorted] (ascending keys, the
     * moved row still at [from]).
     */
    fun forMove(sorted: List<Double>, from: Int, to: Int, random: Random = Random.Default): Double {
        val (prev, next) = if (to > from) {
            sorted.getOrNull(to) to sorted.getOrNull(to + 1)
        } else {
            sorted.getOrNull(to - 1) to sorted.getOrNull(to)
        }
        return between(prev, next, random)
    }

    /** True when two neighbours collide or are too close to split again. */
    fun needsRenormalize(sorted: List<Double>): Boolean =
        sorted.zipWithNext().any { (a, b) -> b - a < MIN_GAP }

    fun renormalized(count: Int): List<Double> = List(count) { (it + 1) * STEP }
}
