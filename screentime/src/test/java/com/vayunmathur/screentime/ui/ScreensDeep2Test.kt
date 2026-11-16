package com.vayunmathur.screentime.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.screentime.data.AppTimer
import com.vayunmathur.screentime.data.WindDownSchedule
import com.vayunmathur.screentime.platform.AppDetailsUiState
import com.vayunmathur.screentime.platform.ScreenTimeUiState
import com.vayunmathur.screentime.platform.TimedApp
import com.vayunmathur.screentime.platform.UsageBar
import com.vayunmathur.screentime.platform.UsagePeriod
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Dashboard stepping/access, details timer clearing and wind-down day/time editing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreensDeep2Test {

    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) =
        if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    private fun swipeUp() {
        rule.onRoot().performTouchInput { down(center); moveBy(Offset(0f, -700f)); up() }
    }

    @Test fun dashboardDaySteppingAndTodayLabel() {
        var stepped: Boolean? = null
        val yesterday = LocalDate.now().minusDays(1)
        val state = ScreenTimeUiState(
            loading = false, hasUsageAccess = true, installedLoaded = true,
            period = UsagePeriod.Day, anchorDate = yesterday,
            chart = (0 until 24).map { UsageBar("$it", 0L) },
            totalMillis = 1_800_000, averageMillis = 75_000,
        )
        rule.setContent {
            DashboardScreen(state, DashboardActions(onSetPeriod = {}, onStep = { stepped = it }, onOpenApp = {}))
        }
        rule.waitForIdle()
        // Back is always enabled; forward is enabled because the anchor is before today.
        rule.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(false, stepped)
        rule.onNodeWithContentDescription("Forward").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, stepped)
        assertTrue(rule.onAllNodesWithText(rangeLabel(UsagePeriod.Day, yesterday), substring = true)
            .fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun dashboardNoAccessRowOpensUsageSettings() {
        val state = ScreenTimeUiState(loading = false, hasUsageAccess = false, installedLoaded = true)
        rule.setContent {
            DashboardScreen(state, DashboardActions(onSetPeriod = {}, onStep = {}, onOpenApp = {}))
        }
        rule.onNodeWithText(str(com.vayunmathur.screentime.R.string.dashboard_no_access_action)).performClick()
        val started = Shadows.shadowOf(ctx as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun dashboardEmptyStates() {
        rule.setContent {
            DashboardScreen(
                ScreenTimeUiState(loading = false, hasUsageAccess = true, installedLoaded = false),
                DashboardActions(onSetPeriod = {}, onStep = {}, onOpenApp = {}),
            )
        }
        swipeUp()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(str(com.vayunmathur.screentime.R.string.dashboard_no_apps_title))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(str(com.vayunmathur.screentime.R.string.dashboard_no_apps_title)).assertExists()
    }

    @Test fun dashboardAppRowWithTimerOpensDetails() {
        var opened: String? = null
        val state = ScreenTimeUiState(
            loading = false, hasUsageAccess = true, installedLoaded = true,
            apps = listOf(
                TimedApp("com.a.app", "Alpha App", 3_600_000, AppTimer("com.a.app", 60)),
                TimedApp("com.b.app", "Beta App", 0, null),
            ),
            totalMillis = 3_600_000,
        )
        rule.setContent {
            DashboardScreen(state, DashboardActions(onSetPeriod = {}, onStep = {}, onOpenApp = { opened = it }))
        }
        swipeUp()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Beta App").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Beta App").performClick()
        assertEquals("com.b.app", opened)
    }

    @Test fun appDetailsTimerCanBeClearedAndBackWorks() {
        var timer: Int? = 60
        var timerSet = false
        var backed = false
        val state = AppDetailsUiState(
            packageName = "com.a.app", label = "Alpha App", hasUsageAccess = true,
            totalMillis = 7_200_000, timerMinutes = 60,
            chart = listOf(UsageBar("Mon", 7_200_000)),
        )
        rule.setContent {
            AppDetailsScreen(
                state, onBack = { backed = true }, onSetPeriod = {}, onStep = {},
                onTimerChange = { timer = it; timerSet = true }, onPausedChange = {},
            )
        }
        // Two Back icons exist (top bar and range stepper); the top bar is the second in tree order.
        rule.onAllNodesWithContentDescription("Back")[1].performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(backed)
        swipeUp()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(str(com.vayunmathur.screentime.R.string.details_timer))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(str(com.vayunmathur.screentime.R.string.details_timer)).performClick()
        // The first "No timer" row in the picker clears the existing limit.
        rule.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasText(str(com.vayunmathur.screentime.R.string.timer_none))), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(timerSet)
        assertEquals(null, timer)
    }

    @Test fun windDownDayChipsToggle() {
        var day: Int? = null
        val state = ScreenTimeUiState(
            windDown = WindDownSchedule(enabled = true, daysMask = WindDownSchedule.ALL_DAYS),
        )
        rule.setContent {
            WindDownScreen(
                state,
                onEnabledChange = {}, onStartChange = { _, _ -> },
                onEndChange = { _, _ -> }, onToggleDay = { day = it },
                onGrayscaleChange = {}, onDndChange = {},
            )
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("M").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText("M")[0].performClick()
        assertEquals(0, day)
    }

    @Test fun windDownTimePickerSaveReportsPickedTime() {
        var start: Pair<Int, Int>? = null
        val state = ScreenTimeUiState(
            windDown = WindDownSchedule(enabled = true, startMinute = 22 * 60),
        )
        rule.setContent {
            WindDownScreen(
                state,
                onEnabledChange = {}, onStartChange = { h, m -> start = h to m },
                onEndChange = { _, _ -> }, onToggleDay = {},
                onGrayscaleChange = {}, onDndChange = {},
            )
        }
        rule.onNodeWithText(str(com.vayunmathur.screentime.R.string.winddown_start)).performClick()
        rule.onNodeWithText(str(com.vayunmathur.screentime.R.string.save)).performClick()
        assertEquals(22 to 0, start)
    }

    @Test fun dashboardWeekSteppingFromPastWeek() {
        var stepped: Boolean? = null
        var period: UsagePeriod? = null
        val pastWeek = LocalDate.now().minusDays(14)
        val state = ScreenTimeUiState(
            loading = false, hasUsageAccess = true, installedLoaded = true,
            period = UsagePeriod.Week, anchorDate = pastWeek,
            chart = (1..7).map { UsageBar("D$it", it * 60_000L) },
            totalMillis = 1_680_000, averageMillis = 240_000,
            apps = listOf(TimedApp("com.a.app", "Alpha App", 1_680_000, null)),
        )
        rule.setContent {
            DashboardScreen(state, DashboardActions(onSetPeriod = { period = it }, onStep = { stepped = it }, onOpenApp = {}))
        }
        rule.waitForIdle()
        rule.onNodeWithText("Day").performClick()
        assertEquals(UsagePeriod.Day, period)
        rule.onNodeWithContentDescription("Forward").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, stepped)
        rule.onNodeWithText(formatStat(1_680_000)).assertExists()
    }
}
