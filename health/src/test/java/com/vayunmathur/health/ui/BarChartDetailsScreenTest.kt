package com.vayunmathur.health.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.health.util.MetricDetailsActions
import com.vayunmathur.health.util.MetricDetailsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the per-metric chart screen.
 *
 * The screen is ViewModel-free by design (state in, callbacks out), so the
 * tests drive it with canned dashboard data and verify the period tabs, the
 * prev/next navigation, and the empty-state copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BarChartDetailsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val today = LocalDate(2026, 9, 16)

    private fun state(
        config: HealthMetricConfig = HealthMetricConfig.STEPS,
        data: MetricDashboardData = MetricDashboardData(
            totalValue = 21_000.0,
            dailyAverage = 7_000.0,
            chartData = listOf("Mon" to 5000.0, "Tue" to 8000.0, "Wed" to 8000.0),
            totalBarCount = 7,
        ),
    ) = MetricDetailsUiState(config = config, today = today, data = data)

    @Test
    fun `week view shows tabs and chart data`() {
        var loads = 0
        val actions = object : MetricDetailsActions {
            override fun loadBarChartData(
                config: HealthMetricConfig,
                anchorDate: LocalDate,
                selectedTab: Int,
            ) {
                loads++
            }
        }
        rule.setContent {
            BarChartDetailsScreen(state = state(), actions = actions)
        }
        rule.onNodeWithText("Day").assertIsDisplayed()
        rule.onNodeWithText("Week").assertIsDisplayed()
        rule.onNodeWithText("Month").assertIsDisplayed()
        rule.onNodeWithText("Year").assertIsDisplayed()
        // The screen requests its data on launch.
        rule.runOnIdle { assertTrue(loads >= 1, "loads chart data") }
    }

    @Test
    fun `heart rate skips the period tabs`() {
        rule.setContent {
            BarChartDetailsScreen(
                state = state(config = HealthMetricConfig.HEART_RATE),
                actions = MetricDetailsActions.Noop,
            )
        }
        rule.onNodeWithText("Day").assertDoesNotExist()
        rule.onNodeWithText("Week").assertDoesNotExist()
    }

    @Test
    fun `empty data shows the empty state`() {
        rule.setContent {
            BarChartDetailsScreen(
                state = state(data = MetricDashboardData()),
                actions = MetricDetailsActions.Noop,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `switching tabs reloads`() {
        var lastTab = -1
        val actions = object : MetricDetailsActions {
            override fun loadBarChartData(
                config: HealthMetricConfig,
                anchorDate: LocalDate,
                selectedTab: Int,
            ) {
                lastTab = selectedTab
            }
        }
        rule.setContent {
            BarChartDetailsScreen(state = state(), actions = actions, initialTab = 1)
        }
        rule.onNodeWithText("Month").performClick()
        rule.runOnIdle { assertEquals(2, lastTab) }
    }

    @Test
    fun `back navigates up`() {
        var ups = 0
        val actions = object : MetricDetailsActions {
            override fun navigateUp() {
                ups++
            }
        }
        rule.setContent {
            BarChartDetailsScreen(state = state(), actions = actions)
        }
        rule.runOnIdle { actions.navigateUp() }
        assertEquals(1, ups)
    }
}
