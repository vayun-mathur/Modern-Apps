package com.vayunmathur.todo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconAccessTime
import com.vayunmathur.library.ui.IconCalendar
import com.vayunmathur.library.ui.IconToday
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Text
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.DueShortcuts
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/**
 * Due date and optional time: one-tap shortcuts, a date picker and a time picker. For a
 * [repeating] task this is the next occurrence, which can't be cleared.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DueDateSheet(
    dueAt: Long?,
    allDay: Boolean,
    repeating: Boolean,
    onSet: (dueAt: Long?, allDay: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = TimeZone.currentSystemDefault()
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val time = dueAt?.takeIf { !allDay }?.let { DueDates.localTime(it, zone) }

    fun setDate(date: LocalDate?) {
        if (date == null) return onSet(null, true)
        onSet(if (time != null) DueDates.at(date, time, zone) else DueDates.startOfDay(date, zone), time == null)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(if (repeating) R.string.next_occurrence else R.string.add_due_date),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DueShortcuts.entries.filter { !repeating || it != DueShortcuts.NONE }.forEach { shortcut ->
                val target = shortcut.dueAt()
                val selected = target?.let { t -> dueAt?.let { DueDates.localDate(it, zone) == DueDates.localDate(t, zone) } }
                    ?: (dueAt == null)
                FilterChip(
                    selected = selected,
                    onClick = { setDate(target?.let { DueDates.localDate(it, zone) }) },
                    label = { Text(stringResource(shortcutLabel(shortcut))) },
                )
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.pick_date)) },
            leadingContent = { IconCalendar() },
            modifier = Modifier.clickable { pickDate = true },
        )
        ListItem(
            headlineContent = { Text(stringResource(if (time == null) R.string.add_time else R.string.change_time)) },
            supportingContent = time?.let { t -> { Text(dueLabel(dueAt, false, System.currentTimeMillis())) } },
            leadingContent = { IconAccessTime() },
            modifier = Modifier.clickable { pickTime = true },
        )
        if (time != null) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.all_day)) },
                leadingContent = { IconToday() },
                modifier = Modifier.clickable { onSet(DueDates.startOfDay(DueDates.localDate(dueAt, zone), zone), true) },
            )
        }
    }

    if (pickDate) {
        TodoDatePickerDialog(initial = dueAt, onConfirm = {
            setDate(DueDates.localDate(it, zone))
            pickDate = false
        }, onDismiss = { pickDate = false })
    }
    if (pickTime) {
        TodoTimePickerDialog(initial = time, onConfirm = { t: LocalTime ->
            val date = dueAt?.let { DueDates.localDate(it, zone) } ?: DueDates.localDate(System.currentTimeMillis(), zone)
            onSet(DueDates.at(date, t, zone), false)
            pickTime = false
        }, onDismiss = { pickTime = false })
    }
}

internal fun shortcutLabel(shortcut: DueShortcuts): Int = when (shortcut) {
    DueShortcuts.TODAY -> R.string.bucket_today
    DueShortcuts.TOMORROW -> R.string.bucket_tomorrow
    DueShortcuts.NEXT_WEEK -> R.string.next_week
    DueShortcuts.NONE -> R.string.bucket_no_date
}
