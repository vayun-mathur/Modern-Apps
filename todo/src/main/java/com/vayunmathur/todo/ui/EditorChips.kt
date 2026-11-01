package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconAlarm
import com.vayunmathur.library.ui.IconEvent
import com.vayunmathur.library.ui.IconFlag
import com.vayunmathur.library.ui.IconHourglass
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconLocalOffer
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconRepeat
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.Recurrence
import com.vayunmathur.todo.platform.TodoActions
import com.vayunmathur.todo.platform.TodoUiState
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil

internal enum class EditorSheet { DUE, REPEAT, REMINDER, LIST, TAGS, START, BLOCKED }

/**
 * A task's properties, grouped: whether it's one-time or repeating, then its schedule, how it's
 * organized, and rarer details. Set properties show as selected chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorChips(state: TodoUiState, actions: TodoActions, modifier: Modifier = Modifier) {
    val todo = state.todo
    var sheet by remember { mutableStateOf<EditorSheet?>(null) }
    val icon = Modifier.size(18.dp)
    val chip: @Composable (Boolean, String, @Composable () -> Unit, () -> Unit) -> Unit = { on, label, lead, click ->
        FilterChip(selected = on, onClick = click, label = { Text(label) }, leadingIcon = lead)
    }
    val recurrence = Recurrence.parse(todo.recurrence)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TaskKindToggle(
            repeating = recurrence != null,
            onOnce = { actions.update { it.copy(recurrence = null) } },
            onRepeating = { actions.update { it.withRecurrence(Recurrence.Daily(), state.now) } },
        )
        ChipSection(stringResource(R.string.section_schedule)) {
            if (recurrence != null) {
                chip(true, recurrenceLabel(recurrence), { IconRepeat(icon) }) { sheet = EditorSheet.REPEAT }
                chip(
                    todo.dueAt != null,
                    todo.dueAt?.let { stringResource(R.string.next_due, dueLabel(it, todo.dueAllDay, state.now)) }
                        ?: stringResource(R.string.next_occurrence),
                    { IconEvent(icon) },
                ) { sheet = EditorSheet.DUE }
            } else {
                chip(
                    todo.dueAt != null,
                    todo.dueAt?.let { dueLabel(it, todo.dueAllDay, state.now) } ?: stringResource(R.string.add_due_date),
                    { IconEvent(icon) },
                ) { sheet = EditorSheet.DUE }
            }
            chip(todo.reminderAt != null, reminderLabel(todo, recurrence != null, state.now), { IconAlarm(icon) }) {
                sheet = EditorSheet.REMINDER
            }
        }
        ChipSection(stringResource(R.string.section_organize)) {
            val list = state.lists.firstOrNull { it.syncId == todo.listSyncId }
            val listLabel = list?.let { l -> listOfNotNull(l.name, todo.section).joinToString(" / ") } ?: stringResource(R.string.view_inbox)
            chip(list != null, listLabel, { IconList(icon) }) { sheet = EditorSheet.LIST }
            PriorityChip(todo.priority) { p -> actions.update { it.copy(priority = p) } }
            chip(
                todo.tags.isNotEmpty(),
                todo.tags.joinToString(" ") { "#$it" }.ifEmpty { stringResource(R.string.tags) },
                { IconLocalOffer(icon) },
            ) { sheet = EditorSheet.TAGS }
        }
        ChipSection(stringResource(R.string.section_details)) {
            chip(
                todo.startAt != null,
                todo.startAt?.let {
                    stringResource(R.string.starts_on, DateString.dateShort(DueDates.localDate(it, TimeZone.currentSystemDefault())))
                } ?: stringResource(R.string.start_date),
                { IconSchedule(icon) },
            ) { sheet = EditorSheet.START }
            EstimateChip(todo.estimateMinutes) { m -> actions.update { it.copy(estimateMinutes = m) } }
            chip(
                todo.blockedBy.isNotEmpty(),
                if (todo.blockedBy.isEmpty()) {
                    stringResource(R.string.blocked_by)
                } else {
                    pluralStringResource(R.plurals.waiting_on, todo.blockedBy.size, todo.blockedBy.size)
                },
                { IconLock(icon) },
            ) { sheet = EditorSheet.BLOCKED }
        }
    }
    sheet?.let { EditorSheetHost(it, state, actions) { sheet = null } }
}

@Composable
private fun TaskKindToggle(repeating: Boolean, onOnce: () -> Unit, onRepeating: () -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !repeating,
            onClick = { if (repeating) onOnce() },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text(stringResource(R.string.kind_once)) },
        )
        SegmentedButton(
            selected = repeating,
            onClick = { if (!repeating) onRepeating() },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text(stringResource(R.string.kind_repeating)) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** A repeating task's reminder reads as a time of day, since it fires for every occurrence. */
@Composable
private fun reminderLabel(todo: Todo, repeating: Boolean, now: Long): String {
    val is24 = rememberIs24Hour()
    val at = todo.reminderAt ?: return stringResource(R.string.reminder)
    val due = todo.dueAt
    if (!repeating || due == null) return dueLabel(at, false, now)
    val zone = TimeZone.currentSystemDefault()
    val time = DateString.time(DueDates.localTime(at, zone), is24)
    val daysBefore = DueDates.localDate(at, zone).daysUntil(DueDates.localDate(due, zone))
    return if (daysBefore <= 0) {
        stringResource(R.string.remind_at, time)
    } else {
        pluralStringResource(R.plurals.remind_days_before_at, daysBefore, daysBefore, time)
    }
}

@Composable
private fun PriorityChip(priority: Int, onSet: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = priority != Todo.PRIORITY_NONE,
            onClick = { open = true },
            label = {
                Text(if (priority == Todo.PRIORITY_NONE) stringResource(R.string.priority) else stringResource(R.string.priority_n, priority))
            },
            leadingIcon = { IconFlag(Modifier.size(18.dp), tint = priorityColor(priority) ?: Color.Unspecified) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (p in 1..Todo.PRIORITY_MAX) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.priority_n, p)) },
                    leadingIcon = { IconFlag(tint = priorityColor(p) ?: Color.Unspecified) },
                    onClick = {
                        onSet(p)
                        open = false
                    },
                )
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.no_priority)) }, onClick = {
                onSet(Todo.PRIORITY_NONE)
                open = false
            })
        }
    }
}

@Composable
private fun EstimateChip(minutes: Int?, onSet: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = minutes != null,
            onClick = { open = true },
            label = { Text(minutes?.let { pluralStringResource(R.plurals.estimate_minutes, it, it) } ?: stringResource(R.string.estimate)) },
            leadingIcon = { IconHourglass(Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ESTIMATES.forEach { m ->
                DropdownMenuItem(text = { Text(pluralStringResource(R.plurals.estimate_minutes, m, m)) }, onClick = {
                    onSet(m)
                    open = false
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.no_estimate)) }, onClick = {
                onSet(null)
                open = false
            })
        }
    }
}

private val ESTIMATES = listOf(5, 15, 30, 45, 60, 90, 120, 240)
