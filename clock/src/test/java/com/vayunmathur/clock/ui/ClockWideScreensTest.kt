package com.vayunmathur.clock.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.platform.StopwatchActions
import com.vayunmathur.clock.platform.StopwatchUiState
import com.vayunmathur.clock.platform.TimerActions
import com.vayunmathur.clock.platform.TimerUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock

/** Expanded-width layouts: side-by-side stopwatch dial/laps and paired timer rows. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1000dp-h800dp")
class ClockWideScreensTest {

    @get:Rule val rule = createComposeRule()

    @Test fun stopwatchExpandedLayoutShowsDialAndLaps() {
        rule.setContent {
            StopwatchScreen(
                StopwatchUiState(isRunning = false, countingTime = 65.seconds, lapTimes = listOf(30.seconds, 65.seconds)),
                StopwatchActions.Noop,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Laps").assertExists()
        rule.onNodeWithText("# 1").assertExists()
    }

    @Test fun timerExpandedLayoutPairsCards() {
        val now = Clock.System.now()
        val timers = listOf(
            Timer(false, "tea", now, 4.minutes, 5.minutes, id = 1),
            Timer(true, "pasta", now, 9.minutes, 10.minutes, id = 2),
            Timer(false, "rice", now, 2.minutes, 3.minutes, id = 3),
        )
        rule.setContent { TimerScreen(TimerUiState(now = { now }, timers = timers), TimerActions.Noop) }
        rule.waitForIdle()
        rule.onNodeWithText("tea").assertExists()
        rule.onNodeWithText("pasta").assertExists()
        rule.onNodeWithText("rice").assertExists()
        assertTrue(timers.size == 3)
    }
}
