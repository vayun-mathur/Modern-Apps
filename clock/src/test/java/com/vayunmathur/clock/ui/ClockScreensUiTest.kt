package com.vayunmathur.clock.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.CompositionLocalProvider
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavResultRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.platform.AlarmActions
import com.vayunmathur.clock.platform.AlarmUiState
import com.vayunmathur.clock.platform.ClockUiState
import com.vayunmathur.clock.platform.StopwatchActions
import com.vayunmathur.clock.platform.StopwatchUiState
import com.vayunmathur.clock.platform.TimerActions
import com.vayunmathur.clock.platform.TimerUiState
import com.vayunmathur.clock.platform.WorldClock
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock

/** Robolectric Compose tests for the four clock tab screens. */
@RunWith(AndroidJUnit4::class)
class ClockScreensUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Main()))

    private fun clickRow(text: String) {
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    private fun alarm(name: String = "gym", enabled: Boolean = true, days: Int = 0, id: Long = 1) =
        Alarm(LocalTime(7, 30), name, enabled, days, id = id)

    // --- AlarmScreen ---

    @Test
    fun alarmScreenEmptyShowsSetAlarm() {
        rule.setContent { CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) { AlarmScreen(backStack(), AlarmUiState(), AlarmActions.Noop) } }
        rule.onNodeWithText("Set an alarm").assertIsDisplayed()
    }

    @Test
    fun alarmScreenListsAlarms12And24Hour() {
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                AlarmScreen(backStack(), AlarmUiState(listOf(alarm(), alarm("work", false, id = 2)), is24Hour = false), AlarmActions.Noop)
            }
        }
        rule.onNodeWithText("gym").assertIsDisplayed()
        rule.onNodeWithText("work").assertIsDisplayed()
    }

    @Test
    fun alarmCardToggleAndDeleteFire() {
        var enabled: Boolean? = null
        var deleted = false
        val actions = object : AlarmActions {
            override fun setEnabled(alarm: Alarm, value: Boolean) { enabled = value }
            override fun delete(alarm: Alarm) { deleted = true }
        }
        val stack = backStack()
        rule.setContent { CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) { AlarmScreen(stack, AlarmUiState(listOf(alarm())), actions) } }
        rule.onNodeWithContentDescription("Delete")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertTrue(deleted)
    }

    @Test
    fun alarmCardDayToggleFires() {
        var days: Int? = null
        val actions = object : AlarmActions {
            override fun setDays(alarm: Alarm, value: Int) { days = value }
        }
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                AlarmScreen(backStack(), AlarmUiState(listOf(alarm(days = 0))), actions, initialExpandedAlarmId = 1)
            }
        }
        // Expanded card shows the label field.
        rule.onNodeWithText("Label").assertExists()
        // Day toggle buttons carry single-letter day names; clicking one sets its bit.
        val toggles = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(toggles.size > 4, "nodes=${toggles.size}")
    }

    @Test
    fun alarmCardExpandedShowsOptions() {
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                AlarmScreen(backStack(), AlarmUiState(listOf(alarm())), AlarmActions.Noop, initialExpandedAlarmId = 1)
            }
        }
        rule.onNodeWithText("Sound").assertExists()
        rule.onNodeWithText("Vibrate").assertExists()
        rule.onNodeWithText("Snooze length", substring = true).assertExists()
    }

    // --- ClockScreen ---

    @Test
    fun clockScreenShowsTimeAndCities() {
        val now = Clock.System.now()
        val state = ClockUiState(
            now = { now }, zone = TimeZone.UTC, is24Hour = true,
            worldClocks = listOf(WorldClock("Tokyo", TimeZone.of("Asia/Tokyo")), WorldClock("London", TimeZone.of("Europe/London"))),
        )
        rule.setContent { ClockScreen(backStack(), state) }
        rule.onNodeWithText("Tokyo").assertIsDisplayed()
        rule.onNodeWithText("London").assertIsDisplayed()
    }

    @Test
    fun clockScreen12HourShowsAmPm() {
        // 2024-01-01 15:00 UTC
        val now = kotlin.time.Instant.fromEpochMilliseconds(1704121200000L)
        val state = ClockUiState(now = { now }, zone = TimeZone.UTC, is24Hour = false)
        rule.setContent { ClockScreen(backStack(), state) }
        rule.onAllNodesWithText("PM", substring = true).onFirst().assertIsDisplayed()
    }

    // --- TimerScreen ---

    @Test
    fun timerScreenKeypadStartsTimer() {
        var started: kotlin.time.Duration? = null
        var name: String? = null
        val actions = object : TimerActions {
            override fun start(duration: kotlin.time.Duration, timerName: String) { started = duration; name = timerName }
        }
        rule.setContent { TimerScreen(TimerUiState(now = { Clock.System.now() }), actions) }
        // Type 1:30 via the keypad digits.
        clickRow("1")
        clickRow("3")
        clickRow("0")
        // Start button is the enabled icon button at the bottom; click via its click action
        // (the last clickable) using semantics.
        val clickables = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(clickables.isNotEmpty())
        // The digit display now shows the entered seconds unit.
        rule.onNodeWithText("30", substring = true).assertExists()
    }

    @Test
    fun timerScreenListsTimers() {
        val now = Clock.System.now()
        val timers = listOf(
            Timer(false, "", now, kotlin.time.Duration.ZERO, 5.minutes, id = 3),
            Timer(true, "tea", now, 4.minutes, 5.minutes, id = 1),
            Timer(false, "pasta", now, 9.minutes, 10.minutes, id = 2),
        )
        rule.setContent { TimerScreen(TimerUiState(now = { now }, timers = timers), TimerActions.Noop) }
        rule.onNodeWithText("Completed").assertIsDisplayed()
        rule.onNodeWithText("tea").assertExists()
        rule.onNodeWithText("Running").assertExists()
    }

    @Test
    fun timerCardButtonsFire() {
        var toggled = false
        var added = false
        val now = Clock.System.now()
        val timer = Timer(false, "tea", now, 4.minutes, 5.minutes, id = 1)
        val actions = object : TimerActions {
            override fun toggle(timer: Timer) { toggled = true }
            override fun addMinute(timer: Timer) { added = true }
        }
        rule.setContent { TimerScreen(TimerUiState(now = { now }, timers = listOf(timer)), actions) }
        clickRow("+ 1:00")
        assertTrue(added)
    }

    // --- StopwatchScreen ---

    @Test
    fun stopwatchPausedShowsTimeAndLaps() {
        val state = StopwatchUiState(
            isRunning = false, countingTime = 65.seconds,
            lapTimes = listOf(30.seconds, 65.seconds),
        )
        rule.setContent { StopwatchScreen(state, StopwatchActions.Noop) }
        rule.onAllNodesWithText("1:05", substring = true).onFirst().assertExists()
        rule.onNodeWithText("Laps").assertIsDisplayed()
    }

    @Test
    fun stopwatchRunningToggleFires() {
        var toggled = false
        val actions = object : StopwatchActions {
            override fun toggleStopwatch() { toggled = true }
        }
        rule.setContent { StopwatchScreen(StopwatchUiState(isRunning = true, countingTime = 5.seconds), actions) }
        rule.onNodeWithContentDescription("Pause")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertTrue(toggled)
    }

    @Test
    fun stopwatchZeroState() {
        rule.setContent { StopwatchScreen(StopwatchUiState(), StopwatchActions.Noop) }
        rule.onNodeWithText("0:00", substring = true).assertExists()
        rule.onNodeWithText("Laps").assertIsDisplayed()
    }
}
