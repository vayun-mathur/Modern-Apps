package com.vayunmathur.findfamily.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DatePicker
import com.vayunmathur.library.ui.DatePickerDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TimePicker
import com.vayunmathur.library.ui.rememberDatePickerState
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.platform.PersonActions
import java.time.Instant as JavaInstant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Instant
import com.vayunmathur.library.ui.R as UiR

/**
 * No-show watch dialog for one person (issue 702): pick the expected place
 * (a saved waypoint, or any location update), the expected date/time, and a
 * grace period. Saving calls [PersonActions.setNoShowAlert], which persists
 * the row and schedules its one-shot check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoShowAlertDialog(
    user: User,
    waypoints: List<Waypoint>,
    actions: PersonActions,
    onDismiss: () -> Unit,
) {
    val namedWaypoints = remember(waypoints) { waypoints.filter { it.name.isNotBlank() } }
    var waypointId: Long? by remember { mutableStateOf(null) }
    var placeExpanded by remember { mutableStateOf(false) }
    var dateMillis: Long? by remember { mutableStateOf(null) }
    var hour: Int? by remember { mutableStateOf(null) }
    var minute: Int? by remember { mutableStateOf(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var graceExpanded by remember { mutableStateOf(false) }
    var graceMinutes by remember { mutableIntStateOf(30) }
    val messenger = rememberMessenger()

    val anyPlaceLabel = stringResource(R.string.noshow_place_any)
    val selectedPlaceLabel = waypointId
        ?.let { id -> namedWaypoints.firstOrNull { it.id == id }?.name }
        ?: anyPlaceLabel
    // Date/time assembly below uses java.time with the device zone; the dialog
    // only stores raw millis/hour/minute until both halves are chosen.
    val dateLabel = dateMillis?.let { millis ->
        JavaInstant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    } ?: stringResource(R.string.noshow_pick_date)
    val timeLabel = if (hour != null && minute != null) {
        String.format(Locale.ROOT, "%02d:%02d", hour, minute)
    } else {
        stringResource(R.string.noshow_pick_time)
    }
    val expectedAt: Instant? = remember(dateMillis, hour, minute) {
        val millis = dateMillis
        val h = hour
        val m = minute
        if (millis == null || h == null || m == null) {
            null
        } else {
            val date = JavaInstant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            val zoned = LocalDate.of(date.year, date.month, date.dayOfMonth)
                .atTime(LocalTime.of(h, m))
                .atZone(ZoneId.systemDefault())
            Instant.fromEpochMilliseconds(zoned.toInstant().toEpochMilli())
        }
    }
    val graceOptions = remember { listOf(0, 15, 30, 60, 120) }
    val scheduledMessage = stringResource(R.string.noshow_scheduled)
    val resources = LocalResources.current
    fun graceLabel(minutesValue: Int): String =
        resources.getQuantityString(R.plurals.noshow_grace_minutes, minutesValue, minutesValue)

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.noshow_title, user.name),
                    style = MaterialTheme.typography.headlineMedium,
                )

                // Expected place: "Anywhere" plus one entry per named saved place,
                // mirroring the arrival-trigger dropdown in AutoToggleRow.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.noshow_place_label),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Box {
                        OutlinedTextField(
                            selectedPlaceLabel, {},
                            interactionSource = interactionSourceClickable { placeExpanded = true },
                            readOnly = true,
                            modifier = Modifier.width(180.dp),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = placeExpanded)
                            },
                        )
                        DropdownMenu(placeExpanded, { placeExpanded = false }) {
                            SelectableDropdownMenuItem(
                                selected = waypointId == null,
                                onClick = {
                                    placeExpanded = false
                                    waypointId = null
                                },
                                text = { Text(anyPlaceLabel) },
                                selectedLeadingIcon = { IconCheck() },
                            )
                            namedWaypoints.forEach { waypoint ->
                                SelectableDropdownMenuItem(
                                    selected = waypointId == waypoint.id,
                                    onClick = {
                                        placeExpanded = false
                                        waypointId = waypoint.id
                                    },
                                    text = { Text(waypoint.name) },
                                    selectedLeadingIcon = { IconCheck() },
                                )
                            }
                        }
                    }
                }

                // Expected date + time rows, opening the shared pickers.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        { showDatePicker = true },
                        Modifier.weight(1f),
                    ) {
                        Text(dateLabel)
                    }
                    OutlinedButton(
                        { showTimePicker = true },
                        Modifier.weight(1f),
                    ) {
                        Text(timeLabel)
                    }
                }

                // Grace period dropdown.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.noshow_grace_label),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Box {
                        OutlinedTextField(
                            graceLabel(graceMinutes), {},
                            interactionSource = interactionSourceClickable { graceExpanded = true },
                            readOnly = true,
                            modifier = Modifier.width(180.dp),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = graceExpanded)
                            },
                        )
                        DropdownMenu(graceExpanded, { graceExpanded = false }) {
                            graceOptions.forEach { option ->
                                SelectableDropdownMenuItem(
                                    selected = graceMinutes == option,
                                    onClick = {
                                        graceExpanded = false
                                        graceMinutes = option
                                    },
                                    text = { Text(graceLabel(option)) },
                                    selectedLeadingIcon = { IconCheck() },
                                )
                            }
                        }
                    }
                }

                Button(
                    {
                        val at = expectedAt ?: return@Button
                        actions.setNoShowAlert(user, waypointId, at, graceMinutes)
                        messenger.show(scheduledMessage)
                        onDismiss()
                    },
                    enabled = expectedAt != null,
                ) {
                    Text(stringResource(R.string.noshow_save))
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(dateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                Button(
                    onClick = {
                        dateMillis = pickerState.selectedDateMillis
                        showDatePicker = false
                    },
                    enabled = pickerState.selectedDateMillis != null,
                ) {
                    Text(stringResource(UiR.string.ok))
                }
            },
            dismissButton = {
                Button(onClick = { showDatePicker = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            },
        ) {
            DatePicker(pickerState)
        }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = hour ?: 12,
            initialMinute = minute ?: 0,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                Button(
                    onClick = {
                        hour = pickerState.hour
                        minute = pickerState.minute
                        showTimePicker = false
                    },
                ) {
                    Text(stringResource(UiR.string.ok))
                }
            },
            dismissButton = {
                Button(onClick = { showTimePicker = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            text = { TimePicker(pickerState) },
        )
    }
}
