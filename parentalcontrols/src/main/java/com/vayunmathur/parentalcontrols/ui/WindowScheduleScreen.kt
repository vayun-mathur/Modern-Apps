package com.vayunmathur.parentalcontrols.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
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
import com.vayunmathur.parentalcontrols.domain.TimeWindow
import com.vayunmathur.parentalcontrols.platform.SupervisableApp

/** Actions any supervised window screen can take. */
data class WindowScheduleActions(
    val onEnabledChange: (Boolean) -> Unit,
    val onStartChange: (Int, Int) -> Unit,
    val onEndChange: (Int, Int) -> Unit,
    val onToggleDay: (Int) -> Unit,
    val onAppAllowChange: (String, Boolean) -> Unit,
)

/**
 * A supervised window editor shared by downtime and school time.
 *
 * Same shape as the bedtime screen with different labels: master switch, start/end pickers,
 * day chips, and the per-app allow-list (apps that stay usable inside the window). Bedtime
 * keeps its own screen because it predates this one and uses block-list semantics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowScheduleScreen(
    title: String,
    enableLabel: String,
    enableHint: String,
    startLabel: String,
    endLabel: String,
    daysLabel: String,
    appsLabel: String,
    dayInitials: String,
    enabled: Boolean,
    startMinute: Int,
    endMinute: Int,
    daysMask: Int,
    apps: List<SupervisableApp>,
    loading: Boolean,
    actions: WindowScheduleActions,
) {
    var picking by remember { mutableStateOf<WindowPicking?>(null) }

    LazyListScaffold(
        title = title,
        horizontalPadding = 0.dp,
    ) {
        item {
            SettingsSection {
                SettingsSwitchRow(
                    title = enableLabel,
                    supportingText = enableHint,
                    checked = enabled,
                    onCheckedChange = actions.onEnabledChange,
                )
                SettingsRow(
                    title = startLabel,
                    supportingText = formatWindowMinute(startMinute),
                    enabled = enabled,
                    onClick = { picking = WindowPicking.Start },
                )
                SettingsRow(
                    title = endLabel,
                    supportingText = formatWindowMinute(endMinute),
                    enabled = enabled,
                    onClick = { picking = WindowPicking.End },
                )
            }
        }

        item {
            SettingsSection(title = daysLabel) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val labels = dayInitials.split(",")
                    for (day in 0 until TimeWindow.DAYS_IN_WEEK) {
                        val set = (daysMask shr day) and 1 == 1
                        FilterChip(
                            selected = set,
                            onClick = { actions.onToggleDay(day) },
                            enabled = enabled,
                            label = { Text(labels.getOrElse(day) { "?" }) },
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(title = appsLabel) {
                if (loading && apps.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Loading\u2026")
                    }
                }
            }
        }

        items(apps.size, key = { apps[it].packageName }) { index ->
            val app = apps[index]
            SettingsSwitchRow(
                title = app.label,
                checked = app.rule?.allowedInDowntime == true,
                enabled = enabled,
                onCheckedChange = { actions.onAppAllowChange(app.packageName, it) },
            )
        }
    }

    val active = picking
    if (active != null) {
        val initial = if (active == WindowPicking.Start) startMinute else endMinute
        val timeState = rememberTimePickerState(
            initialHour = initial / 60,
            initialMinute = initial % 60,
        )
        AlertDialog(
            onDismissRequest = { picking = null },
            title = {
                Text(if (active == WindowPicking.Start) startLabel else endLabel)
            },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    if (active == WindowPicking.Start) {
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

private enum class WindowPicking { Start, End }

private fun formatWindowMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
