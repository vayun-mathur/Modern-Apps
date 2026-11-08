package com.vayunmathur.screentime.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import com.vayunmathur.screentime.data.WindDownSchedule
import com.vayunmathur.screentime.platform.AppDetailsUiState
import com.vayunmathur.screentime.platform.ScreenTimeUiState
import com.vayunmathur.screentime.platform.UsageBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deep screen interactions: timer picker, pause switch, wind-down days/effects, chart variants. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenTimeDeepScreensTest {

    @get:Rule val rule = createComposeRule()

    private fun swipeUp() {
        rule.onRoot().performTouchInput { down(center); moveBy(Offset(0f, -700f)); up() }
    }

    @Test fun appDetails_timerPickerSelects() {
        var timer: Int? = -1
        var timerSet = false
        val state = AppDetailsUiState(
            packageName = "com.a.app", label = "Alpha App", hasUsageAccess = true,
            totalMillis = 7_200_000, timerMinutes = null,
            chart = listOf(UsageBar("Mon", 7_200_000)),
        )
        rule.setContent {
            AppDetailsScreen(state, onBack = {}, onSetPeriod = {}, onStep = {}, onTimerChange = { timer = it; timerSet = true }, onPausedChange = {})
        }
        swipeUp()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Timer").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Timer").performClick()
        rule.onNodeWithText("30 min").performClick()
        rule.runOnIdle { assertTrue(timerSet); assertEquals(30, timer) }
    }

    @Test fun appDetails_pauseSwitch() {
        var paused: Boolean? = null
        val state = AppDetailsUiState(
            packageName = "com.a.app", label = "Alpha App", hasUsageAccess = true, appPaused = false,
            chart = listOf(UsageBar("Mon", 1000)),
        )
        rule.setContent {
            AppDetailsScreen(state, onBack = {}, onSetPeriod = {}, onStep = {}, onTimerChange = {}, onPausedChange = { paused = it })
        }
        swipeUp()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Pause this app").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Pause this app").performClick()
        rule.runOnIdle { assertEquals(true, paused) }
    }

    @Test fun appDetails_noPackage_showsPlaceholder() {
        rule.setContent {
            AppDetailsScreen(AppDetailsUiState(), onBack = {}, onSetPeriod = {}, onStep = {}, onTimerChange = {}, onPausedChange = {})
        }
        rule.onNodeWithText("Time spent").assertIsDisplayed()
    }

    @Test fun windDown_daysAndEffects() {
        var day: Int? = null
        var grayscale: Boolean? = null
        var dnd: Boolean? = null
        val state = ScreenTimeUiState(
            windDown = WindDownSchedule(enabled = true, grayscale = false, doNotDisturb = false),
        )
        rule.setContent {
            WindDownScreen(
                state,
                onEnabledChange = {}, onStartChange = { _, _ -> }, onEndChange = { _, _ -> },
                onToggleDay = { day = it }, onGrayscaleChange = { grayscale = it }, onDndChange = { dnd = it },
            )
        }
        rule.onNodeWithText("Wind Down").assertIsDisplayed()
        swipeUp()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Grayscale").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Grayscale").performClick()
        rule.onNodeWithText("Do Not Disturb").performClick()
        rule.runOnIdle { assertEquals(true, grayscale); assertEquals(true, dnd) }
    }

    @Test fun windDown_timePickerOpens() {
        val state = ScreenTimeUiState(windDown = WindDownSchedule(enabled = true))
        rule.setContent {
            WindDownScreen(state, {}, { _, _ -> }, { _, _ -> }, {}, {}, {})
        }
        rule.onNodeWithText("Starts at").performClick()
        rule.onNodeWithText("Save").assertExists()
        rule.onNodeWithText("Cancel").performClick()
    }

    @Test fun chart_dayBarsAndAverageZero() {
        rule.setContent {
            UsageBarChart(
                bars = (0 until 24).map { UsageBar("$it", if (it % 3 == 0) 3_600_000L else 0L) },
                averageMillis = 0, labelEvery = 4,
            )
        }
        // Canvas-only: composing 24 bars with a zero average must not crash.
    }

    @Test fun chart_singleBar() {
        rule.setContent {
            UsageBarChart(bars = listOf(UsageBar("Mon", 60_000)), averageMillis = 60_000, labelEvery = 1)
        }
    }
}
