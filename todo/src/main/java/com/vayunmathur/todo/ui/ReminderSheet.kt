package com.vayunmathur.todo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.Reminders
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Reminder relative to the due time, or at a chosen moment. A repeating task's reminder keeps
 * its lead for every occurrence, so it only offers a time of day; a one-time task gets a full
 * date and time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSheet(todo: Todo, onSet: (Long?) -> Unit, onDismiss: () -> Unit) {
    val dueAt = todo.dueAt
    val allDay = todo.dueAllDay
    val reminderAt = todo.reminderAt
    val repeating = todo.recurrence != null
    val zone = TimeZone.currentSystemDefault()
    val requestPermission = rememberNotificationPermissionRequest()
    LaunchedEffect(Unit) { requestPermission() }
    var pickDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(if (repeating) R.string.remind_each_time else R.string.reminder),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val option: @Composable (String, Long?) -> Unit = { label, value ->
            ListItem(
                headlineContent = { Text(label) },
                trailingContent = if (value == reminderAt) ({ IconCheck() }) else null,
                modifier = Modifier.clickable { onSet(value) },
            )
        }
        option(stringResource(R.string.no_reminder), null)
        if (dueAt != null) {
            Reminders.offsetsFor(allDay).forEach { offset ->
                option(offsetLabel(offset, allDay), Reminders.at(dueAt, allDay, offset, zone))
            }
        }
        val is24 = rememberIs24Hour()
        ListItem(
            headlineContent = { Text(stringResource(if (repeating) R.string.pick_time else R.string.pick_date_time)) },
            supportingContent = reminderAt?.let { at ->
                {
                    Text(
                        if (repeating) DateString.time(DueDates.localTime(at, zone), is24) else dueLabel(at, false, System.currentTimeMillis()),
                    )
                }
            },
            modifier = Modifier.clickable {
                if (repeating) {
                    pickedDate = DueDates.localDate(dueAt ?: System.currentTimeMillis(), zone)
                } else {
                    pickDate = true
                }
            },
        )
    }

    if (pickDate) {
        TodoDatePickerDialog(initial = reminderAt ?: dueAt, onConfirm = {
            pickedDate = DueDates.localDate(it, zone)
            pickDate = false
        }, onDismiss = { pickDate = false })
    }
    pickedDate?.let { date ->
        TodoTimePickerDialog(initial = reminderAt?.let { DueDates.localTime(it, zone) }, onConfirm = {
            onSet(DueDates.at(date, it, zone))
            pickedDate = null
        }, onDismiss = { pickedDate = null })
    }
}

@Composable
private fun offsetLabel(offset: Reminders.Offset, allDay: Boolean): String {
    if (allDay) {
        val time = DateString.time(Reminders.ALL_DAY_TIME, rememberIs24Hour())
        return if (offset == Reminders.Offset.DAY_1) {
            stringResource(R.string.reminder_day_before_at, time)
        } else {
            stringResource(R.string.reminder_on_the_day, time)
        }
    }
    return when (offset) {
        Reminders.Offset.AT_DUE -> stringResource(R.string.reminder_at_due)
        Reminders.Offset.MINUTES_10 -> pluralStringResource(R.plurals.reminder_minutes_before, 10, 10)
        Reminders.Offset.HOUR_1 -> pluralStringResource(R.plurals.reminder_hours_before, 1, 1)
        Reminders.Offset.DAY_1 -> pluralStringResource(R.plurals.reminder_days_before, 1, 1)
    }
}
