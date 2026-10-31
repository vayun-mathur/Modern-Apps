package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.domain.DueShortcuts

/** What the selection bar can do to the selected tasks. */
interface BulkActions {
    fun clear()
    fun selectAll()
    fun complete()
    fun delete()
    fun move(listSyncId: String?)
    fun reschedule(dueAt: Long?)
    fun setPriority(priority: Int)
}

/** Top bar shown while tasks are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkActionBar(count: Int, lists: List<TodoList>, actions: BulkActions) {
    var menu by remember { mutableStateOf(false) }
    var pickList by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.selected_count, count, count)) },
        navigationIcon = { IconButton(onClick = actions::clear) { IconClose() } },
        actions = {
            IconButton(onClick = actions::complete) { IconCheck() }
            IconButton(onClick = actions::delete) { IconDelete() }
            IconButton(onClick = { menu = true }) { IconMoreVert() }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val item: @Composable (Int, () -> Unit) -> Unit = { label, onClick ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                        menu = false
                        onClick()
                    })
                }
                item(R.string.select_all, actions::selectAll)
                item(R.string.move_to) { pickList = true }
                HorizontalDivider()
                DueShortcuts.entries.forEach { shortcut ->
                    item(shortcut.label) { actions.reschedule(shortcut.dueAt()) }
                }
                item(R.string.pick_date) { pickDate = true }
                HorizontalDivider()
                (1..Todo.PRIORITY_MAX).forEach { p ->
                    DropdownMenuItem(text = { Text(stringResource(R.string.priority_n, p)) }, onClick = {
                        menu = false
                        actions.setPriority(p)
                    })
                }
                item(R.string.no_priority) { actions.setPriority(Todo.PRIORITY_NONE) }
            }
        },
    )

    if (pickList) {
        ListPickerDialog(lists, current = "", onPick = {
            actions.move(it)
            pickList = false
        }, onDismiss = { pickList = false })
    }
    if (pickDate) {
        TodoDatePickerDialog(initial = null, onConfirm = {
            actions.reschedule(it)
            pickDate = false
        }, onDismiss = { pickDate = false })
    }
}

private val DueShortcuts.label: Int
    get() = when (this) {
        DueShortcuts.TODAY -> R.string.bucket_today
        DueShortcuts.TOMORROW -> R.string.bucket_tomorrow
        DueShortcuts.NEXT_WEEK -> R.string.next_week
        DueShortcuts.NONE -> R.string.bucket_no_date
    }
