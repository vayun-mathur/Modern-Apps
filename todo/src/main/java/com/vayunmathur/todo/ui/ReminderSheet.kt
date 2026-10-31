package com.vayunmathur.todo.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Text
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.Reminders
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Reminder relative to the due time, or at an exact date and time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSheet(
    dueAt: Long?,
    allDay: Boolean,
    reminderAt: Long?,
    onSet: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = TimeZone.currentSystemDefault()
    var pickDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        val option: @Composable (String, Long?) -> Unit = { label, value ->
            ListItem(
                headlineContent = { Text(label) },
                trailingContent = if (value == reminderAt) ({ IconCheck() }) else null,
                modifier = Modifier.clickable { onSet(value) },
            )
        }
        option(stringResource(R.string.no_reminder), null)
        if (dueAt != null) {
            Reminders.Offset.entries.forEach { offset ->
                option(offsetLabel(offset), Reminders.at(dueAt, allDay, offset, zone))
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.pick_date_time)) },
            supportingContent = reminderAt?.let { { Text(dueLabel(it, false, System.currentTimeMillis())) } },
            modifier = Modifier.clickable { pickDate = true },
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
private fun offsetLabel(offset: Reminders.Offset): String = when (offset) {
    Reminders.Offset.AT_DUE -> stringResource(R.string.reminder_at_due)
    Reminders.Offset.MINUTES_10 -> pluralStringResource(R.plurals.reminder_minutes_before, 10, 10)
    Reminders.Offset.HOUR_1 -> pluralStringResource(R.plurals.reminder_hours_before, 1, 1)
    Reminders.Offset.DAY_1 -> pluralStringResource(R.plurals.reminder_days_before, 1, 1)
}
