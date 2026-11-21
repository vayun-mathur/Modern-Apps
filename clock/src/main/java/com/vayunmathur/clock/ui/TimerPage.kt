package com.vayunmathur.clock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.ui.components.sendTimerNotification
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.platform.TimerActions
import com.vayunmathur.clock.platform.TimerUiState
import com.vayunmathur.library.ui.rememberClock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Binds [ClockViewModel] to the stateless [TimerScreen]. */
@Composable
fun TimerPage(clockViewModel: ClockViewModel) {
    // Countdowns are shown to the second, so a one-second tick is enough; the ViewModel's 100ms
    // flow exists for the stopwatch's centiseconds. Read inside each timer's row, not here.
    val ticker = rememberClock()
    val now = remember(ticker) { { Instant.fromEpochMilliseconds(ticker()) } }
    val timers by clockViewModel.timers.collectAsState()
    val context = LocalContext.current

    val actions = remember(clockViewModel, context) {
        object : TimerActions {
            override fun start(duration: Duration, name: String) {
                val timer = Timer(true, name, Clock.System.now(), duration, duration, remainingStartElapsedRealtime = com.vayunmathur.clock.platform.DualTimeBase.elapsedRealtimeMs())
                clockViewModel.upsert(timer) { sendTimerNotification(context, timer.copy(id = it), true) }
            }
            override fun delete(timer: Timer) { sendTimerNotification(context, timer, false); clockViewModel.delete(timer) }
            override fun addMinute(timer: Timer) {
                val updated = timer.copy(remainingLength = timer.remainingLength + 1.minutes, totalLength = timer.totalLength + 1.minutes)
                clockViewModel.upsert(updated)
                if (timer.isRunning) sendTimerNotification(context, updated, true)
            }
            override fun reset(timer: Timer) {
                clockViewModel.upsert(timer.copy(isRunning = false, remainingLength = timer.totalLength, remainingStartTime = Clock.System.now(), remainingStartElapsedRealtime = null))
                sendTimerNotification(context, timer, false)
            }
            override fun toggle(timer: Timer) {
                when {
                    !timer.isRunning && timer.remainingLength <= Duration.ZERO -> {
                        val restarted = timer.started().copy(remainingLength = timer.totalLength)
                        clockViewModel.upsert(restarted); sendTimerNotification(context, restarted, true)
                    }
                    timer.isRunning -> { clockViewModel.upsert(timer.stopped()); sendTimerNotification(context, timer, false) }
                    else -> { val started = timer.started(); clockViewModel.upsert(started); sendTimerNotification(context, started, true) }
                }
            }
        }
    }

    TimerScreen(state = TimerUiState(now = now, timers = timers), actions = actions)
}
