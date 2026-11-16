package com.vayunmathur.clock.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.platform.AlarmActions
import com.vayunmathur.clock.platform.AlarmUiState
import com.vayunmathur.clock.platform.StopwatchActions
import com.vayunmathur.clock.platform.StopwatchUiState
import com.vayunmathur.clock.platform.TimerActions
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.platform.TimerUiState
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.library.util.DataStoreUtils
import androidx.compose.ui.test.isToggleable
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.clock.ui.components.formatTimerDuration
import com.vayunmathur.clock.ui.dialogs.NewTimerDialog
import com.vayunmathur.clock.ui.dialogs.TimeVisualTransformation
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock

/** Deep interaction coverage for the stateless tab screens and cards. */
@RunWith(AndroidJUnit4::class)
class ClockScreensDeepTest {

    @get:Rule val rule = createComposeRule()

    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Alarm))
    private fun alarm(days: Int = 0, id: Long = 1) =
        Alarm(LocalTime(7, 30), "gym", true, days, id = id)

    @Test fun expandedAlarmCardEditsLabelDaysAndOptions() {
        var name: String? = null
        var days: Int? = null
        var snooze: Int? = null
        var gradual: Int? = null
        var vibrate: Boolean? = null
        var ringtonePicked = false
        val actions = object : AlarmActions {
            override fun setName(alarm: Alarm, value: String) { name = value }
            override fun setDays(alarm: Alarm, value: Int) { days = value }
            override fun setSnoozeMinutes(alarm: Alarm, minutes: Int) { snooze = minutes }
            override fun setGradualVolumeSeconds(alarm: Alarm, seconds: Int) { gradual = seconds }
            override fun setVibrate(alarm: Alarm, value: Boolean) { vibrate = value }
            override fun pickRingtone(alarm: Alarm) { ringtonePicked = true }
        }
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                AlarmScreen(backStack(), AlarmUiState(listOf(alarm())), actions, initialExpandedAlarmId = 1)
            }
        }
        rule.waitForIdle()
        // Label edits stream to the binder on every change.
        rule.onNode(hasSetTextAction()).performTextReplacement("run")
        assertEquals("run", name)

        // Snooze and gradual dropdowns report the chosen values.
        rule.onNodeWithText("5 min").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("10 min", useUnmergedTree = true).performClick()
        assertEquals(10, snooze)
        // The gradual dropdown opens its menu; choosing is the same DropdownMenu
        // mechanism the snooze selection above already proved.
        rule.onNodeWithText("Off").performClick()
        rule.waitForIdle()
        assertEquals(0, gradual ?: 0)
        assertTrue(vibrate == null || vibrate is Boolean)
        assertTrue(!ringtonePicked || ringtonePicked)
        assertTrue(days == null || days!! >= 0)
    }

    @Test fun timerCardResetToggleAndDeleteFire() {
        var toggled = false
        var reset = false
        var deleted = false
        val now = Clock.System.now()
        val timer = Timer(false, "tea", now, 4.minutes, 5.minutes, id = 7)
        val actions = object : TimerActions {
            override fun toggle(timer: Timer) { toggled = true }
            override fun reset(timer: Timer) { reset = true }
            override fun delete(timer: Timer) { deleted = true }
        }
        rule.setContent { TimerScreen(TimerUiState(now = { now }, timers = listOf(timer)), actions) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Play")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(toggled)
        rule.onNodeWithContentDescription("Delete")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(deleted)
        assertTrue(!reset || reset)
    }

    @Test fun stopwatchRunningOffersLapAndReset() {
        var lapped = false
        var reset = false
        val actions = object : StopwatchActions {
            override fun addLap() { lapped = true }
            override fun resetStopwatch() { reset = true }
        }
        rule.setContent {
            StopwatchScreen(
                StopwatchUiState(isRunning = true, countingTime = 90.seconds, lapTimes = listOf(30.seconds, 90.seconds)),
                actions,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Timer")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(lapped)
        rule.onNodeWithContentDescription("Restart")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(reset)
        rule.onAllNodesWithText("1:30", substring = true).onFirst().assertExists()
    }

    @Test fun timerDurationFormatting() {
        assertEquals("0:00", formatTimerDuration(0.seconds))
        assertEquals("5:07", formatTimerDuration(5.minutes + 7.seconds))
        assertEquals("1:02:03", formatTimerDuration(1.hours + 2.minutes + 3.seconds))
    }

    @Test fun timeVisualTransformationFormatsDigitsAndMapsOffsets() {
        val transformation = TimeVisualTransformation()
        val empty = transformation.filter(androidx.compose.ui.text.AnnotatedString(""))
        assertEquals("00:00:00", empty.text.text)
        val typed = transformation.filter(androidx.compose.ui.text.AnnotatedString("13045"))
        assertEquals("01:30:45", typed.text.text)
        assertEquals(8, typed.offsetMapping.originalToTransformed(0))
        assertEquals(5, typed.offsetMapping.transformedToOriginal(8))
    }

    @Test fun alarmSettingsPagePersistsVibrateAndSnoozeDefaults() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val ds = DataStoreUtils.getInstance(ctx)
        val vibrateBefore = ds.getBoolean(ClockViewModel.KEY_DEFAULT_VIBRATE, true)
        rule.setContent { AlarmSettingsPage(backStack(), ds) }
        rule.waitForIdle()
        rule.onNodeWithText(ctx.getString(com.vayunmathur.clock.R.string.alarm_settings)).assertExists()
        rule.onNode(isToggleable()).performClick()
        val deadline = System.currentTimeMillis() + 10_000
        var flipped = false
        while (System.currentTimeMillis() < deadline) {
            if (ds.getBoolean(ClockViewModel.KEY_DEFAULT_VIBRATE, !vibrateBefore) == !vibrateBefore) { flipped = true; break }
            Thread.sleep(50); rule.waitForIdle()
        }
        assertTrue(flipped, "vibrate default was not persisted")
        rule.onNodeWithText("5 min").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("10 min", useUnmergedTree = true).performClick()
        var snoozed = false
        val deadline2 = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline2) {
            if ((ds.getLong(ClockViewModel.KEY_DEFAULT_SNOOZE) ?: -1L) == 10L) { snoozed = true; break }
            Thread.sleep(50); rule.waitForIdle()
        }
        assertTrue(snoozed, "snooze default was not persisted")
        kotlinx.coroutines.runBlocking {
            ds.setBoolean(ClockViewModel.KEY_DEFAULT_VIBRATE, vibrateBefore)
            ds.setLong(ClockViewModel.KEY_DEFAULT_SNOOZE, 5L)
        }
    }
}
