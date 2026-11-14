package com.vayunmathur.flashcards.util

import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardState

/**
 * An in-memory review queue for a single study session. Pure Kotlin (no Android
 * dependency) so it can be unit-tested directly.
 *
 * The queue is seeded from the deck's due cards, capped at [newPerDay] new cards
 * and [maxReviews] review cards. Grading runs the [Scheduler]; cards that land in
 * a learning/relearning step (e.g. after `AGAIN`) are re-inserted a few positions
 * back so they resurface later in *this* session instead of dropping out, while
 * graduated cards leave the queue and count towards [progress].
 */
class ReviewSession(
    cards: List<Card>,
    private val newPerDay: Int,
    private val maxReviews: Int,
    now: Long,
    private val desiredRetention: Double,
    private val weights: DoubleArray = Scheduler.DEFAULT_W,
    params: StudyParams = StudyParams(),
) {
    private val queue = ArrayDeque<Card>()

    /** True in cram mode: grading is preview-only and must not be persisted. */
    val previewOnly: Boolean = params.previewOnly

    /** Distinct cards this session started with. */
    val totalCards: Int
    private var completed = 0

    private data class Undo(val card: Card, val queue: List<Card>, val completed: Int)
    private val undoStack = ArrayDeque<Undo>()

    init {
        val active = cards.filterNot { it.suspended == 1 }
        val dayMs = Scheduler.DAY_MS
        val selected: List<Card> = when (params.mode) {
            StudyMode.DUE -> {
                val newCards = active
                    .filter { it.state == CardState.NEW }
                    .sortedBy { it.position }
                    .take(newPerDay.coerceAtLeast(0))
                val reviewCards = active
                    .filter { it.state != CardState.NEW && it.dueDate <= now }
                    .sortedBy { it.dueDate }
                    .take(maxReviews.coerceAtLeast(0))
                reviewCards + newCards
            }
            StudyMode.AHEAD -> {
                val horizon = now + params.daysAhead.toLong() * dayMs
                active
                    .filter { it.state != CardState.NEW && it.dueDate <= horizon }
                    .sortedBy { it.dueDate }
                    .take(params.count.coerceAtLeast(0))
            }
            StudyMode.LAPSES -> active
                .filter { it.state == CardState.RELEARNING || it.lapses > 0 }
                .sortedBy { it.dueDate }
                .take(params.count.coerceAtLeast(0))
            StudyMode.NEW_ONLY -> active
                .filter { it.state == CardState.NEW }
                .sortedBy { it.position }
                .take(params.count.coerceAtLeast(0))
            StudyMode.CRAM -> active
                .sortedBy { it.position }
                .take(params.count.coerceAtLeast(0))
        }
        queue.addAll(selected)
        totalCards = queue.size
    }

    val current: Card? get() = queue.firstOrNull()
    val remaining: Int get() = queue.size
    val done: Boolean get() = queue.isEmpty()

    val newCount: Int get() = queue.count { it.state == CardState.NEW }
    val learningCount: Int
        get() = queue.count { it.state == CardState.LEARNING || it.state == CardState.RELEARNING }
    val reviewCount: Int get() = queue.count { it.state == CardState.REVIEW }

    val progress: Float get() = if (totalCards == 0) 1f else completed.toFloat() / totalCards
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /**
     * Grades the current card and advances the queue. Returns the updated card to
     * persist, or null if the queue was empty.
     */
    fun grade(grade: Grade, now: Long): Card? {
        val card = queue.firstOrNull() ?: return null
        undoStack.addLast(Undo(card, queue.toList(), completed))
        queue.removeFirst()

        val updated = Scheduler.schedule(card, grade, now, desiredRetention, weights)
        if (updated.state == CardState.LEARNING || updated.state == CardState.RELEARNING) {
            queue.add(minOf(RE_QUEUE_GAP, queue.size), updated)
        } else {
            completed++
        }
        return updated
    }

    /**
     * Removes the current card from the queue without scheduling it (used when a
     * card is suspended mid-session). Returns the removed card, or null if empty.
     */
    fun removeCurrent(): Card? {
        val card = queue.firstOrNull() ?: return null
        undoStack.addLast(Undo(card, queue.toList(), completed))
        queue.removeFirst()
        completed++
        return card
    }

    /**
     * Removes every queued copy of the card with [id] without scheduling (used when
     * a card is auto-suspended as a leech mid-session).
     */
    fun removeFromQueue(id: Long) {
        queue.removeAll { it.id == id }
    }

    /**
     * Reverts the last [grade]. Returns the card whose pre-grade state must be
     * restored in the database, or null if there is nothing to undo.
     */
    fun undo(): Card? {
        val entry = undoStack.removeLastOrNull() ?: return null
        queue.clear()
        queue.addAll(entry.queue)
        completed = entry.completed
        return entry.card
    }

    /** Predicted interval label for each grade on the current card. */
    fun previewLabels(now: Long): Map<Grade, String> {
        val card = current ?: return emptyMap()
        return Grade.entries.associateWith {
            Scheduler.previewLabel(card, it, now, desiredRetention, weights)
        }
    }

    /** How many cards ahead a failed card is re-queued in a session. */
    companion object {
        private const val RE_QUEUE_GAP = 3
    }
}
