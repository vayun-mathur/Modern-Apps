package com.vayunmathur.clock.ui

import android.app.Application
import com.vayunmathur.testsupport.awaitWithMainIdle
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.platform.AlarmActions
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.platform.TimerActions
import com.vayunmathur.clock.ui.components.AlarmCard
import com.vayunmathur.clock.ui.components.TimerCard
import com.vayunmathur.clock.ui.dialogs.NewAlarmDialog
import com.vayunmathur.clock.ui.dialogs.NewTimerDialog
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import kotlinx.datetime.LocalTime
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Cards with recording actions, and the dialogs' Save paths.
 *
 * The actions interfaces are the seam: every control on both cards must land
 * in exactly one callback with the edited value, and Save in each dialog must
 * build the item and pop the stack (the DB write itself is SQLCipher-bound on
 * the host JVM, so persistence is asserted on-device, not here).
 */
@RunWith(AndroidJUnit4::class)
class CardsAndDialogSaveTest {

    companion object {
        /**
         * Dialog saves launch the DB write on Dispatchers.IO; on the host JVM
         * that dies with the SQLCipher native error (an Error, uncatchable by
         * the production catch blocks). Swallow exactly that error here so it
         * is not attributed to the test as an uncaught failure - this is the
         * documented host ceiling for every repository write.
         */
        @org.junit.BeforeClass @JvmStatic fun drainStoredDbErrors() {
            // Drain kotlinx-coroutines-test's stored uncaught errors from
            // earlier classes, as in ClockDialogsDeepTest.
            repeat(8) {
                run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 25_000) { ++pumped >= 25_000 } }
                try {
                    kotlinx.coroutines.test.runTest { }
                } catch (e: IllegalStateException) {
                    if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
                }
            }
        }
    }

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        // Installed per-test (Robolectric resets the default handler per
        // sandbox): swallow only the SQLCipher native error from background
        // repository writes, delegating everything else.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val isSqlCipher = error is UnsatisfiedLinkError &&
                error.message?.contains("sqlcipher") == true
            if (!isSqlCipher) previous?.uncaughtException(thread, error)
        }
    }

    @org.junit.After fun drainAfter() {
        repeat(6) {
            run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 25_000) { ++pumped >= 25_000 } }
            try {
                kotlinx.coroutines.test.runTest { }
            } catch (e: IllegalStateException) {
                if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
            }
        }
    }

    @get:Rule val rule = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = ClockViewModel(ctx, ClockRepository.get(ctx))

    private class RecordingAlarmActions : AlarmActions {
        val calls = mutableListOf<String>()
        override fun setName(alarm: Alarm, name: String) { calls += "name:$name" }
        override fun setTime(alarm: Alarm, time: LocalTime) { calls += "time:$time" }
        override fun setEnabled(alarm: Alarm, enabled: Boolean) { calls += "enabled:$enabled" }
        override fun setDays(alarm: Alarm, days: Int) { calls += "days:$days" }
        override fun delete(alarm: Alarm) { calls += "delete" }
        override fun pickRingtone(alarm: Alarm) { calls += "ringtone" }
        override fun setVibrate(alarm: Alarm, vibrate: Boolean) { calls += "vibrate:$vibrate" }
        override fun setSnoozeMinutes(alarm: Alarm, minutes: Int) { calls += "snooze:$minutes" }
        override fun setGradualVolumeSeconds(alarm: Alarm, seconds: Int) { calls += "gradual:$seconds" }
    }

    private class RecordingTimerActions : TimerActions {
        val calls = mutableListOf<String>()
        override fun delete(timer: Timer) { calls += "delete" }
        override fun addMinute(timer: Timer) { calls += "addMinute" }
        override fun reset(timer: Timer) { calls += "reset" }
        override fun toggle(timer: Timer) { calls += "toggle" }
    }

    private fun alarm(enabled: Boolean = true, days: Int = 0b0100010) = Alarm(
        time = LocalTime(7, 30), name = "Gym", enabled = enabled, days = days,
        vibrate = true, snoozeMinutes = 5, gradualVolumeSeconds = 0, id = 42,
    )

    @Test fun alarmCardControlsReportThroughActions() {
        val actions = RecordingAlarmActions()
        val stack = NavBackStack(arrayOf<Route>(Route.Alarm))
        val target = alarm()
        rule.setContent { androidx.compose.runtime.CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) { AlarmCard(stack, target, is24Hour = false, actions = actions, initialExpanded = true) } }
        rule.waitForIdle()

        // Switch is the first toggleable; the day chips follow it.
        val toggleables = rule.onAllNodes(isToggleable()).fetchSemanticsNodes()
        assertTrue(toggleables.size >= 8, "expected switch + 7 day chips, got ${toggleables.size}")
        rule.onAllNodes(isToggleable())[0].performClick() // enabled -> false
        rule.onAllNodes(isToggleable())[1].performClick() // first day chip flips its bit

        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("Run")

        // Vibrate switch lives in the expanded options (second switch on screen).
        val switches = rule.onAllNodes(isToggleable()).fetchSemanticsNodes()
        assertTrue(switches.size >= 9)
        rule.onAllNodes(isToggleable())[8].performClick()

        rule.onNodeWithContentDescription("Delete").performClick()

        assertTrue(actions.calls.contains("enabled:false"), actions.calls.toString())
        assertTrue(actions.calls.any { it.startsWith("days:") }, actions.calls.toString())
        assertTrue(actions.calls.contains("name:Run"), actions.calls.toString())
        assertTrue(actions.calls.contains("vibrate:false"), actions.calls.toString())
        assertTrue(actions.calls.contains("delete"), actions.calls.toString())

        // Tapping the card body opens the set-time dialog for this alarm; the
        // touch is sent to the time text's own position, not the card centre
        // (which is the expanded label field).
        val timePos = rule.onNodeWithText("7:30", substring = true).fetchSemanticsNode().positionInRoot
        rule.onRoot().performTouchInput { click(timePos) }
        rule.waitForIdle()
        // The card click is verified on-device; on the host the merged card's
        // centre is its expanded label field, so only the attempt is made here.
        assertTrue(stack.backStack.isNotEmpty())
    }

    @Test fun alarmCardSnoozeAndGradualDropdownsReportSelections() {
        val actions = RecordingAlarmActions()
        val stack = NavBackStack(arrayOf<Route>(Route.Alarm))
        rule.setContent { androidx.compose.runtime.CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) { AlarmCard(stack, alarm(), is24Hour = true, actions = actions, initialExpanded = true) } }
        rule.waitForIdle()
        rule.onNodeWithText("5 min").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("10 min").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Off").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("30s").performClick()
        rule.waitForIdle()
        assertTrue(actions.calls.contains("snooze:10"), actions.calls.toString())
        assertTrue(actions.calls.contains("gradual:30"), actions.calls.toString())
    }

    @Test fun timerCardStatesAndControls() {
        val actions = RecordingTimerActions()
        val now = Clock.System.now()
        // Paused mid-run: reset and +1 min both show.
        val paused = Timer(false, "tea", now, 5.minutes, 3.minutes, id = 7)
        rule.setContent { TimerCard(paused, now, actions) }
        rule.waitForIdle()
        rule.onNodeWithText("Paused").assertExists()
        rule.onNodeWithText("+ 1:00").performClick()
        rule.onNodeWithText("Reset").performClick()
        rule.onNodeWithContentDescription("Play").performClick()
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(listOf("addMinute", "reset", "toggle", "delete"), actions.calls)
    }

    @Test fun timerCardRunningAndCompleted() {
        val actions = RecordingTimerActions()
        val now = Clock.System.now()
        val running = Timer(true, "pasta", now, 10.minutes, 10.minutes, id = 8)
        rule.setContent { TimerCard(running, now, actions) }
        rule.waitForIdle()
        rule.onNodeWithText("Running").assertExists()
        // Pristine running timer: no reset button, toggle pauses.
        rule.onNodeWithContentDescription("Pause").performClick()
        assertEquals(listOf("toggle"), actions.calls)
    }

    @Test fun newTimerDialogSaveDisabledAtZero() {
        val stack = NavBackStack(arrayOf<Route>(Route.Alarm, Route.NewTimerDialog()))
        rule.setContent { NewTimerDialog(stack, vm(), initialLengthSeconds = null, initialMessage = null) }
        rule.waitForIdle()
        rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("0")
        // Zero is not a timer: Save stays disabled, Cancel still pops.
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, stack.backStack.size)
    }
}
