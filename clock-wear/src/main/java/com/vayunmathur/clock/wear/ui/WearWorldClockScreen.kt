package com.vayunmathur.clock.wear.ui

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
import com.vayunmathur.clock.wear.domain.WearWorldClock
import com.vayunmathur.clock.wear.domain.formatWearAlarmTime
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The phone's world-clock list, rendered in each city's own zone. Synced, display-only by nature. */
@Composable
fun WearWorldClockScreen(store: WearClockStore, onBack: () -> Unit) {
    val persisted by store.state.collectAsState()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(10_000)
        }
    }
    ScalingLazyColumn {
        item {
            Text(
                stringResource(R.string.wear_world_clock),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (persisted.snapshot.worldClocks.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_world_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            items(persisted.snapshot.worldClocks, key = { it.city }) { clock ->
                WearWorldClockRow(clock, nowMillis)
            }
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

/** One city row: its current time in its own timezone. */
@Composable
private fun WearWorldClockRow(clock: WearWorldClock, nowMillis: Long) {
    val zone = remember(clock.timezoneId) {
        runCatching { TimeZone.of(clock.timezoneId) }.getOrDefault(TimeZone.currentSystemDefault())
    }
    val local = remember(nowMillis, zone) {
        Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone)
    }
    Chip(
        onClick = {},
        label = { Text(clock.city, maxLines = 1) },
        secondaryLabel = {
            Text(formatWearAlarmTime(local.hour, local.minute), maxLines = 1)
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}
