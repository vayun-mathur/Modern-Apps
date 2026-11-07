package com.vayunmathur.flashcards.util

import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardState
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The four Anki-style review buttons, mapped onto the FSRS 1..4 grade scale.
 *
 * Ids live at file level (not in the companion): enum entries are initialised
 * before the companion object, so entries cannot read companion constants.
 */
private const val GRADE_AGAIN = 1
private const val GRADE_HARD = 2
private const val GRADE_GOOD = 3
private const val GRADE_EASY = 4

enum class Grade(val value: Int) {
    AGAIN(GRADE_AGAIN),
    HARD(GRADE_HARD),
    GOOD(GRADE_GOOD),
    EASY(GRADE_EASY),
    ;
}

/**
 * Pure FSRS (v5, 19-weight) spaced-repetition scheduler. Kept free of Android
 * dependencies so it can be unit-tested directly. [schedule] returns a copy of
 * [card] with an updated memory state and due date; it never mutates its input.
 *
 * Cards that fail (`AGAIN`) or are still in a learning/relearning step get a
 * short sub-day interval so they resurface within the same session; graduated
 * cards get the long-term FSRS interval derived from their stability and the
 * deck's desired retention.
 */
object Scheduler {
    const val DAY_MS: Long = 24L * 60 * 60 * 1000
    private const val MINUTE_MS: Long = 60L * 1000

    /** FSRS-5 default parameters (19 weights). */
    val DEFAULT_W = doubleArrayOf(
        0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046,
        1.54575, 0.1192, 1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315,
        2.9898, 0.51655, 0.6621,
    )

    const val DECAY = -0.5
    /** `FACTOR = 0.9^(1/DECAY) - 1` so that `interval == stability` at 90% retention. */
    val FACTOR = 0.9.pow(1.0 / DECAY) - 1.0

    private const val MIN_STABILITY = 0.01
    private const val MAX_STABILITY = 36500.0
    private const val MIN_DIFFICULTY = 1.0
    private const val MAX_DIFFICULTY = 10.0

    // FSRS weight positions: difficulty anchor (4), difficulty slope (5),
    // difficulty damping (6), recall-stability floor (7), recall shape (8..10).
    private const val W_DIFF_ANCHOR = 4
    private const val W_DIFF_SLOPE = 5
    private const val W_DIFF_DAMPING = 6
    private const val W_RECALL_FLOOR = 7
    private const val W_RECALL_SHAPE = 8
    private const val W_RECALL_DECAY = 9
    private const val W_RECALL_GAIN = 10
    private const val W_FORGET_BASE = 11
    private const val W_FORGET_DIFF = 12
    private const val W_FORGET_STAB = 13
    private const val W_FORGET_RETR = 14
    private const val W_HARD_PENALTY = 15
    private const val W_EASY_BONUS = 16

    private const val AGAIN_STEP_MS = MINUTE_MS
    private const val HARD_STEP_MS = 6 * MINUTE_MS

    /** Probability of recall after [elapsedDays] given [stability] (in days). */
    fun retrievability(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 0.0
        return (1.0 + FACTOR * elapsedDays / stability).pow(DECAY)
    }

    /** Days until recall probability decays to [desiredRetention]. */
    fun nextIntervalDays(stability: Double, desiredRetention: Double): Double {
        val r = desiredRetention.coerceIn(0.01, 0.999)
        return (stability / FACTOR) * (r.pow(1.0 / DECAY) - 1.0)
    }

