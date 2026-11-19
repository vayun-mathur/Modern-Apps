package com.vayunmathur.parentalcontrols.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.parentalcontrols.R
import com.vayunmathur.parentalcontrols.data.BedtimeSchedule
import com.vayunmathur.parentalcontrols.platform.SupervisionUiState

/** Actions the bedtime screen can take. */
data class BedtimeActions(
    val onEnabledChange: (Boolean) -> Unit,
    val onStartChange: (Int, Int) -> Unit,
    val onEndChange: (Int, Int) -> Unit,
    val onToggleDay: (Int) -> Unit,
    val onAppBedtimeChange: (String, Boolean) -> Unit,
)

/** The nightly window, the days it applies to, and which apps it covers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BedtimeScreen(state: SupervisionUiState, actions: BedtimeActions) {
    var picking by remember { mutableStateOf<Picking?>(null) }
    val schedule = state.schedule

    LazyListScaffold(
        title = stringResource(R.string.bedtime_title),
        horizontalPadding = 0.dp,
    ) {
        item {
            SettingsSection {
                SettingsSwitchRow(
                    title = stringResource(R.string.bedtime_enable),
                    supportingText = stringResource(R.string.bedtime_enable_hint),
                    checked = schedule.enabled,
                    onCheckedChange = actions.onEnabledChange,
                )
                SettingsRow(
                    title = stringResource(R.string.schedule_start),
                    supportingText = formatMinute(schedule.startMinute),
                    enabled = schedule.enabled,
                    onClick = { picking = Picking.Start },
                )
                SettingsRow(
                    title = stringResource(R.string.schedule_end),
                    supportingText = formatMinute(schedule.endMinute),
                    enabled = schedule.enabled,
                    onClick = { picking = Picking.End },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.schedule_days)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val labels = stringResource(R.string.bedtime_day_initials).split(",")
                    for (day in 0 until BedtimeSchedule.DAYS_IN_WEEK) {
                        FilterChip(
                            selected = schedule.isDaySet(day),
                            onClick = { actions.onToggleDay(day) },
                            enabled = schedule.enabled,
                            label = { Text(labels.getOrElse(day) { "?" }) },
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bedtime_apps)) {
                if (state.apps.isEmpty()) {
                    SettingsRow(title = stringResource(R.string.apps_loading))
                }
            }
        }

        items(state.apps.size, key = { state.apps[it].packageName }) { index ->
            val app = state.apps[index]
            SettingsSwitchRow(
                title = app.label,
                checked = app.rule?.blockedAtBedtime == true,
                enabled = schedule.enabled,
                onCheckedChange = { actions.onAppBedtimeChange(app.packageName, it) },
            )
        }
    }

    val active = picking
    if (active != null) {
        val initial = if (active == Picking.Start) schedule.startMinute else schedule.endMinute
        val timeState = rememberTimePickerState(
            initialHour = initial / 60,
            initialMinute = initial % 60,
        )
        AlertDialog(
            onDismissRequest = { picking = null },
            title = {
                Text(
                    stringResource(
                        if (active == Picking.Start) R.string.schedule_start else R.string.schedule_end,
                    ),
                )
            },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    if (active == Picking.Start) {
                        actions.onStartChange(timeState.hour, timeState.minute)
                    } else {
                        actions.onEndChange(timeState.hour, timeState.minute)
                    }
                    picking = null
                }) { Text(stringResource(UiR.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) {
                    Text(stringResource(UiR.string.cancel))
                }
            },
        )
    }
}

private enum class Picking { Start, End }

private fun formatMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
