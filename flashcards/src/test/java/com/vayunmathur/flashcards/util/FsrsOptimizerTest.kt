package com.vayunmathur.flashcards.util
import com.vayunmathur.flashcards.domain.FsrsOptimizer
import com.vayunmathur.flashcards.domain.Scheduler

import com.vayunmathur.flashcards.data.ReviewLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FsrsOptimizerTest {

    /** Builds [cards] cards each with [perCard] reviews; every 4th review is a lapse. */
    private fun history(cards: Int, perCard: Int): List<List<ReviewLog>> =
        (1..cards).map { c ->
            (0 until perCard).map { i ->
                ReviewLog(
                    cardId = c.toLong(),
                    deckId = 1,
                    reviewedAt = i.toLong(),
                    grade = if (i % 4 == 0) 1 else 3,
                    elapsedDays = (i + 1).toDouble(),
                    scheduledDays = 0.0,
                    state = 0,
                )
            }
        }

    @Test
    fun optimizeDoesNotIncreaseLoss() {
        val logs = history(60, 6)
        val default = Scheduler.DEFAULT_W
        val tuned = FsrsOptimizer.optimize(logs)
        assertTrue(
            FsrsOptimizer.loss(tuned, logs) <= FsrsOptimizer.loss(default, logs) + 1e-9,
            "optimized loss must not exceed default",
        )
    }

    @Test
    fun tunedWeightsStayWithinClamps() {
        val logs = history(60, 6)
        val tuned = FsrsOptimizer.optimize(logs)
        assertEquals(Scheduler.DEFAULT_W.size, tuned.size)
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 8, 9, 10).forEach { idx ->
            val d = Scheduler.DEFAULT_W[idx]
            val lo = maxOf(0.001, d * 0.1)
            val hi = maxOf(lo + 0.001, d * 10.0)
            assertTrue(tuned[idx] in lo..hi, "weight $idx=${tuned[idx]} out of clamp [$lo,$hi]")
        }
    }

    @Test
    fun belowThresholdReturnsDefault() {
        val logs = history(5, 3) // 15 reviews < MIN_REVIEWS
        val tuned = FsrsOptimizer.optimize(logs)
        assertTrue(tuned.contentEquals(Scheduler.DEFAULT_W))
    }
}
