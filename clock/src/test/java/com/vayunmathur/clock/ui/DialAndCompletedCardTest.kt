package com.vayunmathur.clock.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.platform.StopwatchActions
import com.vayunmathur.clock.platform.StopwatchUiState
import com.vayunmathur.clock.platform.TimerActions
import com.vayunmathur.clock.ui.components.TimerCard
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The stopwatch dial's canvas and a completed timer card, drawn for real. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialAndCompletedCardTest {


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

    @Test fun stopwatchDialDrawsWithLaps() {
        val actions = object : StopwatchActions {
            val calls = mutableListOf<String>()
            override fun toggleStopwatch() { calls += "toggle" }
            override fun addLap() { calls += "lap" }
        }
        val state = StopwatchUiState(
            isRunning = true,
            countingTime = 90.seconds,
            lapTimes = listOf(30.seconds, 65.seconds, 90.seconds),
        )
        rule.setContent { StopwatchScreen(state, actions) }
        rule.waitForIdle()
        // Forcing a frame runs the dial's canvas (ticks, hands, lap markers).
        rule.onRoot().captureToImage()
        rule.onNodeWithContentDescription("Timer").performClick() // lap button
        assertEquals(listOf("lap"), actions.calls)
    }

    @Test fun completedTimerCardRestarts() {
        val calls = mutableListOf<String>()
        val actions = object : TimerActions {
            override fun toggle(timer: Timer) { calls += "toggle" }
            override fun delete(timer: Timer) { calls += "delete" }
            override fun reset(timer: Timer) { calls += "reset" }
        }
        val now = Clock.System.now()
        val finished = Timer(false, "done", now, 0.seconds, 5.minutes, id = 3)
        rule.setContent { TimerCard(finished, now, actions) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        // Completed: no +1min; a Reset button and the restart FAB both show.
        rule.onNodeWithText("Reset").performClick()
        rule.onAllNodesWithContentDescription("Restart")[1].performClick()
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(listOf("reset", "toggle", "delete"), calls)
    }
}
