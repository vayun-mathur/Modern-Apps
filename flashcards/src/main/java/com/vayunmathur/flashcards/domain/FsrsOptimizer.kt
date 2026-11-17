package com.vayunmathur.flashcards.domain

import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.ReviewLog
import kotlin.math.ln

/**
 * A pragmatic FSRS weight optimizer. Given a deck's [ReviewLog] history grouped and
 * ordered per card, it runs **coordinate descent** to minimise the binary log-loss
 * between the model's predicted recall and the observed pass/fail, over a bounded
 * subset of the 19 FSRS weights, seeded from [Scheduler.DEFAULT_W].
 *
 * This is intentionally *not* a bit-exact reimplementation of Anki's optimizer — it
 * is a lightweight, dependency-free, unit-testable estimate. Pure Kotlin.
 */
object FsrsOptimizer {

    /** Minimum total reviews before optimization is attempted. */
    const val MIN_REVIEWS = 200

    // Weights we tune: initial stabilities (0..3), difficulty terms (4..6), and the
    // stability-growth terms (8..10). The rest keep their defaults.
    private val TUNABLE = intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 9, 10)
    private const val R_EPS = 1e-6
    /** Coordinate-descent step rates, coarse to fine. */
    private val STEP_RATES = doubleArrayOf(0.25, 0.1, 0.05)
    /** Max improvement passes per step rate. */
    private const val MAX_PASSES = 25
    /** Minimum loss improvement to accept a candidate. */
    private const val MIN_IMPROVEMENT = 1e-9

    /** True when [logsByCard] has enough contributing reviews to optimize. */
    fun hasEnough(logsByCard: List<List<ReviewLog>>): Boolean =
        logsByCard.sumOf { (it.size - 1).coerceAtLeast(0) } >= MIN_REVIEWS

    /**
     * Returns tuned weights, or a copy of [Scheduler.DEFAULT_W] when there is too
     * little history. [logsByCard] must be ordered chronologically within each card.
     */
    fun optimize(logsByCard: List<List<ReviewLog>>): DoubleArray {
        val weights = Scheduler.DEFAULT_W.copyOf()
        if (!hasEnough(logsByCard)) return weights
        var best = loss(weights, logsByCard)
        for (rate in STEP_RATES) {
            best = descendAtRate(weights, logsByCard, rate, best)
        }
        return weights
    }

    private fun descendAtRate(
        weights: DoubleArray,
        logsByCard: List<List<ReviewLog>>,
        rate: Double,
        initialBest: Double,
    ): Double {
        var best = initialBest
        var improved = true
        var guard = 0
        while (improved && guard++ < MAX_PASSES) {
            improved = false
            for (idx in TUNABLE) {
                if (tryWeight(weights, logsByCard, idx, rate, best)) {
                    best = loss(weights, logsByCard)
                    improved = true
                }
            }
        }
        return best
    }

    private fun tryWeight(
        weights: DoubleArray,
        logsByCard: List<List<ReviewLog>>,
        idx: Int,
        rate: Double,
        best: Double,
    ): Boolean {
        for (sign in intArrayOf(1, -1)) {
            val candidate = weights.copyOf()
            candidate[idx] = clamp(idx, weights[idx] * (1 + sign * rate))
            if (candidate[idx] == weights[idx]) continue
            if (loss(candidate, logsByCard) < best - MIN_IMPROVEMENT) {
                weights[idx] = candidate[idx]
                return true
            }
        }
        return false
    }

    /** Keeps a tuned weight positive and within 10x of its default magnitude. */
    private fun clamp(index: Int, value: Double): Double {
        val default = Scheduler.DEFAULT_W[index]
        val lo = maxOf(0.001, default * 0.1)
        val hi = maxOf(lo + 0.001, default * 10.0)
        return value.coerceIn(lo, hi)
    }

    /**
     * Binary log-loss of predicted recall vs observed pass across every review that
     * has a prior memory state (the first review of each card is skipped, since its
     * stability is only established by that review).
     */
    fun loss(weights: DoubleArray, logsByCard: List<List<ReviewLog>>): Double {
        var sum = 0.0
        var count = 0
        for (logs in logsByCard) {
            var card = Card(noteId = 0, templateOrd = 0, deckId = 0)
            var lastReview = 0L
            for (log in logs) {
                val elapsed = log.elapsedDays.coerceAtLeast(0.0)
                if (card.reps > 0 && card.stability > 0.0) {
                    val r = Scheduler.retrievability(elapsed, card.stability)
                        .coerceIn(R_EPS, 1.0 - R_EPS)
                    val observed = if (log.grade > 1) 1.0 else 0.0
                    sum += -(observed * ln(r) + (1.0 - observed) * ln(1.0 - r))
                    count++
                }
                val grade = gradeFrom(log.grade)
                val now = lastReview + (elapsed * Scheduler.DAY_MS).toLong()
                card = Scheduler.schedule(card.copy(lastReview = lastReview), grade, now, weights = weights)
                lastReview = now
            }
        }
        return if (count == 0) Double.MAX_VALUE else sum / count
    }

    private fun gradeFrom(value: Int): Grade =
        Grade.entries.firstOrNull { it.value == value } ?: Grade.GOOD
}
