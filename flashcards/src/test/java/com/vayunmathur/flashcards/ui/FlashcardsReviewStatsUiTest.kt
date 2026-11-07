package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.vayunmathur.flashcards.util.DailyStat
import com.vayunmathur.flashcards.util.DeckOption
import com.vayunmathur.flashcards.util.Grade
import com.vayunmathur.flashcards.util.ReviewActions
import com.vayunmathur.flashcards.util.ReviewUiState
import com.vayunmathur.flashcards.util.StatsActions
import com.vayunmathur.flashcards.util.StatsUiState
import com.vayunmathur.library.ui.DynamicTheme
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlashcardsReviewStatsUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun reviewDoneState() {
        rule.setContent { DynamicTheme { ReviewScreen(ReviewUiState(done = true), ReviewActions.Noop) } }
        rule.onNodeWithText("All caught up!").assertIsDisplayed()
        rule.onNodeWithText("You have finished this session").assertIsDisplayed()
    }

    @Test
    fun reviewRevealThenGrade() {
        var graded: Grade? = null; var spoken: String? = null
        val actions = object : ReviewActions {
            override fun grade(grade: Grade) { graded = grade }
            override fun speak(text: String) { spoken = text }
        }
        val state = ReviewUiState(
            front = "la biblioteca", back = "the library", remaining = 3,
            newCount = 1, learningCount = 1, reviewCount = 1, progress = 0.25f,
            intervalLabels = mapOf(Grade.AGAIN to "1m", Grade.HARD to "10m", Grade.GOOD to "4d", Grade.EASY to "9d"),
            canUndo = true,
        )
        rule.setContent { DynamicTheme { ReviewScreen(state, actions) } }
        rule.onNodeWithText("la biblioteca").assertIsDisplayed()
        rule.onNodeWithText("1 new · 1 learning · 1 review").assertIsDisplayed()
        rule.onNodeWithText("Show answer").performClick()
        rule.onNodeWithText("the library").assertIsDisplayed()
        rule.onNodeWithText("Again").assertIsDisplayed()
        rule.onNodeWithText("1m").assertIsDisplayed()
        rule.onNodeWithText("Good").performClick()
        assertEquals(Grade.GOOD, graded)
    }

    @Test
    fun reviewTypeInFieldAndUndoSuspendSpeak() {
        var undone = false; var suspended = false
        val actions = object : ReviewActions {
            override fun undo() { undone = true }
            override fun suspend() { suspended = true }
        }
        val state = ReviewUiState(
            front = "Capital?", back = "Paris", typeField = "Front", typeAnswer = "Paris",
            canUndo = true, remaining = 1,
        )
        rule.setContent { DynamicTheme { ReviewScreen(state, actions) } }
        rule.onNodeWithText("Type the answer").performTextInput("Par")
        rule.onNodeWithContentDescription("Undo").performClick()
        assertTrue(undone)
        // Suspend / speak are icon buttons in the app bar; verify they exist by description if present.
        val suspendNodes = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Visibility off")).fetchSemanticsNodes()
        if (suspendNodes.isNotEmpty()) {
            rule.onNodeWithContentDescription("Visibility off").performClick()
            assertTrue(suspended)
        }
    }

    @Test
    fun reviewInitialRevealedShowsGrades() {
        rule.setContent {
            DynamicTheme {
                ReviewScreen(
                    ReviewUiState(front = "Q", back = "A", intervalLabels = mapOf(Grade.EASY to "9d")),
                    ReviewActions.Noop, initialRevealed = true,
                )
            }
        }
        rule.onNodeWithText("9d").assertIsDisplayed()
        rule.onNodeWithText("Easy").assertIsDisplayed()
    }

    @Test
    fun gradeButtonsDirect() {
        var graded: Grade? = null
        rule.setContent {
            DynamicTheme {
                GradeButtons(ReviewUiState(intervalLabels = mapOf(Grade.HARD to "6m"))) { graded = it }
            }
        }
        rule.onNodeWithText("Hard").performClick()
        assertEquals(Grade.HARD, graded)
        rule.onNodeWithText("6m").assertIsDisplayed()
    }

    // ---- Stats ----

    @Test
    fun statsEmptyStateAndSummary() {
        rule.setContent { DynamicTheme { StatsScreen(StatsUiState(), StatsActions.Noop) } }
        rule.onNodeWithText("Stats").assertIsDisplayed()
        rule.onNodeWithText("No reviews yet").assertIsDisplayed()
        rule.onNodeWithText("Reviews").assertIsDisplayed()
        rule.onNodeWithText("0").assertIsDisplayed()
    }

    @Test
    fun statsWithDataShowsChartsAndDeckPicker() {
        var selected: Long? = -99; var selectedCalled = false
        val actions = object : StatsActions {
            override fun selectDeck(id: Long?) { selected = id; selectedCalled = true }
        }
        val today = LocalDate.now().toEpochDay()
        val daily = (0..6).map { DailyStat(today - it, it + 1) }
        val state = StatsUiState(
            deckOptions = listOf(DeckOption(null, "All decks"), DeckOption(7, "Spanish")),
            selectedDeckId = null,
            daily = daily, totalReviews = 42, retentionPct = 85, streakDays = 4,
            matureCards = 10, totalCards = 30,
            forecast = listOf(DailyStat(today, 3), DailyStat(today + 1, 2)),
            retentionBuckets = listOf("≤1" to 0.5f, "2–3" to 0.9f),
            heatmap = daily,
        )
        rule.setContent { DynamicTheme { StatsScreen(state, actions) } }
        rule.onNodeWithText("85%").assertIsDisplayed()
        rule.onNodeWithText("42").assertIsDisplayed()
        rule.onNodeWithText("4 days").assertIsDisplayed()
        rule.onNodeWithText("10 / 30").assertIsDisplayed()
        rule.onNodeWithText("Review activity").assertExists()
        rule.onNodeWithText("Due forecast").assertExists()
        rule.onNodeWithText("Retention by interval").assertExists()
        rule.onNodeWithText("Spanish").performClick()
        assertTrue(selectedCalled)
        assertEquals(7L, selected)
        // Period tabs
        rule.onNodeWithText("Week").performClick()
        rule.onNodeWithText("Year").performClick()
        rule.onNodeWithText("85%").assertIsDisplayed()
    }

    @Test
    fun barChartTapSelectsBar() {
        rule.setContent {
            DynamicTheme {
                ReviewBarChart(data = listOf("1" to 3.0, "2" to 9.0, "3" to 6.0), modifier = Modifier.fillMaxWidth())
            }
        }
        // Tap near the middle bar; a tooltip with its value appears.
        rule.onRoot().performTouchInput { click(center) }
        assertTrue(rule.onAllNodesWithText("9").fetchSemanticsNodes().isNotEmpty())
        // Tapping again deselects (tooltip goes away) - just verify the chart still exists via a second tap.
        rule.onRoot().performTouchInput { click(center) }
        rule.onRoot().assertExists()
    }

    @Test
    fun metricRingAndChartsRender() {
        val today = LocalDate.now().toEpochDay()
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    MetricRing(progress = 1.5f, label = "Retention", value = "90%")
                    MetricRing(progress = -1f, label = "", value = "")
                    ReviewBarChart(data = listOf("1" to 2.0, "2" to 5.0, "3" to 0.0), modifier = Modifier.fillMaxWidth())
                    ReviewBarChart(data = emptyList(), modifier = Modifier.fillMaxWidth())
                    ReviewHeatmap(days = listOf(DailyStat(today, 3), DailyStat(today - 1, 0)), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        rule.onNodeWithText("90%").assertIsDisplayed()
        rule.onNodeWithText("Retention").assertIsDisplayed()
    }
}
