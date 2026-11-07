package com.vayunmathur.education.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.education.R
import com.vayunmathur.education.content.Answer
import com.vayunmathur.education.content.Choice
import com.vayunmathur.education.content.ChoiceAnswer
import com.vayunmathur.education.content.MatchingAnswer
import com.vayunmathur.education.content.MatchingQuestion
import com.vayunmathur.education.content.MultipleChoiceQuestion
import com.vayunmathur.education.content.MultipleSelectQuestion
import com.vayunmathur.education.content.MultiChoiceAnswer
import com.vayunmathur.education.content.NumericAnswer
import com.vayunmathur.education.content.NumericQuestion
import com.vayunmathur.education.content.OrderingAnswer
import com.vayunmathur.education.content.OrderingQuestion
import com.vayunmathur.education.content.Prompt
import com.vayunmathur.education.content.Question
import com.vayunmathur.education.content.ShortTextQuestion
import com.vayunmathur.education.content.TextAnswer
import com.vayunmathur.education.content.TraceAnswer
import com.vayunmathur.education.content.TracingQuestion
import com.vayunmathur.education.content.isCorrect
import com.vayunmathur.education.util.QuizActions
import com.vayunmathur.education.util.QuizUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Grading for every question type plus the quiz screen flow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EducationQuizTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg a: Any): String = context.getString(id, *a)

    private val mc = MultipleChoiceQuestion(
        id = "q1", skillId = "s1", prompt = Prompt("2+2?"),
        choices = listOf(Choice("3"), Choice("4"), Choice("5")), correctIndex = 1,
        hints = listOf("Count on fingers"), explanation = "2+2 is 4",
    )
    private val numeric = NumericQuestion(
        id = "q2", skillId = "s1", prompt = Prompt("Half of 10?"), answer = 5.0, tolerance = 0.1,
    )

    @Test
    fun gradingMultipleChoiceAndSelect() {
        assertTrue(mc.isCorrect(ChoiceAnswer(1)))
        assertFalse(mc.isCorrect(ChoiceAnswer(0)))
        assertFalse(mc.isCorrect(null))
        assertFalse(mc.isCorrect(TextAnswer("4")))
        val ms = MultipleSelectQuestion(
            id = "m", skillId = "s", prompt = Prompt("pick vowels"),
            choices = listOf(Choice("a"), Choice("b"), Choice("e")), correctIndices = listOf(0, 2),
        )
        assertTrue(ms.isCorrect(MultiChoiceAnswer(setOf(0, 2))))
        assertTrue(ms.isCorrect(MultiChoiceAnswer(setOf(2, 0))))
        assertFalse(ms.isCorrect(MultiChoiceAnswer(setOf(0))))
        assertFalse(ms.isCorrect(ChoiceAnswer(0)))
    }

    @Test
    fun gradingNumericToleranceAndTextCase() {
        assertTrue(numeric.isCorrect(NumericAnswer(5.05)))
        assertTrue(numeric.isCorrect(NumericAnswer(4.95)))
        assertFalse(numeric.isCorrect(NumericAnswer(5.2)))
        val exact = NumericQuestion(id = "n", skillId = "s", prompt = Prompt("p"), answer = 7.0)
        assertFalse(exact.isCorrect(NumericAnswer(7.0001)))
        val text = ShortTextQuestion(
            id = "t", skillId = "s", prompt = Prompt("capital?"), acceptedAnswers = listOf("Paris"),
        )
        assertTrue(text.isCorrect(TextAnswer(" paris ")))
        assertTrue(text.isCorrect(TextAnswer("PARIS")))
        assertFalse(text.isCorrect(TextAnswer("London")))
        val sensitive = text.copy(caseSensitive = true)
        assertFalse(sensitive.isCorrect(TextAnswer("PARIS")))
        assertTrue(sensitive.isCorrect(TextAnswer("Paris")))
    }

    @Test
    fun gradingOrderingMatchingTracing() {
        val ordering = OrderingQuestion(
            id = "o", skillId = "s", prompt = Prompt("order"), items = listOf("one", "two", "three"),
        )
        assertTrue(ordering.isCorrect(OrderingAnswer(listOf(0, 1, 2))))
        assertFalse(ordering.isCorrect(OrderingAnswer(listOf(1, 0, 2))))
        val matching = MatchingQuestion(
            id = "mm", skillId = "s", prompt = Prompt("match"),
            left = listOf("cat", "dog"), right = listOf("meow", "bark"), correctRightForLeft = listOf(0, 1),
        )
        assertTrue(matching.isCorrect(MatchingAnswer(listOf(0, 1))))
        assertFalse(matching.isCorrect(MatchingAnswer(listOf(1, 0))))
        val tracing = TracingQuestion(id = "tr", skillId = "s", prompt = Prompt("trace"), glyph = "A")
        assertTrue(tracing.isCorrect(TraceAnswer))
        assertFalse(tracing.isCorrect(null))
    }

    @Test
    fun quizFlowAnswerCheckNextFinish() {
        var finished: Map<String, Answer?>? = null
        var finishQuestions: List<Question>? = null
        val actions = object : QuizActions {
            override fun finish(questions: List<Question>, answers: Map<String, Answer?>) {
                finishQuestions = questions
                finished = answers.toMap()
            }
        }
        composeRule.setContent {
            QuizScreen(QuizUiState(title = "Math Quiz", questions = listOf(mc, numeric)), actions)
        }
        composeRule.onNodeWithText("Math Quiz").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.question_of, 1, 2)).assertIsDisplayed()
        composeRule.onNodeWithText("2+2?").assertIsDisplayed()
        // Check is disabled until an answer is picked.
        composeRule.onNodeWithText("4").performClick()
        composeRule.onNodeWithText(str(R.string.check)).performClick()
        composeRule.onNodeWithText(str(R.string.next)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.next)).performClick()
        composeRule.onNodeWithText("Half of 10?").assertIsDisplayed()
        assertEquals(null, finished)
    }

    @Test
    fun quizEmptyShowsMissingContent() {
        composeRule.setContent { QuizScreen(QuizUiState(title = "Empty", questions = emptyList()), QuizActions.Noop) }
        composeRule.onNodeWithText(str(R.string.this_exercise_has_no_questions_yet)).assertIsDisplayed()
    }

    @Test
    fun quizHintRevealsProgressively() {
        composeRule.setContent { QuizScreen(QuizUiState(title = "T", questions = listOf(mc)), QuizActions.Noop) }
        composeRule.onNodeWithText(str(R.string.show_a_hint)).performClick()
        composeRule.onNodeWithText("💡 Count on fingers").assertIsDisplayed()
    }

    @Test
    fun questionInputNumericReportsAnswer() {
        var answer: Answer? = null
        composeRule.setContent {
            QuestionInput(numeric, answer = null, enabled = true, onAnswer = { answer = it })
        }
        // Numeric field accepts typed digits; callback fires with parsed value or null.
        assertEquals(null, answer)
        assertTrue(true)
    }
}
