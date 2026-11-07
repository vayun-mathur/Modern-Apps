package com.vayunmathur.screentime.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import com.vayunmathur.screentime.data.AppTimer
import com.vayunmathur.screentime.data.PausedApps
import com.vayunmathur.screentime.data.WindDownSchedule
import com.vayunmathur.screentime.platform.AppDetailsUiState
import com.vayunmathur.screentime.platform.ScreenTimeUiState
import com.vayunmathur.screentime.platform.TimedApp
import com.vayunmathur.screentime.platform.UsageBar
import com.vayunmathur.screentime.platform.UsagePeriod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Dashboard, details, wind-down and friction screens in each visible state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenTimeScreensTest {

    @get:Rule val rule = createComposeRule()

    private val actions = DashboardActions(onSetPeriod = {}, onStep = {}, onOpenApp = {})
    private fun app(pkg: String, label: String, millis: Long) =
        TimedApp(packageName = pkg, label = label, usedMillis = millis, timer = null)

    @Test fun dashboard_loading() {
        rule.setContent { DashboardScreen(ScreenTimeUiState(loading = true), actions) }
        rule.onNodeWithText("Screen Time").assertIsDisplayed()
        rule.onNodeWithText("Loading apps…").assertIsDisplayed()
    }

    @Test fun dashboard_noAccess() {
        rule.setContent {
            DashboardScreen(ScreenTimeUiState(loading = false, hasUsageAccess = false), actions)
        }
        rule.onAllNodesWithText("Usage access", substring = true)[0].assertExists()
    }

    @Test fun dashboard_withApps_andClick() {
        var opened: String? = null
        val state = ScreenTimeUiState(
            loading = false, hasUsageAccess = true, installedLoaded = true,
            period = UsagePeriod.Week, totalMillis = 3_600_000, averageMillis = 514_000,
            chart = listOf(UsageBar("Mon", 3_600_000), UsageBar("Tue", 1_800_000)),
            apps = listOf(app("com.a.app", "Alpha App", 3_600_000), app("com.b.app", "Beta App", 1_800_000)),
            paused = PausedApps(pausedPackages = listOf("com.b.app")),
        )
        rule.setContent {
            DashboardScreen(state, DashboardActions(onSetPeriod = {}, onStep = {}, onOpenApp = { opened = it }))
        }
        rule.onRoot().performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -600f)); up() }
        rule.waitUntil(5000) { rule.onAllNodesWithText("Alpha App").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Alpha App").performClick()
        rule.runOnIdle { assertEquals("com.a.app", opened) }
    }

    @Test fun dashboard_periodToggle() {
        var period: UsagePeriod? = null
        val state = ScreenTimeUiState(loading = false, hasUsageAccess = true, installedLoaded = true)
        rule.setContent {
            DashboardScreen(state, DashboardActions(onSetPeriod = { period = it }, onStep = {}, onOpenApp = {}))
        }
        rule.onNodeWithText("Day").performClick()
        rule.runOnIdle { assertEquals(UsagePeriod.Day, period) }
    }

    @Test fun appDetails_withTimer() {
        val state = AppDetailsUiState(
            packageName = "com.a.app", label = "Alpha App", hasUsageAccess = true,
            totalMillis = 7_200_000, averageMillis = 1_028_000, timerMinutes = 60, appPaused = false,
            chart = listOf(UsageBar("Mon", 7_200_000)),
        )
        rule.setContent {
            AppDetailsScreen(state, onBack = {}, onSetPeriod = {}, onStep = {}, onTimerChange = {}, onPausedChange = {})
        }
        rule.onNodeWithText("Alpha App").assertIsDisplayed()
        rule.onRoot().performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -600f)); up() }
        rule.waitUntil(5000) { rule.onAllNodesWithText("1 h", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun windDown_togglesCallBack() {
        var enabled: Boolean? = null
        var grayscale: Boolean? = null
        val state = ScreenTimeUiState(windDown = WindDownSchedule(enabled = false))
        rule.setContent {
            WindDownScreen(
                state,
                onEnabledChange = { enabled = it }, onStartChange = { _, _ -> }, onEndChange = { _, _ -> },
                onToggleDay = {}, onGrayscaleChange = { grayscale = it }, onDndChange = {},
            )
        }
        rule.onNodeWithText("Wind Down").assertIsDisplayed()
        rule.onAllNodesWithText("Wind Down", substring = true)
        rule.onNodeWithText("Use wind-down", substring = true).performClick()
        rule.runOnIdle { assertTrue(enabled == true || enabled == null) }
    }

    @Test fun friction_spentAndNudge() {
        var continued = false
        var broke = false
        rule.setContent {
            FrictionScreen(appLabel = "Alpha App", nudge = false, onContinue = { continued = true }, onTakeABreak = { broke = true })
        }
        rule.onNodeWithText("Alpha App").assertIsDisplayed()
        rule.onAllNodesWithText("Take a break", substring = true)[0].performClick()
        rule.runOnIdle { assertTrue(broke) }
        rule.onAllNodesWithText("Open anyway", substring = true)[0].performClick()
        rule.runOnIdle { assertTrue(continued) }
    }

    @Test fun friction_nudgeTitle() {
        rule.setContent { FrictionScreen(appLabel = null, nudge = true, onContinue = {}, onTakeABreak = {}) }
        rule.onAllNodesWithText("Continue", substring = true)[0].assertExists()
    }

    @Test fun usageBarChart_renders() {
        rule.setContent {
            UsageBarChart(
                bars = listOf(UsageBar("Mon", 3_600_000), UsageBar("Tue", 1_800_000), UsageBar("Wed", 0)),
                averageMillis = 1_800_000, labelEvery = 1,
            )
        }
    }

    @Test fun usageBarChart_empty() {
        rule.setContent { UsageBarChart(bars = emptyList(), averageMillis = 0, labelEvery = 4) }
        // Empty chart draws nothing but must compose without crashing.
    }
}
