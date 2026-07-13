package com.vayunmathur.education.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Prompt media for a question. [text] is always present; [audioRef] (narration,
 * required in practice for K-2) and [imageRef] are optional asset references.
 */
@Serializable
data class Prompt(
    val text: String,
    val audioRef: String? = null,
    val imageRef: String? = null,
)

/** A selectable option, optionally illustrated (tap-the-picture for K-2). */
@Serializable
data class Choice(
    val text: String,
    val imageRef: String? = null,
)

/**
 * The single question schema — a closed polymorphic hierarchy covering the
 * union of all question types across bands. Each question declares the
 * band(s) it suits, its skill, prompt media, answer spec, hints, and
 * explanation. Serialized with a `"type"` discriminator (see [SerialName]s).
 */
@Serializable
sealed interface Question {
    val id: String
    val skillId: String
    val prompt: Prompt
    val hints: List<String>
    val explanation: String?

    /** Which band(s) this question is appropriate for. Empty == all bands. */
    val bands: List<Band>

    /** 1 (easiest) .. 5 (hardest). */
    val difficulty: Int
}

@Serializable
@SerialName("multiple_choice")
data class MultipleChoiceQuestion(
    override val id: String,
    override val skillId: String,
    override val prompt: Prompt,
    val choices: List<Choice>,
    val correctIndex: Int,
    override val hints: List<String> = emptyList(),
    override val explanation: String? = null,
    override val bands: List<Band> = emptyList(),
    override val difficulty: Int = 1,
) : Question

@Serializable
@SerialName("multiple_select")
data class MultipleSelectQuestion(
    override val id: String,
    override val skillId: String,
    override val prompt: Prompt,
    val choices: List<Choice>,
    val correctIndices: List<Int>,
    override val hints: List<String> = emptyList(),
    override val explanation: String? = null,
    override val bands: List<Band> = emptyList(),
    override val difficulty: Int = 2,
) : Question

@Serializable
@SerialName("numeric")
data class NumericQuestion(
    override val id: String,
    override val skillId: String,
    override val prompt: Prompt,
    val answer: Double,
    /** Absolute tolerance for accepting an answer (0.0 == exact). */
    val tolerance: Double = 0.0,
    val unit: String? = null,
    override val hints: List<String> = emptyList(),
    override val explanation: String? = null,
    override val bands: List<Band> = emptyList(),
    override val difficulty: Int = 2,
) : Question

@Serializable
@SerialName("short_text")
data class ShortTextQuestion(
    override val id: String,
    override val skillId: String,
    override val prompt: Prompt,
    val acceptedAnswers: List<String>,
    val caseSensitive: Boolean = false,
    override val hints: List<String> = emptyList(),
    override val explanation: String? = null,
    override val bands: List<Band> = emptyList(),
    override val difficulty: Int = 2,
) : Question

/** Drag-to-order: [items] are stored in the CORRECT order. */
@Serializable
@SerialName("ordering")
data class OrderingQuestion(
    override val id: String,
    override val skillId: String,
    override val prompt: Prompt,
    val items: List<String>,
    override val hints: List<String> = emptyList(),
    override val explanation: String? = null,
    override val bands: List<Band> = emptyList(),
    override val difficulty: Int = 2,
) : Question

/**
 * Matching: each entry in [left] matches the entry at the same index in
 * [correctRightForLeft] of [right]. i.e. left[i] ↔ right[correctRightForLeft[i]].
 */
@Serializable
@SerialName("matching")
data class MatchingQuestion(
    override val id: String,
    override val skillId: String,
    override val prompt: Prompt,
    val left: List<String>,
    val right: List<String>,
    val correctRightForLeft: List<Int>,
    override val hints: List<String> = emptyList(),
    override val explanation: String? = null,
    override val bands: List<Band> = emptyList(),
    override val difficulty: Int = 3,
) : Question

/**
 * Trace-the-glyph (K-2). The child traces [glyph] (a letter/number/shape) with
 * a finger. No-penalty: completing the trace is always accepted.
 */
@Serializable
@SerialName("tracing")
data class TracingQuestion(
    override val id: String,
    override val skillId: String,
    override val prompt: Prompt,
    val glyph: String,
    override val hints: List<String> = emptyList(),
    override val explanation: String? = null,
    override val bands: List<Band> = emptyList(),
    override val difficulty: Int = 1,
) : Question

// --- Answers (UI-side, not serialized) -------------------------------------

/** A learner's answer to a [Question], produced by the quiz UI. */
sealed interface Answer

data class ChoiceAnswer(val index: Int) : Answer
data class MultiChoiceAnswer(val indices: Set<Int>) : Answer
data class NumericAnswer(val value: Double) : Answer
data class TextAnswer(val text: String) : Answer

/** [order] holds the original item indices in the sequence the learner placed them. */
data class OrderingAnswer(val order: List<Int>) : Answer

/** [rightForLeft][i] is the chosen right index for left item i. */
data class MatchingAnswer(val rightForLeft: List<Int>) : Answer

/** The child completed a tracing gesture (no-penalty). */
data object TraceAnswer : Answer

/** True if [answer] correctly satisfies this question. */
fun Question.isCorrect(answer: Answer?): Boolean = when (this) {
    is MultipleChoiceQuestion -> answer is ChoiceAnswer && answer.index == correctIndex
    is MultipleSelectQuestion -> answer is MultiChoiceAnswer && answer.indices == correctIndices.toSet()
    is NumericQuestion -> answer is NumericAnswer && abs(answer.value - this.answer) <= tolerance
    is ShortTextQuestion -> answer is TextAnswer && acceptedAnswers.any {
        it.trim().equals(answer.text.trim(), ignoreCase = !caseSensitive)
    }
    is OrderingQuestion -> answer is OrderingAnswer && answer.order == items.indices.toList()
    is MatchingQuestion -> answer is MatchingAnswer && answer.rightForLeft == correctRightForLeft
    is TracingQuestion -> answer is TraceAnswer
}
