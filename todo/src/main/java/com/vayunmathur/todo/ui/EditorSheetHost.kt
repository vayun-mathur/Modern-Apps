package com.vayunmathur.todo.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.Recurrence
import com.vayunmathur.todo.platform.TodoActions
import com.vayunmathur.todo.platform.TodoUiState
import kotlinx.datetime.TimeZone
import com.vayunmathur.library.ui.R as UiR

/** Shows the sheet or dialog for one [EditorSheet]; every choice dismisses it. */
@Composable
internal fun EditorSheetHost(sheet: EditorSheet, state: TodoUiState, actions: TodoActions, onDismiss: () -> Unit) {
    val todo = state.todo
    val set: ((Todo) -> Todo) -> Unit = { transform ->
        actions.update(transform)
        onDismiss()
    }
    val repeating = todo.recurrence != null
    when (sheet) {
        EditorSheet.DUE -> DueDateSheet(todo.dueAt, todo.dueAllDay, repeating, onSet = { due, allDay ->
            set { t ->
                val shift = if (t.dueAt != null && due != null) due - t.dueAt else null
                t.copy(dueAt = due, dueAllDay = allDay, reminderAt = shift?.let { s -> t.reminderAt?.plus(s) } ?: t.reminderAt)
            }
        }, onDismiss = onDismiss)
        EditorSheet.REPEAT -> RecurrenceSheet(Recurrence.parse(todo.recurrence), onSet = { r ->
            set { it.withRecurrence(r, state.now) }
        }, onDismiss = onDismiss)
        EditorSheet.REMINDER -> ReminderSheet(todo, onSet = { at ->
            set { it.copy(reminderAt = at) }
        }, onDismiss = onDismiss)
        EditorSheet.LIST -> ListSectionPickerDialog(state.lists, todo.listSyncId, todo.section, onPick = { list, section ->
            set { it.copy(listSyncId = list, section = section) }
        }, onDismiss = onDismiss)
        EditorSheet.TAGS -> TagEditorDialog(todo.tags, state.allTags, onSet = { tags ->
            set { it.copy(tags = tags) }
        }, onDismiss = onDismiss)
        EditorSheet.START -> StartDateDialog(todo.startAt, onSet = { start -> set { it.copy(startAt = start) } }, onDismiss = onDismiss)
        EditorSheet.BLOCKED -> BlockedByDialog(state.blockerCandidates, todo.blockedBy, onSet = { ids ->
            set { it.copy(blockedBy = ids) }
        }, onDismiss = onDismiss)
    }
}

/**
 * Makes the task repeat on [recurrence]. A repeating task only rolls forward from a due date,
 * so one without gets the schedule's first day from today.
 */
internal fun Todo.withRecurrence(recurrence: Recurrence, now: Long): Todo {
    if (dueAt != null) return copy(recurrence = recurrence.serialize())
    val zone = TimeZone.currentSystemDefault()
    val first = recurrence.firstOnOrAfter(DueDates.localDate(now, zone))
    return copy(recurrence = recurrence.serialize(), dueAt = DueDates.startOfDay(first, zone), dueAllDay = true)
}

/** A set start date offers clearing first; otherwise go straight to the picker. */
@Composable
private fun StartDateDialog(startAt: Long?, onSet: (Long?) -> Unit, onDismiss: () -> Unit) {
    if (startAt == null) {
        TodoDatePickerDialog(initial = null, onConfirm = onSet, onDismiss = onDismiss)
        return
    }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        TodoDatePickerDialog(initial = startAt, onConfirm = onSet, onDismiss = onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.start_date)) },
        text = {
            androidx.compose.foundation.layout.Column {
                ListItem(headlineContent = { Text(stringResource(R.string.pick_date)) }, modifier = Modifier.clickable { picking = true })
                ListItem(headlineContent = { Text(stringResource(R.string.no_start_date)) }, modifier = Modifier.clickable { onSet(null) })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}