    private fun clampStability(s: Double) = s.coerceIn(MIN_STABILITY, MAX_STABILITY)
    private fun clampDifficulty(d: Double) = d.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)

    private fun initialStability(w: DoubleArray, grade: Grade) =
        clampStability(w[grade.value - 1])

    private fun initialDifficulty(w: DoubleArray, grade: Grade) =
        clampDifficulty(w[W_DIFF_ANCHOR] - exp(w[W_DIFF_SLOPE] * (grade.value - 1)) + 1.0)

    private fun nextDifficulty(w: DoubleArray, difficulty: Double, grade: Grade): Double {
        val deltaD = -w[W_DIFF_DAMPING] * (grade.value - 3)
        val dampened = difficulty + deltaD * (10.0 - difficulty) / 9.0
        val d0Easy = w[W_DIFF_ANCHOR] - exp(w[W_DIFF_SLOPE] * (Grade.EASY.value - 1)) + 1.0
        return clampDifficulty(w[W_RECALL_FLOOR] * d0Easy + (1.0 - w[W_RECALL_FLOOR]) * dampened)
    }

    private fun stabilityAfterRecall(
        w: DoubleArray,
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        grade: Grade,
    ): Double {
        val hardPenalty = if (grade == Grade.HARD) w[W_HARD_PENALTY] else 1.0
        val easyBonus = if (grade == Grade.EASY) w[W_EASY_BONUS] else 1.0
        val increment = exp(w[W_RECALL_SHAPE]) *
            (11.0 - difficulty) *
            stability.pow(-w[W_RECALL_DECAY]) *
            (exp(w[W_RECALL_GAIN] * (1.0 - retrievability)) - 1.0) *
            hardPenalty *
            easyBonus
        return clampStability(stability * (increment + 1.0))
    }

    private fun stabilityAfterForget(
        w: DoubleArray,
        difficulty: Double,
        stability: Double,
        retrievability: Double,
    ): Double {
        val forgotten = w[W_FORGET_BASE] *
            difficulty.pow(-w[W_FORGET_DIFF]) *
            ((stability + 1.0).pow(w[W_FORGET_STAB]) - 1.0) *
            exp(w[W_FORGET_RETR] * (1.0 - retrievability))
        // Post-lapse stability can never exceed the prior value.
        return clampStability(minOf(forgotten, stability))
    }

    /**
     * Returns [card] updated for [grade] applied at [now] (epoch millis), using
     * [desiredRetention] (0..1) to size the next interval.
     */
    fun schedule(
        card: Card,
        grade: Grade,
        now: Long,
        desiredRetention: Double = 0.9,
        weights: DoubleArray = DEFAULT_W,
    ): Card {
        val w = if (weights.size == DEFAULT_W.size) weights else DEFAULT_W
        val firstReview = card.reps == 0 || card.state == CardState.NEW
        val elapsedDays =
            if (card.lastReview > 0) (now - card.lastReview).toDouble() / DAY_MS else 0.0

        val (newDifficulty, newStability) = updateMemory(w, card, grade, firstReview, elapsedDays)
        val (newState, due) = resolveDue(card, grade, now, desiredRetention, newStability, firstReview)

        val wasReview = card.state == CardState.REVIEW
        val lapses = card.lapses + if (grade == Grade.AGAIN && wasReview) 1 else 0

        return card.copy(
            stability = newStability,
            difficulty = newDifficulty,
            state = newState,
            lastReview = now,
            lapses = lapses,
            reps = card.reps + 1,
            dueDate = due,
        )
    }

    private fun updateMemory(
        w: DoubleArray,
        card: Card,
        grade: Grade,
        firstReview: Boolean,
        elapsedDays: Double,
    ): Pair<Double, Double> {
        if (firstReview) {
            return initialDifficulty(w, grade) to initialStability(w, grade)
        }
        val r = retrievability(elapsedDays, card.stability)
        val difficulty = nextDifficulty(w, clampDifficulty(card.difficulty), grade)
        val stability = if (grade == Grade.AGAIN) {
            stabilityAfterForget(w, difficulty, card.stability, r)
        } else {
            stabilityAfterRecall(w, difficulty, card.stability, r, grade)
        }
        return difficulty to stability
    }

    private fun resolveDue(
        card: Card,
        grade: Grade,
        now: Long,
        desiredRetention: Double,
        newStability: Double,
        firstReview: Boolean,
    ): Pair<Int, Long> {
        val wasReview = card.state == CardState.REVIEW
        val inLearning = card.state == CardState.LEARNING || card.state == CardState.RELEARNING
        val relearn = wasReview || card.state == CardState.RELEARNING
        if (grade == Grade.AGAIN) {
            val state = if (relearn) CardState.RELEARNING else CardState.LEARNING
            return state to (now + AGAIN_STEP_MS)
        }
        if (grade == Grade.HARD && (firstReview || inLearning)) {
            val state = if (relearn) CardState.RELEARNING else CardState.LEARNING
            return state to (now + HARD_STEP_MS)
        }
        val days = nextIntervalDays(newStability, desiredRetention)
            .roundToLong()
            .coerceAtLeast(1)
        return CardState.REVIEW to (now + days * DAY_MS)
    }

    /** A short human label for the interval [grade] would assign to [card]. */
    fun previewLabel(
        card: Card,
        grade: Grade,
        now: Long,
        desiredRetention: Double = 0.9,
        weights: DoubleArray = DEFAULT_W,
    ): String = formatDuration(schedule(card, grade, now, desiredRetention, weights).dueDate - now)

    /**
     * Parses a JSON array string of 19 doubles into FSRS weights. Returns
     * [DEFAULT_W] on a blank input, a parse failure, or a wrong length. Kept
     * dependency-free (no `org.json`) so it is usable from pure JVM tests.
     */
    fun parseWeights(json: String): DoubleArray {
        if (json.isBlank()) return DEFAULT_W
        val tokens = json.trim().removePrefix("[").removeSuffix("]").split(",")
        // Every token must parse to a finite double: mapNotNull would silently
        // drop a corrupt token and shift the remaining weights into the wrong
        // FSRS positions, and NaN/Infinity would poison every interval.
        if (tokens.size != DEFAULT_W.size) return DEFAULT_W
        val nums = tokens.map { it.trim().toDoubleOrNull() }
        if (nums.any { it == null || !it.isFinite() }) return DEFAULT_W
        return DoubleArray(nums.size) { nums[it]!! }
    }

    /** Serializes FSRS [weights] to a JSON array string for storage. */
    fun weightsToJson(weights: DoubleArray): String =
        weights.joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun formatDuration(ms: Long): String {
        val minutes = ms / MINUTE_MS
        if (minutes < MINUTES_PER_HOUR) return "${minutes.coerceAtLeast(1)}m"
        val hours = ms / (MINUTES_PER_HOUR * MINUTE_MS)
        if (hours < HOURS_PER_DAY) return "${hours}h"
        val days = ms / DAY_MS
        return when {
            days < DAYS_PER_MONTH -> "${days}d"
            days < DAYS_PER_YEAR -> "${days / DAYS_PER_MONTH}mo"
            else -> "${days / DAYS_PER_YEAR}y"
        }
    }

    private const val MINUTES_PER_HOUR = 60L
    private const val HOURS_PER_DAY = 24L
    private const val DAYS_PER_MONTH = 30L
    private const val DAYS_PER_YEAR = 365L
}
