package com.vayunmathur.clock.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.clock.wear.R
import com.vayunmathur.clock.wear.data.WearClockStore
import com.vayunmathur.clock.wear.domain.WATCH_TIMER_PRESETS_MILLIS
import com.vayunmathur.clock.wear.domain.WatchTimer
import com.vayunmathur.clock.wear.domain.formatWatchDuration
import com.vayunmathur.clock.wear.domain.pauseWatchTimer
import com.vayunmathur.clock.wear.domain.startWatchTimer
import com.vayunmathur.clock.wear.domain.watchTimerRemainingMillis
import com.vayunmathur.clock.wear.platform.WatchTimerScheduler
import kotlinx.coroutines.delay

/**
 * Watch-native timers: presets start a real countdown on this watch, armed
 * with its AlarmManager — it rings here even with the phone absent. A timer
 * is only shown running after the scheduler accepted it.
 */
@Composable
fun WearTimersScreen(store: WearClockStore, onBack: () -> Unit) {
    val persisted by store.state.collectAsState()
    val context = LocalContext.current
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsedNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var note by remember { mutableStateOf<String?>(null) }
    val scheduleFailedText = stringResource(R.string.wear_timer_schedule_failed)
    LaunchedEffect(persisted.timers) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            elapsedNow = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    ScalingLazyColumn {
        item {
            Text(
                stringResource(R.string.wear_timers),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        note?.let { text ->
            item {
                Text(
                    text,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
        items(persisted.timers, key = { it.id }) { timer ->
            WearTimerRow(
                timer = timer,
                remainingMillis = watchTimerRemainingMillis(timer, nowMillis, elapsedNow),
                onPause = {
                    WatchTimerScheduler.cancel(context, timer.id)
                    store.upsertTimer(pauseWatchTimer(timer, nowMillis, elapsedNow))
                },
                onResume = {
                    val started = startWatchTimer(timer, System.currentTimeMillis(), SystemClock.elapsedRealtime())
                    if (WatchTimerScheduler.schedule(context, started)) {
                        store.upsertTimer(started)
                        note = null
                    } else {
                        note = scheduleFailedText
                    }
                },
                onDelete = {
                    WatchTimerScheduler.cancel(context, timer.id)
                    store.removeTimer(timer.id)
                },
            )
        }
        item {
            Text(
                stringResource(R.string.wear_timer_presets),
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        items(WATCH_TIMER_PRESETS_MILLIS, key = { it }) { preset ->
            Chip(
                onClick = {
                    val id = System.nanoTime() / 1_000_000L
                    val timer = startWatchTimer(
                        WatchTimer(
                            id = id,
                            label = formatWatchDuration(preset),
                            totalMillis = preset,
                            remainingMillis = preset,
                        ),
                        System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(),
                    )
                    if (WatchTimerScheduler.schedule(context, timer)) {
                        store.upsertTimer(timer)
                        note = null
                    } else {
                        note = scheduleFailedText
                    }
                },
                label = { Text(formatWatchDuration(preset), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = onBack,
                label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

/** One timer row with its live remainder and pause/resume/delete controls. */
@Composable
private fun WearTimerRow(
    timer: WatchTimer,
    remainingMillis: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = when {
        timer.finished -> stringResource(R.string.wear_timer_finished)
        timer.running -> formatWatchDuration(remainingMillis)
        else -> "${formatWatchDuration(remainingMillis)} · ${stringResource(R.string.wear_paused)}"
    }
    Chip(
        onClick = if (timer.running) onPause else onResume,
        label = { Text(status, maxLines = 1) },
        secondaryLabel = {
            Text(
                if (timer.running) stringResource(R.string.wear_pause)
                else stringResource(R.string.wear_resume),
                maxLines = 1,
            )
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
    Chip(
        onClick = onDelete,
        label = { Text(stringResource(R.string.wear_delete), maxLines = 1) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}
