package com.vayunmathur.clock.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.vayunmathur.clock.wear.domain.WatchStopwatch
import com.vayunmathur.clock.wear.domain.formatWatchStopwatch
import com.vayunmathur.clock.wear.domain.pauseWatchStopwatch
import com.vayunmathur.clock.wear.domain.startWatchStopwatch
import com.vayunmathur.clock.wear.domain.watchStopwatchElapsedMillis
import kotlinx.coroutines.delay

/** Watch-native stopwatch: dual-base elapsed, laps, persisted across process death. */
@Composable
fun WearStopwatchScreen(store: WearClockStore, onBack: () -> Unit) {
    val persisted by store.state.collectAsState()
    val stopwatch = persisted.stopwatch
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsedNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(stopwatch.running) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            elapsedNow = SystemClock.elapsedRealtime()
            if (!stopwatch.running) break
            delay(100)
        }
    }
    val elapsed = watchStopwatchElapsedMillis(stopwatch, nowMillis, elapsedNow)
    ScalingLazyColumn {
        item {
            Text(
                formatWatchStopwatch(elapsed),
                style = MaterialTheme.typography.display2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item {
            Chip(
                onClick = {
                    val wall = System.currentTimeMillis()
                    val mono = SystemClock.elapsedRealtime()
                    store.setStopwatch(
                        if (stopwatch.running) {
                            pauseWatchStopwatch(stopwatch, wall, mono)
                        } else {
                            startWatchStopwatch(stopwatch, wall, mono)
                        },
                    )
                },
                label = {
                    Text(
                        stringResource(
                            if (stopwatch.running) R.string.wear_pause else R.string.wear_start,
                        ),
                        maxLines = 1,
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        if (stopwatch.running) {
            item {
                Chip(
                    onClick = {
                        store.setStopwatch(stopwatch.copy(lapsMillis = stopwatch.lapsMillis + elapsed))
                    },
                    label = { Text(stringResource(R.string.wear_lap), maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        if (elapsed > 0 || stopwatch.lapsMillis.isNotEmpty()) {
            item {
                Chip(
                    onClick = { store.setStopwatch(WatchStopwatch()) },
                    label = { Text(stringResource(R.string.wear_reset), maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        items(stopwatch.lapsMillis.indices.reversed().toList(), key = { it }) { index ->
            val lap = stopwatch.lapsMillis[index]
            Text(
                formatWatchStopwatch(lap),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
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
