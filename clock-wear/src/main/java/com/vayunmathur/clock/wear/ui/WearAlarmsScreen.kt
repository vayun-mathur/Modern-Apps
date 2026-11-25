package com.vayunmathur.clock.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.vayunmathur.clock.wear.domain.WearAlarmDisplayState
import com.vayunmathur.clock.wear.domain.WearClockAlarm
import com.vayunmathur.clock.wear.domain.formatWearAlarmDays
import com.vayunmathur.clock.wear.domain.formatWearAlarmTime
import com.vayunmathur.clock.wear.domain.resolveWearAlarmDisplay
import com.vayunmathur.clock.wear.sync.WearLinkBridge
import kotlinx.coroutines.launch

/**
 * Synced alarms. A row says "Armed on watch" only when the store holds
 * arming proof for it; everything else is labelled display-only with its
 * reason — the phone's enabled flag alone never earns the armed label.
 */
@Composable
fun WearAlarmsScreen(store: WearClockStore, onBack: () -> Unit, onOpenAlarm: (Long) -> Unit) {
    val persisted by store.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nowMillis = remember(persisted) { System.currentTimeMillis() }
    WearAlarmsContent(
        persisted = persisted,
        nowMillis = nowMillis,
        onRequestSync = { scope.launch { WearLinkBridge.requestSync(context) } },
        onBack = onBack,
        onOpenAlarm = onOpenAlarm,
    )
}

/**
 * Stateless alarms body behind [WearAlarmsScreen]: snapshot and instant are
 * parameters, so Compose previews (src/screenshotTest) render literal fixtures
 * at a fixed time with no store or phone.
 */
@Composable
internal fun WearAlarmsContent(
    persisted: com.vayunmathur.clock.wear.data.WearClockPersisted,
    nowMillis: Long,
    onRequestSync: () -> Unit,
    onBack: () -> Unit,
    onOpenAlarm: (Long) -> Unit,
) {
    ScalingLazyColumn {
        item {
            Text(
                stringResource(R.string.wear_alarms),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (persisted.snapshot.alarms.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_alarms_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            items(persisted.snapshot.alarms, key = { it.alarmId }) { alarm ->
                val state = resolveWearAlarmDisplay(
                    alarm,
                    persisted.armedRecords.firstOrNull { it.alarmId == alarm.alarmId },
                    persisted.armFailures.firstOrNull { it.alarmId == alarm.alarmId },
                    nowMillis,
                )
                WearAlarmChip(alarm, state) { onOpenAlarm(alarm.alarmId) }
            }
        }
        item {
            Chip(
                onClick = onRequestSync,
                label = { Text(stringResource(R.string.wear_request_sync), maxLines = 1) },
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

/** One alarm row: time + label, and its honest armed/display-only status. */
@Composable
private fun WearAlarmChip(
    alarm: WearClockAlarm,
    state: WearAlarmDisplayState,
    onClick: () -> Unit,
) {
    val status = when (state) {
        is WearAlarmDisplayState.Armed ->
            stringResource(if (state.exact) R.string.wear_armed else R.string.wear_armed_inexact)
        is WearAlarmDisplayState.DisplayOnly -> when (state.reason) {
            com.vayunmathur.clock.wear.domain.DisplayOnlyReason.DISABLED_ON_PHONE ->
                stringResource(R.string.wear_alarm_off)
            else -> stringResource(R.string.wear_display_only)
        }
    }
    Chip(
        onClick = onClick,
        label = {
            Text(
                "${formatWearAlarmTime(alarm.hour, alarm.minute)} · $status",
                maxLines = 1,
            )
        },
        secondaryLabel = {
            val detail = listOf(alarm.label, formatWearAlarmDays(alarm.daysMask))
                .filter { it.isNotBlank() }.joinToString(" · ")
            Text(detail, maxLines = 1)
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}
