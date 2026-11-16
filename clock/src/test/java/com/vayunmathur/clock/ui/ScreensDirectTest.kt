package com.vayunmathur.clock.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.up
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.Timer
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
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The tab screens composed directly with state and recording actions: the
 * timer keypad flow, alarm list/empty states and their routes, world-clock
 * rows, and the stopwatch with laps.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w400dp-h800dp")
class ScreensDirectTest {


    companion object {
        /** Drain stored background-DB errors from earlier classes (see ClockDialogsDeepTest). */
        @org.junit.BeforeClass @JvmStatic fun drainStoredDbErrors() {
            repeat(8) {
                try {
                    kotlinx.coroutines.test.runTest { }
                } catch (e: IllegalStateException) {
                    if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
                }
                Thread.sleep(400)
            }
        }
    }

    @get:Rule val rule = createComposeRule()

    private class RecordingTimerActions : TimerActions {
        val calls = mutableListOf<String>()
        var started: Pair<Duration, String>? = null
        override fun start(duration: Duration, name: String) { started = duration to name; calls += "start" }
        override fun delete(timer: Timer) { calls += "delete" }
        override fun toggle(timer: Timer) { calls += "toggle" }
    }

    private class RecordingStopwatchActions : StopwatchActions {
        val calls = mutableListOf<String>()
        override fun toggleStopwatch() { calls += "toggle" }
        override fun resetStopwatch() { calls += "reset" }
        override fun addLap() { calls += "lap" }
    }

    @Test fun timerScreenKeypadTypesAndStarts() {
        val actions = RecordingTimerActions()
        val now = Clock.System.now()
        rule.setContent {
            TimerScreen(TimerUiState(now = { now }, timers = emptyList()), actions)
        }
        rule.waitForIdle()
        // 1m 30s from the keypad: digits 1, 3, 0 land as 00:01:30.
        rule.onNodeWithText("1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("01").assertExists()
        rule.onNodeWithText("3").performClick()
        rule.onNodeWithText("0").performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("tea")
        // The start button is the last clickable in tree order (digits, backspace, then it).
        val clickables = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[clickables.size - 1].performClick()
        rule.waitForIdle()
        assertEquals(90.seconds to "tea", actions.started, "clickables=${clickables.size} started=${actions.started}")
    }

    @Test fun timerScreenKeypadBackspaceAndDoubleZero() {
        val actions = RecordingTimerActions()
        val now = Clock.System.now()
        rule.setContent {
            TimerScreen(TimerUiState(now = { now }, timers = emptyList()), actions, initialKeypadInput = "5")
        }
        rule.waitForIdle()
        rule.onNodeWithText("⌫").performClick() // erase the 5 -> zero, start disabled
        rule.onNodeWithText("2").performClick()
        rule.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasText("00")).performClick() // appends "00"
        val clickables = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[clickables.size - 1].performClick()
        rule.waitForIdle()
        assertEquals(2.minutes, actions.started?.first)
    }

    @Test fun timerScreenAddTimerFromFabAndCancelBack() {
        val actions = RecordingTimerActions()
        val now = Clock.System.now()
        val timer = Timer(false, "tea", now, 5.minutes, 3.minutes, id = 7)
        rule.setContent {
            TimerScreen(TimerUiState(now = { now }, timers = listOf(timer)), actions)
        }
        rule.waitForIdle()
        rule.onNodeWithText("tea").assertExists()
        rule.onNodeWithContentDescription("Add").performClick()
        rule.waitForIdle()
        // Keypad is up with a Cancel (close) affordance because timers exist.
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("tea").assertExists()
        assertTrue(actions.calls.isEmpty(), actions.calls.toString())
    }

    @Test fun alarmScreenEmptyPushesNewAlarmRoute() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main()))
        rule.setContent {
            AlarmScreen(stack, AlarmUiState(alarms = emptyList()), com.vayunmathur.clock.platform.AlarmActions.Noop)
        }
        rule.waitForIdle()
        rule.onNodeWithText("Set an alarm").performClick()
        assertTrue(stack.backStack.last() is Route.NewAlarmDialog, stack.backStack.toString())
    }

    @Test fun alarmScreenPopulatedRoutesAndCards() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main()))
        val alarms = listOf(
            Alarm(LocalTime(7, 30), "Gym", true, 0b0100010, id = 1),
            Alarm(LocalTime(22, 15), "", false, 0, id = 2),
        )
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                com.vayunmathur.library.util.LocalNavResultRegistry provides
                    com.vayunmathur.library.util.NavResultRegistry(),
            ) {
                AlarmScreen(stack, AlarmUiState(alarms = alarms, is24Hour = true), com.vayunmathur.clock.platform.AlarmActions.Noop)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Gym").assertExists()
        rule.onNodeWithContentDescription("Settings").performClick()
        assertTrue(stack.backStack.last() is Route.AlarmSettings, stack.backStack.toString())
    }

    @Test fun clockScreenRowsAndAddRoute() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main()))
        val now = Clock.System.now()
        val state = ClockUiState(
            now = { now },
            zone = TimeZone.UTC,
            worldClocks = listOf(
                WorldClock("Tokyo", TimeZone.of("Asia/Tokyo")),
                WorldClock("London", TimeZone.of("Europe/London")),
            ),
        )
        rule.setContent { ClockScreen(stack, state) }
        rule.waitForIdle()
        rule.onNodeWithText("Tokyo").assertExists()
        rule.onNodeWithText("London").assertExists()
        rule.onNodeWithContentDescription("Add").performClick()
        assertTrue(stack.backStack.last() is Route.SelectTimeZonesDialog, stack.backStack.toString())
    }

    @Test fun clockScreenDragReordersCities() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main()))
        val now = Clock.System.now()
        var reordered: List<String>? = null
        val state = ClockUiState(
            now = { now },
            zone = TimeZone.UTC,
            worldClocks = listOf(
                WorldClock("Tokyo", TimeZone.of("Asia/Tokyo")),
                WorldClock("London", TimeZone.of("Europe/London")),
            ),
        )
        rule.setContent { ClockScreen(stack, state) { reordered = it } }
        rule.waitForIdle()
        // Long-press the first city's drag handle and pull it below the second.
        val handles = rule.onAllNodesWithContentDescription("Reorder").fetchSemanticsNodes()
        assertEquals(2, handles.size)
        val first = handles[0].positionInRoot
        val second = handles[1].positionInRoot
        rule.onAllNodesWithContentDescription("Reorder")[0].performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(androidx.compose.ui.geometry.Offset(0f, (second.y - first.y) + 40f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("London", "Tokyo"), reordered)
    }

    @Test fun stopwatchScreenRunningWithLaps() {
        val actions = RecordingStopwatchActions()
        val state = StopwatchUiState(
            isRunning = true,
            countingTime = 65.seconds,
            lapTimes = listOf(30.seconds, 65.seconds),
        )
        rule.setContent { StopwatchScreen(state, actions) }
        rule.waitForIdle()
        rule.onNodeWithText("# 1").assertExists()
        rule.onNodeWithText("# 2").assertExists()
        rule.onNodeWithContentDescription("Pause").performClick()
        assertEquals(listOf("toggle"), actions.calls)
    }

    @Test fun stopwatchScreenPausedReset() {
        val actions = RecordingStopwatchActions()
        val state = StopwatchUiState(
            isRunning = false,
            countingTime = 12.seconds,
            lapTimes = listOf(12.seconds),
        )
        rule.setContent { StopwatchScreen(state, actions) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Restart").performClick()
        assertEquals(listOf("reset"), actions.calls)
    }
}
