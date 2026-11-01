package com.vayunmathur.tuner.domain

import kotlin.math.exp

/**
 * Online forward filtering over [stateCount] states with a sticky diagonal.
 *
 * Forward only, so it adds no latency: each hop's posterior uses the past and the present frame.
 * What it buys over per-frame decisions is accumulation - a strum's decay tail, too weak on its
 * own to separate C from C5, keeps the C it was confidently given a few hops earlier unless the
 * new evidence actually argues for something else.
 *
 * Transitions are uniform off the diagonal, which makes each step O(states).
 */
internal class ChordHmm(private val stateCount: Int, private val stay: Double = STAY) {
    val posterior = DoubleArray(stateCount) { 1.0 / stateCount }

    fun reset() {
        posterior.fill(1.0 / stateCount)
    }

    fun update(logLikelihood: DoubleArray) {
        val leave = (1.0 - stay) / (stateCount - 1)
        var peak = Double.NEGATIVE_INFINITY
        for (v in logLikelihood) if (v > peak) peak = v
        var total = 0.0
        for (s in 0 until stateCount) {
            // Sum over predecessors collapses because every off-diagonal weight is the same.
            val prior = posterior[s] * stay + (1.0 - posterior[s]) * leave
            posterior[s] = prior * exp(logLikelihood[s] - peak)
            total += posterior[s]
        }
        if (total <= 0.0) {
            reset()
            return
        }
        for (s in 0 until stateCount) posterior[s] /= total
    }

    private companion object {
        /** Per 85 ms hop. Swept against 0.7..0.97; see `ChordTemplates` for how flat that was. */
        const val STAY = 0.95
    }
}
