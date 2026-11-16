package com.vayunmathur.screentime.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import com.vayunmathur.screentime.platform.UsageBar
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Canvas chart in its data shapes: week, day, empty, and average-only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class UsageBarChartTest {

    @get:Rule val rule = createComposeRule()

    @Test fun weekChartWithBarsAndAverage() {
        val bars = listOf(
            UsageBar("Mon", 3_600_000), UsageBar("Tue", 0), UsageBar("Wed", 1_800_000),
            UsageBar("Thu", 7_200_000), UsageBar("Fri", 900_000), UsageBar("Sat", 0),
            UsageBar("Sun", 2_700_000),
        )
        rule.setContent { UsageBarChart(bars, averageMillis = 2_285_000, labelEvery = 1) }
        rule.waitForIdle()
        // Capturing forces a draw pass so the Canvas block actually executes.
        rule.onRoot().captureToImage()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun dayChartWithSparseLabels() {
        val bars = (0 until 24).map { UsageBar("$it", if (it == 9) 1_800_000L else 0L) }
        rule.setContent { UsageBarChart(bars, averageMillis = 75_000, labelEvery = 4) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun emptyChartComposes() {
        rule.setContent { UsageBarChart(emptyList(), averageMillis = 0, labelEvery = 1) }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    @Test fun zeroBarsChartComposes() {
        rule.setContent {
            UsageBarChart(listOf(UsageBar("Mon", 0), UsageBar("Tue", 0)), averageMillis = 0, labelEvery = 1)
        }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }
}
