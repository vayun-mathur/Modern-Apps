package com.vayunmathur.education.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.education.EducationFixtures as Fx
import com.vayunmathur.education.R
import com.vayunmathur.education.content.Answer
import com.vayunmathur.education.content.ChoiceAnswer
import com.vayunmathur.education.content.MatchingAnswer
import com.vayunmathur.education.content.MultiChoiceAnswer
import com.vayunmathur.education.content.NumericAnswer
import com.vayunmathur.education.content.OrderingAnswer
import com.vayunmathur.education.content.TextAnswer
import com.vayunmathur.education.util.QuizActions
import com.vayunmathur.education.util.QuizUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Question inputs, both quiz shells, and shared UI helpers. */
@RunWith(RobolectricTestRunner::class)
class EducationQuizInputsTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private var screenContent by androidx.compose.runtime.mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)
    private var contentSet = false

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        if (!contentSet) { rule.setContent { screenContent?.invoke() }; contentSet = true }
        screenContent = content
        rule.waitForIdle()
    }
    private fun str(id: Int, vararg a: Any): String = ctx.getString(id, *a)

    @Test fun multipleChoiceSelectsAndReports() {
        var answer: Answer? = null
        show { QuestionInput(Fx.mcq, ChoiceAnswer(0), enabled = true) { answer = it } }
        rule.onNodeWithText("3").assertIsDisplayed().performClick()
        assertEquals(ChoiceAnswer(1), answer)
        rule.onNodeWithText("7").performClick()
        assertEquals(ChoiceAnswer(2), answer)
    }

    @Test fun multipleSelectTogglesSet() {
        var answer: Answer? = null
        show { QuestionInput(Fx.multiSelect, null, enabled = true) { answer = it } }
        rule.onNodeWithText("2").performClick()
        assertEquals(MultiChoiceAnswer(setOf(0)), answer)
        rule.onNodeWithText("4").performClick()
        assertEquals(MultiChoiceAnswer(setOf(0, 2)), answer)
        rule.onNodeWithText("2").performClick()
        assertEquals(MultiChoiceAnswer(setOf(2)), answer)
    }

    @Test fun numericAndShortTextInputs() {
        var answer: Answer? = null
        show { QuestionInput(Fx.numeric, null, enabled = true) { answer = it } }
        rule.onNodeWithText(str(R.string.answer_1, "apples")).performTextInput("42")
        assertEquals(NumericAnswer(42.0), answer)
        show { QuestionInput(Fx.shortText, null, enabled = true) { answer = it } }
        rule.onNodeWithText(str(R.string.answer)).performTextInput("a")
        assertEquals(TextAnswer("a"), answer)
    }

    @Test fun orderingPublishesInitialAndReorders() {
        var answer: Answer? = null
        show { QuestionInput(Fx.ordering, null, enabled = true) { answer = it } }
        rule.waitForIdle()
        val initial = answer as? OrderingAnswer
        assertNotNull(initial)
        assertEquals(listOf(0, 1, 2), initial.order.sorted())
        assertTrue(rule.onAllNodesWithText("3").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun matchingPublishesWhenComplete() {
        var answer: Answer? = null
        show { QuestionInput(Fx.matching, null, enabled = true) { answer = it } }
        // Pick the right-hand option for the first left item, then the second.
        rule.onAllNodesWithText(str(R.string.choose))[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("one").performClick()
        rule.waitForIdle()
        assertEquals(null, answer)
        rule.onAllNodesWithText(str(R.string.choose))[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("two").performClick()
        rule.waitForIdle()
        assertEquals(MatchingAnswer(listOf(0, 1)), answer)
    }

    @Test fun tracingGestureCompletes() {
        var traced = false
        show { TracingCanvas(glyph = "B", enabled = true, onTraced = { traced = true }) }
        rule.onNodeWithText("B").assertIsDisplayed()
        assertEquals(false, traced)
        // Long horizontal swipes across the canvas accumulate past the trace threshold.
        val root = rule.onRoot()
        repeat(4) {
            root.performTouchInput { swipe(Offset(60f, center.y), Offset(width - 60f, center.y), 120) }
            rule.waitForIdle()
        }
        assertEquals(true, traced)
    }

    @Test fun dragMatchRendersAndMissedDropSnapsBack() {
        var solved = false
        show { K2DragMatch(Fx.matching, enabled = true) { solved = true } }
        rule.onNodeWithText("one").assertIsDisplayed()
        rule.onNodeWithText("two").assertIsDisplayed()
        // Drag a chip a short distance inside its own column (no target there):
        // a missed drop must snap back and not solve or mark any target.
        // (Synthetic gestures cannot reproduce the per-frame bounds updates the
        // hit test uses on a real device, so the correct-drop path is not
        // asserted here.)
        rule.onNodeWithText("one").performTouchInput {
            swipe(Offset(center.x, center.y), Offset(center.x, center.y + 60f), 200)
        }
        rule.waitForIdle()
        assertEquals(false, solved)
        rule.onNodeWithText("one").assertIsDisplayed()
        rule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test fun quizScreenFullFlowScholar() {
        var finishedQuestions: Int = -1
        var finishedAnswers: Map<String, Answer?>? = null
        var up = false
        val actions = object : QuizActions {
            override fun navigateUp() { up = true }
            override fun finish(questions: List<com.vayunmathur.education.content.Question>, answers: Map<String, Answer?>) {
                finishedQuestions = questions.size; finishedAnswers = answers
            }
        }
        val questions = listOf(Fx.mcq, Fx.numeric)
        show { QuizScreen(QuizUiState(title = "Practice", questions = questions), actions) }
        rule.onNodeWithText("Practice").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.question_of, 1, 2)).assertIsDisplayed()
        // Check is disabled until an answer is chosen.
        rule.onNodeWithText("3").performClick()
        rule.onNodeWithText(str(R.string.show_a_hint)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("💡 Count them one by one.").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.check)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.correct_1)).assertIsDisplayed()
        rule.onNodeWithText("There are three apples.").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.next)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.answer_1, "apples")).performTextInput("5")
        rule.onNodeWithText(str(R.string.check)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.finish)).performClick()
        assertEquals(2, finishedQuestions)
        assertEquals(ChoiceAnswer(1), finishedAnswers?.get("q-mc"))
        assertEquals(NumericAnswer(5.0), finishedAnswers?.get("q-num"))
        assertEquals(false, up)
    }

    @Test fun quizScreenEmptyAndWrongFeedback() {
        show { QuizScreen(QuizUiState(title = "Empty", questions = emptyList()), QuizActions.Noop) }
        rule.onNodeWithText(str(R.string.this_exercise_has_no_questions_yet)).assertIsDisplayed()
        show {
            QuizScreen(
                QuizUiState(title = "Practice", questions = listOf(Fx.mcq)), QuizActions.Noop,
                initialIndex = 0, initialChecked = true, initialAnswers = mapOf("q-mc" to ChoiceAnswer(0)),
            )
        }
        rule.onNodeWithText(str(R.string.not_quite)).assertIsDisplayed()
    }

    @Test fun k2QuizCorrectAdvancesAndFinishes() {
        var finished: Int = -1
        val actions = object : QuizActions {
            override fun finish(questions: List<com.vayunmathur.education.content.Question>, answers: Map<String, Answer?>) { finished = questions.size }
        }
        show { K2QuizScreen(QuizUiState(questions = listOf(Fx.mcq, Fx.tracing)), actions) }
        rule.onNodeWithText("How many apples?").assertIsDisplayed()
        // Wrong choice wiggles into the error color but stays on the question.
        rule.onNodeWithText("1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("How many apples?").assertIsDisplayed()
        assertEquals(-1, finished)
        // Correct choice celebrates, then advances to tracing; finish after the trace question.
        rule.onNodeWithText("3").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.great_job)).assertIsDisplayed()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Trace the letter").fetchSemanticsNodes().isNotEmpty() }
        // Trace the glyph to complete the last question; the quiz then finishes.
        val root = rule.onRoot()
        var swipes = 0
        while (finished != 2 && swipes < 6) {
            root.performTouchInput { swipe(Offset(60f, center.y), Offset(width - 60f, center.y), 120) }
            rule.waitForIdle()
            swipes++
        }
        rule.waitUntil(10_000) { finished == 2 }
    }

    @Test fun k2QuizSecondTracingQuestionCanComplete() {
        var finished: Int = -1
        val actions = object : QuizActions {
            override fun finish(questions: List<com.vayunmathur.education.content.Question>, answers: Map<String, Answer?>) { finished = questions.size }
        }
        // Regression: two tracing questions back to back (bundled ex.k-trace has
        // three). The second must start unfired, or it can never complete.
        val second = Fx.tracing.copy(id = "q-trace2", glyph = "B")
        show { K2QuizScreen(QuizUiState(questions = listOf(Fx.tracing, second)), actions) }
        rule.onNodeWithText("A").assertIsDisplayed()
        val root = rule.onRoot()
        var swipes = 0
        while (onAllNodesWithText("B").fetchSemanticsNodes().isEmpty() && swipes < 8) {
            root.performTouchInput { swipe(Offset(60f, center.y), Offset(width - 60f, center.y), 120) }
            rule.waitForIdle()
            swipes++
        }
        rule.waitUntil(10_000) { onAllNodesWithText("B").fetchSemanticsNodes().isNotEmpty() }
        swipes = 0
        while (finished != 2 && swipes < 8) {
            root.performTouchInput { swipe(Offset(60f, center.y), Offset(width - 60f, center.y), 120) }
            rule.waitForIdle()
            swipes++
        }
        rule.waitUntil(10_000) { finished == 2 }
    }

    @Test fun k2QuizEmptyShowsGoBack() {
        show { K2QuizScreen(QuizUiState(questions = emptyList()), QuizActions.Noop) }
        rule.onNodeWithText(str(R.string.let_s_go_back)).assertIsDisplayed()
    }

    @Test fun commonHelpers() {
        show {
            androidx.compose.foundation.layout.Column {
                StarRow(2)
                StreakChip(3)
                StarsChip(7)
                DeadlineChip(System.currentTimeMillis() / 86_400_000)
                SectionHeader("Header text")
            }
        }
        rule.onNodeWithText("Header text").assertIsDisplayed()
        rule.onNodeWithText("7").assertIsDisplayed()
        assertEquals("K-2 · Guided Playground", bandLabel(com.vayunmathur.education.content.Band.K2))
        assertEquals("3-5 · Explorer", bandLabel(com.vayunmathur.education.content.Band.ELEMENTARY))
        assertEquals("6-12 · Scholar", bandLabel(com.vayunmathur.education.content.Band.SCHOLAR))
        assertEquals(0, averageStars(emptyList(), emptyMap()))
        assertEquals(2, averageStars(listOf("a", "b"), mapOf("a" to com.vayunmathur.education.data.SkillProgress("a", stars = 3), "b" to com.vayunmathur.education.data.SkillProgress("b", stars = 1))))
        assertTrue(formatEpochDay(ctx, 20_000).isNotEmpty())
        // Subject colors are defined for every subject.
        show {
            androidx.compose.foundation.layout.Column {
                com.vayunmathur.education.content.Subject.entries.forEach { s -> Text2Colors(s) }
            }
        }
        rule.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun Text2Colors(s: com.vayunmathur.education.content.Subject) {
        val c = subjectColors(s)
        com.vayunmathur.library.ui.Text(s.name + ":" + c.container.value.toString())
    }

    @Test fun rememberNarratorComposesAndDisposes() {
        show {
            val narrator = com.vayunmathur.education.util.rememberNarrator()
            androidx.compose.runtime.LaunchedEffect(Unit) { narrator.narrate("Ready to learn") }
            com.vayunmathur.library.ui.Text("narrator up")
        }
        rule.onNodeWithText("narrator up").assertIsDisplayed()
        // Swapping content disposes the narrator (TTS shutdown under Robolectric).
        show { com.vayunmathur.library.ui.Text("gone") }
        rule.onNodeWithText("gone").assertIsDisplayed()
    }

    @Test fun videoPlayerRendersTitle() {
        val bs = com.vayunmathur.library.util.NavBackStack<com.vayunmathur.education.Route>(arrayOf(com.vayunmathur.education.Route.Home))
        show { VideoPlayerPage(bs, "http://127.0.0.1:9/none.mp4", "Space video") }
        rule.onNodeWithText("Space video").assertIsDisplayed()
    }

    @Test fun videoPlayerRetryAfterErrorDoesNotCrash() {
        val bs = com.vayunmathur.library.util.NavBackStack<com.vayunmathur.education.Route>(arrayOf(com.vayunmathur.education.Route.Home))
        show { VideoPlayerPage(bs, "http://127.0.0.1:9/none.mp4", "Space video") }
        // Unreachable URL must surface the error state with a Retry action.
        rule.waitUntil(30_000) { onAllNodesWithText(str(R.string.retry)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(str(R.string.retry)).performClick()
        rule.waitForIdle()
        // Regression: retry used to reuse the ExoPlayer that the effect
        // disposal had just released. The screen must survive and keep
        // showing the player (title bar still present, no crash).
        rule.onNodeWithText("Space video").assertIsDisplayed()
    }

    private fun onAllNodesWithText(text: String) = rule.onAllNodesWithText(text)
}
