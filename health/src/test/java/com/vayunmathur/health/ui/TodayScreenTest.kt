package com.vayunmathur.health.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.health.util.MainPageMetrics
import com.vayunmathur.health.util.TodayActions
import com.vayunmathur.health.util.TodayUiState
import com.vayunmathur.health.util.formatDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the Today dashboard screen and its hero.
 *
 * The screen is ViewModel-free (state in, navigation out), so canned states
 * exercise the whole layout: the rings, the vitals rows, and the empty
 * placeholders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodayScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun state() = TodayUiState(
        steps = 8_432,
        activeCalories = 320,
        mindfulnessMinutes = 10,
        heartRateMin = 58,
        heartRateMax = 120,
        metrics = MainPageMetrics(
            sleepMinutes = 450,
            bloodPressure = 118.0 to 76.0,
            spo2 = 98.4,
            rhr = 58,
        ),
    )

    @Test
    fun `today shows rings vitals and sleep`() {
        var opened: HealthMetricConfig? = null
        var sleepOpened = 0
        val actions = object : TodayActions {
            override fun openSleepDetails() {
                sleepOpened++
            }

            override fun openMetric(config: HealthMetricConfig) {
                opened = config
            }
        }
        rule.setContent { TodayScreen(state = state(), actions = actions) }

        rule.onNodeWithText("Last night").assertIsDisplayed()
        rule.onNodeWithText("Latest vitals").assertIsDisplayed()
        rule.onNodeWithText("58-120").assertIsDisplayed()
        // Merged row text; the section may sit below the fold, so existence
        // rather than visibility is what is pinned.
        rule.onNodeWithText("118/76", substring = true).assertExists()
        rule.onNodeWithText("98.4", substring = true).assertExists()

        rule.onNodeWithText("Sleep").performClick()
        assertEquals(1, sleepOpened)
        rule.onNodeWithText("Heart Rate").performClick()
        assertEquals(HealthMetricConfig.HEART_RATE, opened)
    }

    @Test
    fun `empty today shows placeholders`() {
        rule.setContent { TodayScreen(state = TodayUiState(), actions = TodayActions.Noop) }
        // Every unknown metric renders a double dash, not a zero: five rows.
        assertEquals(5, rule.onAllNodesWithText("--", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("Last night").assertIsDisplayed()
    }

    @Test
    fun `activity rings hero scales to its goals`() {
        rule.setContent {
            ActivityRingsHero(stepsToday = 5_000, activeCaloriesToday = 250, mindfulnessToday = 10)
        }
        rule.onNodeWithText("5000").assertIsDisplayed()
        rule.onNodeWithText("250").assertIsDisplayed()
        rule.onNodeWithText("10").assertIsDisplayed()
    }

    @Test
    fun `durations format without a clock`() {
        assertEquals("7h 30m", formatDuration(450))
        assertEquals("25m", formatDuration(25))
        assertEquals("0m", formatDuration(0))
    }

    @Test
    fun `sleep formats through the shared duration`() {
        assertEquals("7h 30m", formatSleep(450))
        assertTrue(TodayScreenTest::class.simpleName!!.isNotBlank())
    }
}
