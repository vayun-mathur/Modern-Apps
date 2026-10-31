package com.vayunmathur.todo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.DatePicker
import com.vayunmathur.library.ui.DatePickerDialog
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCalendar
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberDatePickerState
import com.vayunmathur.todo.R
import com.vayunmathur.todo.platform.TodoActions
import com.vayunmathur.todo.platform.TodoUiState
import com.vayunmathur.todo.domain.DueDates
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import com.vayunmathur.library.ui.R as UiR

/**
 * The todo editor, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoDetailScreen(
    state: TodoUiState,
    actions: TodoActions,
) {
    var showDuePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    DetailScaffold(
        title = stringResource(R.string.todo_details),
        onNavigateBack = { actions.back() },
        scrollBehavior = appBarScrollBehavior(),
        actions = {
            IconButton(onClick = { showDeleteConfirm = true }) {
                IconDelete()
            }
        },
    ) {
        OutlinedTextField(
            value = state.title,
            onValueChange = { actions.setTitle(it) },
            label = { Text(stringResource(R.string.todo_title)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.notes,
            onValueChange = { actions.setNotes(it) },
            label = { Text(stringResource(R.string.todo_notes)) },
            modifier = Modifier.fillMaxWidth(),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.done)) },
            leadingContent = {
                Checkbox(
                    checked = state.done,
                    onCheckedChange = { actions.setDone(it) },
                )
            },
        )
        val dueLabel = state.dueAt?.let { DateString.dateShort(Instant.fromEpochMilliseconds(it)) }
            ?: stringResource(R.string.no_due_date)
        ListItem(
            headlineContent = { Text(dueLabel) },
            modifier = Modifier.clickable { showDuePicker = true },
            trailingContent = {
                IconButton(onClick = { showDuePicker = true }) {
                    IconCalendar()
                }
            },
        )
        if (state.dueAt != null) {
            TextButton(onClick = { actions.setDueAt(null) }) {
                Text(stringResource(R.string.clear_date))
            }
        }
    }

    if (showDuePicker) {
        val zone = TimeZone.currentSystemDefault()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.dueAt?.let { DueDates.toPickerMillis(it, zone) },
        )
        DatePickerDialog(
            onDismissRequest = { showDuePicker = false },
            confirmButton = {
                Button(
                    onClick = {
                        actions.setDueAt(pickerState.selectedDateMillis?.let { DueDates.fromPickerMillis(it, zone) })
                        showDuePicker = false
                    },
                    enabled = pickerState.selectedDateMillis != null,
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                Button(onClick = { showDuePicker = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            },
        ) {
            DatePicker(pickerState)
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.delete_todo),
            message = stringResource(R.string.delete_todo_confirm),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = { actions.deleteTodo() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
