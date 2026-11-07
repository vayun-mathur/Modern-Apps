package com.vayunmathur.flashcards.util

import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulerTest {

    private val now = 1_000_000_000_000L

    private fun newCard() = Card(id = 1, noteId = 1, templateOrd = 0, deckId = 1)

    private fun reviewCard(stability: Double, difficulty: Double, daysAgo: Int) = Card(
        id = 1,
        noteId = 1,
        templateOrd = 0,
        deckId = 1,
        stability = stability,
        difficulty = difficulty,
        state = CardState.REVIEW,
        reps = 3,
        lastReview = now - daysAgo * Scheduler.DAY_MS,
    )

    @Test
    fun retrievabilityDecaysWithElapsedTime() {
        val s = 10.0
        val r0 = Scheduler.retrievability(0.0, s)
        val r5 = Scheduler.retrievability(5.0, s)
        val r20 = Scheduler.retrievability(20.0, s)
        assertEquals(1.0, r0, 1e-9)
        assertTrue(r5 < r0, "recall should drop over time")
        assertTrue(r20 < r5, "recall should keep dropping")
    }

    @Test
    fun retrievabilityIsAboutNinetyPercentAtStabilityInterval() {
        // By construction, recall is ~0.9 after `stability` days.
        val r = Scheduler.retrievability(10.0, 10.0)
        assertEquals(0.9, r, 1e-6)
    }

    @Test
    fun intervalScalesInverselyWithDesiredRetention() {
        val s = 20.0
        val lowRetention = Scheduler.nextIntervalDays(s, 0.80)
        val highRetention = Scheduler.nextIntervalDays(s, 0.95)
        assertTrue(
            highRetention < lowRetention,
            "higher desired retention must shorten the interval ($highRetention !< $lowRetention)",
        )
    }

    @Test
    fun newCardInitializesMemoryState() {
        val graded = Scheduler.schedule(newCard(), Grade.GOOD, now)
        assertTrue(graded.stability > 0.0)
        assertTrue(graded.difficulty in 1.0..10.0)
        assertEquals(1, graded.reps)
    }

    @Test
    fun stabilityGrowsOnSuccessfulReview() {
        val card = reviewCard(stability = 10.0, difficulty = 5.0, daysAgo = 10)
        val graded = Scheduler.schedule(card, Grade.GOOD, now)
        assertTrue(
            graded.stability > card.stability,
            "stability should grow after recall (${graded.stability} !> ${card.stability})",
        )
    }

    @Test
    fun stabilityShrinksOnLapse() {
        val card = reviewCard(stability = 40.0, difficulty = 5.0, daysAgo = 30)
        val graded = Scheduler.schedule(card, Grade.AGAIN, now)
        assertTrue(
            graded.stability <= card.stability,
            "post-lapse stability must not exceed prior (${graded.stability} > ${card.stability})",
        )
        assertEquals(CardState.RELEARNING, graded.state)
        assertEquals(1, graded.lapses)
    }

    @Test
    fun difficultyStaysClamped() {
        var card = reviewCard(stability = 5.0, difficulty = 9.5, daysAgo = 5)
        repeat(10) {
            card = Scheduler.schedule(card, Grade.AGAIN, now)
            assertTrue(card.difficulty in 1.0..10.0, "difficulty out of range: ${card.difficulty}")
        }
        card = reviewCard(stability = 5.0, difficulty = 1.2, daysAgo = 5)
        repeat(10) {
            card = Scheduler.schedule(card, Grade.EASY, now)
            assertTrue(card.difficulty in 1.0..10.0, "difficulty out of range: ${card.difficulty}")
        }
    }

    @Test
    fun parseWeightsRejectsCorruptAndNonFiniteTokens() {
        val good = Scheduler.weightsToJson(Scheduler.DEFAULT_W)
        assertTrue(Scheduler.parseWeights(good).contentEquals(Scheduler.DEFAULT_W))
        // One corrupt token among 20 tokens must not drop-and-shift into 19 weights.
        val corrupt = "[" + (listOf("oops") + Scheduler.DEFAULT_W.map { it.toString() })
            .joinToString(",") + "]"
        assertTrue(Scheduler.parseWeights(corrupt).contentEquals(Scheduler.DEFAULT_W))
        val nan = "[" + (listOf("NaN") + Scheduler.DEFAULT_W.drop(1).map { it.toString() })
            .joinToString(",") + "]"
        assertTrue(Scheduler.parseWeights(nan).contentEquals(Scheduler.DEFAULT_W))
        assertTrue(Scheduler.parseWeights("[1,2,3]").contentEquals(Scheduler.DEFAULT_W))
        assertTrue(Scheduler.parseWeights("").contentEquals(Scheduler.DEFAULT_W))
    }

    @Test
    fun againUsesShortStepSoItRecursThisSession() {
        val graded = Scheduler.schedule(newCard(), Grade.AGAIN, now)
        assertTrue(
            graded.dueDate - now < Scheduler.DAY_MS,
            "Again should schedule a sub-day step",
        )
        assertEquals(CardState.LEARNING, graded.state)
    }

    @Test
    fun goodGraduatesToReviewWithDayScaleInterval() {
        val graded = Scheduler.schedule(newCard(), Grade.EASY, now)
        assertEquals(CardState.REVIEW, graded.state)
        assertTrue(graded.dueDate - now >= Scheduler.DAY_MS, "Easy should give a multi-day interval")
    }
}
