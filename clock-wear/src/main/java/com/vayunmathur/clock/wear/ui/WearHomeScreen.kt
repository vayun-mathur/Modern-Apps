package com.vayunmathur.clock.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.clock.wear.R
import com.vayunmathur.clock.wear.domain.formatWearAlarmTime
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Round home: the watch's own time plus the four clock functions. */
@Composable
fun WearHomeScreen(onOpen: (WearRoute) -> Unit) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val local = remember(nowMillis) {
        Instant.fromEpochMilliseconds(nowMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    }
    ScalingLazyColumn {
        item {
            Text(
                formatWearAlarmTime(local.hour, local.minute),
                style = MaterialTheme.typography.display1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item {
            WearMenuChip(stringResource(R.string.wear_world_clock)) { onOpen(WearRoute.World) }
        }
        item {
            WearMenuChip(stringResource(R.string.wear_alarms)) { onOpen(WearRoute.Alarms) }
        }
        item {
            WearMenuChip(stringResource(R.string.wear_timers)) { onOpen(WearRoute.Timers) }
        }
        item {
            WearMenuChip(stringResource(R.string.wear_stopwatch)) { onOpen(WearRoute.Stopwatch) }
        }
    }
}

/** One home-menu row. */
@Composable
private fun WearMenuChip(label: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}
