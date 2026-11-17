package com.vayunmathur.flashcards.util
import com.vayunmathur.flashcards.domain.Grade
import com.vayunmathur.flashcards.domain.ReviewSession
import com.vayunmathur.flashcards.domain.Scheduler
import com.vayunmathur.flashcards.ui.StudyMode
import com.vayunmathur.flashcards.ui.StudyParams

import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewSessionTest {

    private val now = 1_000_000_000_000L

    private fun newCard(id: Long, position: Double) =
        Card(id = id, noteId = id, templateOrd = 0, deckId = 1, position = position)

    private fun dueReviewCard(id: Long) = Card(
        id = id,
        noteId = id,
        templateOrd = 0,
        deckId = 1,
        state = CardState.REVIEW,
        stability = 10.0,
        difficulty = 5.0,
        reps = 3,
        lastReview = now - 15 * Scheduler.DAY_MS,
        dueDate = now - Scheduler.DAY_MS,
    )

    @Test
    fun respectsNewPerDayCap() {
        val cards = (1..10L).map { newCard(it, it.toDouble()) }
        val session = ReviewSession(cards, newPerDay = 3, maxReviews = 200, now = now, desiredRetention = 0.9)
        assertEquals(3, session.totalCards)
        assertEquals(3, session.newCount)
    }

    @Test
    fun respectsMaxReviewsCap() {
        val cards = (1..10L).map { dueReviewCard(it) }
        val session = ReviewSession(cards, newPerDay = 20, maxReviews = 4, now = now, desiredRetention = 0.9)
        assertEquals(4, session.totalCards)
        assertEquals(4, session.reviewCount)
    }

    @Test
    fun futureDueReviewCardsAreExcluded() {
        val future = dueReviewCard(1).copy(dueDate = now + 5 * Scheduler.DAY_MS)
        val session = ReviewSession(listOf(future), newPerDay = 20, maxReviews = 200, now = now, desiredRetention = 0.9)
        assertTrue(session.done)
    }

    @Test
    fun goodGraduatesAndLeavesTheQueue() {
        val cards = listOf(newCard(1, 1.0), newCard(2, 2.0))
        val session = ReviewSession(cards, newPerDay = 20, maxReviews = 200, now = now, desiredRetention = 0.9)
        val firstId = session.current!!.id
        session.grade(Grade.EASY, now)
        assertEquals(1, session.remaining)
        assertTrue(session.current!!.id != firstId)
    }

    @Test
    fun againReQueuesWithinSession() {
        val cards = (1..4L).map { newCard(it, it.toDouble()) }
        val session = ReviewSession(cards, newPerDay = 20, maxReviews = 200, now = now, desiredRetention = 0.9)
        val firstId = session.current!!.id
        session.grade(Grade.AGAIN, now)
        // Still four cards queued, and the failed card comes back later, not immediately.
        assertEquals(4, session.remaining)
        assertTrue(session.current!!.id != firstId, "Again card should not be shown again immediately")
        assertTrue(session.learningCount >= 1)
        // Drain the rest; the re-queued card must resurface.
        val seen = mutableSetOf<Long>()
        var guard = 0
        while (!session.done && guard++ < 50) {
            seen.add(session.current!!.id)
            session.grade(Grade.EASY, now)
        }
        assertTrue(firstId in seen, "the Again card must reappear this session")
    }

    @Test
    fun progressReachesOneWhenComplete() {
        val cards = (1..3L).map { newCard(it, it.toDouble()) }
        val session = ReviewSession(cards, newPerDay = 20, maxReviews = 200, now = now, desiredRetention = 0.9)
        var guard = 0
        while (!session.done && guard++ < 50) session.grade(Grade.GOOD, now)
        assertEquals(1f, session.progress)
    }

    @Test
    fun undoRestoresPreviousCard() {
        val cards = listOf(newCard(1, 1.0), newCard(2, 2.0))
        val session = ReviewSession(cards, newPerDay = 20, maxReviews = 200, now = now, desiredRetention = 0.9)
        val original = session.current!!
        session.grade(Grade.EASY, now)
        assertTrue(session.canUndo)
        val restored = session.undo()
        assertNotNull(restored)
        assertEquals(original.id, restored.id)
        assertEquals(CardState.NEW, restored.state)
        assertEquals(original.id, session.current!!.id)
    }

    @Test
    fun undoWithNothingToUndoReturnsNull() {
        val session = ReviewSession(emptyList(), newPerDay = 20, maxReviews = 200, now = now, desiredRetention = 0.9)
        assertNull(session.undo())
    }

    @Test
    fun suspendedCardsAreExcluded() {
        val cards = listOf(
            newCard(1, 1.0),
            newCard(2, 2.0).copy(suspended = 1),
        )
        val session = ReviewSession(cards, newPerDay = 20, maxReviews = 200, now = now, desiredRetention = 0.9)
        assertEquals(1, session.totalCards)
    }

    @Test
    fun newOnlyModeTakesOnlyNewCards() {
        val cards = listOf(newCard(1, 1.0), dueReviewCard(2), newCard(3, 3.0))
        val session = ReviewSession(
            cards, newPerDay = 20, maxReviews = 200, now = now, desiredRetention = 0.9,
            params = StudyParams(mode = StudyMode.NEW_ONLY, count = 10),
        )
        assertEquals(2, session.totalCards)
        assertEquals(2, session.newCount)
    }

    @Test
    fun cramModeIsPreviewOnlyAndTakesByCount() {
        val cards = (1..5L).map { newCard(it, it.toDouble()) }
        val session = ReviewSession(
            cards, newPerDay = 20, maxReviews = 200, now = now, desiredRetention = 0.9,
            params = StudyParams(mode = StudyMode.CRAM, count = 3),
        )
        assertEquals(3, session.totalCards)
        assertTrue(session.previewOnly)
    }

    @Test
    fun lapsesModeSelectsLapsedCards() {
        val lapsed = dueReviewCard(1).copy(lapses = 2)
        val clean = dueReviewCard(2)
        val session = ReviewSession(
            listOf(lapsed, clean), newPerDay = 20, maxReviews = 200, now = now, desiredRetention = 0.9,
            params = StudyParams(mode = StudyMode.LAPSES, count = 10),
        )
        assertEquals(1, session.totalCards)
    }
}
