package com.vayunmathur.health.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the generic charts plus the axis formatter behind them.
 *
 * The bar and line charts render every metric dashboard; covering them here
 * exercises both chart files and the shared axis helpers in one place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenericChartsTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `axis values format with commas below ten thousand`() {
        val resources = context.resources
        assertEquals("999", chartValueFormat(resources, 999.4))
        assertEquals("9,999", chartValueFormat(resources, 9999.0))
    }

    @Test
    fun `axis values abbreviate to thousands above ten thousand`() {
        // One decimal is kept: 25_000 -> "25.0k".
        assertEquals("25.0k", chartValueFormat(context.resources, 25_000.0))
        assertEquals("12.0k", chartValueFormat(context.resources, 12345.0))
    }

    @Test
    fun `bar chart renders labels and goal`() {
        rule.setContent {
            GenericBarChart(
                data = listOf("Mon" to 5000.0, "Tue" to 8000.0, "Wed" to 3000.0),
                totalBarCount = 7,
                goalValue = 10000.0,
                barColor = Color.Blue,
                goalColor = Color.Red,
            )
        }
        rule.onNodeWithText("Mon").assertIsDisplayed()
        rule.onNodeWithText("Tue").assertIsDisplayed()
        // Y-axis always ends at zero; the max label renders the goal-scaled ceiling.
        rule.onNodeWithText("0").assertIsDisplayed()
        rule.onNodeWithText("12.0k", substring = true).assertIsDisplayed()
    }

    @Test
    fun `bar chart tolerates empty data`() {
        rule.setContent {
            GenericBarChart(
                data = emptyList(),
                totalBarCount = 0,
                goalValue = 10000.0,
                barColor = Color.Blue,
                goalColor = Color.Red,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `line chart renders with dual series`() {
        rule.setContent {
            GenericLineChart(
                data = listOf("Mon" to 120.0, "Tue" to 118.0, "Wed" to null),
                secondaryData = listOf("Mon" to 80.0, "Tue" to 82.0, "Wed" to 79.0),
                goalValue = 120.0,
                secondaryGoal = 80.0,
                lineColor = Color.Blue,
                secondaryLineColor = Color.Cyan,
                goalColor = Color.Red,
            )
        }
        rule.onNodeWithText("Mon").assertIsDisplayed()
        // The chart labels its max, average and min; the average of the two
        // series is pinned here.
        rule.onNodeWithText("avg", substring = true).assertIsDisplayed()
        rule.onNodeWithText("min", substring = true).assertIsDisplayed()
    }

    @Test
    fun `line chart with all gaps renders nothing`() {
        // Every value null: the chart returns early rather than dividing by zero.
        rule.setContent {
            GenericLineChart(
                data = listOf("Mon" to null, "Tue" to null),
                goalValue = 120.0,
                lineColor = Color.Blue,
                secondaryLineColor = Color.Cyan,
                goalColor = Color.Red,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `chart header renders the metric average`() {
        rule.setContent {
            ChartHeader(
                config = HealthMetricConfig.STEPS,
                avgString = "8,432",
                unitLabel = "steps",
            )
        }
        rule.onNodeWithText("8,432").assertIsDisplayed()
        rule.onNodeWithText("steps").assertIsDisplayed()
    }
}
