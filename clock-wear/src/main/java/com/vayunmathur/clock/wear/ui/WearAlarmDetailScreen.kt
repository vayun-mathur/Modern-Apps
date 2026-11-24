package com.vayunmathur.clock.wear.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.clock.wear.R
import com.vayunmathur.clock.wear.data.WearClockStore
import com.vayunmathur.clock.wear.domain.DisplayOnlyReason
import com.vayunmathur.clock.wear.domain.WearAlarmDisplayState
import com.vayunmathur.clock.wear.domain.formatWearAlarmDays
import com.vayunmathur.clock.wear.domain.formatWearAlarmTime
import com.vayunmathur.clock.wear.domain.resolveWearAlarmDisplay
import com.vayunmathur.clock.wear.platform.WatchAlarmScheduler
import com.vayunmathur.clock.wear.sync.WearLinkBridge
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** One synced alarm: its time and exactly what the watch will (or will not) do for it. */
@Composable
fun WearAlarmDetailScreen(store: WearClockStore, alarmId: Long, onBack: () -> Unit) {
    val persisted by store.state.collectAsState()
    val context = LocalContext.current
    val alarm = persisted.snapshot.alarms.firstOrNull { it.alarmId == alarmId }
    val nowMillis = System.currentTimeMillis()
    val state = alarm?.let {
        resolveWearAlarmDisplay(
            it,
            persisted.armedRecords.firstOrNull { record -> record.alarmId == it.alarmId },
            persisted.armFailures.firstOrNull { failure -> failure.alarmId == it.alarmId },
            nowMillis,
        )
    }
    ScalingLazyColumn {
        item {
            Text(
                stringResource(R.string.wear_alarms),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (alarm == null) {
            item {
                Text(
                    stringResource(R.string.wear_alarm_gone),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            item {
                Text(
                    formatWearAlarmTime(alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.display2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
            item {
                val detail = listOf(alarm.label, formatWearAlarmDays(alarm.daysMask))
                    .filter { it.isNotBlank() }.joinToString(" · ")
                Text(
                    detail,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
            item {
                Text(
                    wearAlarmStateText(requireNotNull(state)),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (state is WearAlarmDisplayState.Armed) {
                item {
                    val next = Instant.fromEpochMilliseconds(state.nextTriggerMillis)
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                    Text(
                        stringResource(
                            R.string.wear_next_ring,
                            "${next.date} ${formatWearAlarmTime(next.hour, next.minute)}",
                        ),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
            }
            if (alarm.enabled && state is WearAlarmDisplayState.DisplayOnly) {
                item {
                    Chip(
                        onClick = { WearLinkBridge.rearmFromStore(context) },
                        label = { Text(stringResource(R.string.wear_rearm), maxLines = 1) },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
                if (!WatchAlarmScheduler.canScheduleExact(context)) {
                    item {
                        Chip(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        },
                                    )
                                }
                            },
                            label = {
                                Text(stringResource(R.string.wear_exact_settings), maxLines = 1)
                            },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        )
                    }
                }
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

@Composable
private fun wearAlarmStateText(state: WearAlarmDisplayState): String = when (state) {
    is WearAlarmDisplayState.Armed ->
        stringResource(if (state.exact) R.string.wear_armed_detail else R.string.wear_armed_inexact_detail)
    is WearAlarmDisplayState.DisplayOnly -> when (state.reason) {
        DisplayOnlyReason.DISABLED_ON_PHONE -> stringResource(R.string.wear_alarm_off)
        DisplayOnlyReason.NOT_YET_ARMED -> stringResource(R.string.wear_not_armed_detail)
        DisplayOnlyReason.SCHEDULING_FAILED -> stringResource(R.string.wear_arm_failed_detail)
    }
}
