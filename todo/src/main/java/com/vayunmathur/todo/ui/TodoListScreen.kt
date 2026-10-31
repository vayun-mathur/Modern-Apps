package com.vayunmathur.todo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberHaptics
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.Completion
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.GroupKey
import com.vayunmathur.todo.domain.Positions
import com.vayunmathur.todo.domain.QuickAddParser
import com.vayunmathur.todo.domain.TodoRow
import com.vayunmathur.todo.domain.TodoView
import com.vayunmathur.todo.platform.TodoListActions
import com.vayunmathur.todo.platform.TodoListUiState
import kotlinx.datetime.TimeZone

/**
 * The todo list, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoListScreen(
    state: TodoListUiState,
    actions: TodoListActions,
    /** Extra top-bar actions; empty in a preview. */
    extraActions: @Composable RowScope.() -> Unit = {},
    /** Leading slot of the add field (voice input), or null. */
    quickAddLeading: (@Composable () -> Unit)? = null,
    showMenu: Boolean = true,
) {
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var completedExpanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.searchQuery.isNotEmpty()) { actions.setSearchQuery("") }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    val messenger = rememberMessenger()
    val haptics = rememberHaptics()
    val resources = LocalResources.current
    val completedMsg = stringResource(R.string.task_completed)
    val deletedMsg = stringResource(R.string.task_deleted)
    val undo = stringResource(R.string.undo)

    val model = state.model
    val openRows = model.groups.flatMap { it.rows }
    val allRows = openRows + model.completed
    val byId = allRows.associateBy { it.todo.id }
    val listState = rememberLazyListState()
    val reorder = rememberTodoReorderState(
        todos = openRows.map { it.todo },
        listState = listState,
        indexOffset = 1,
        onMoveTick = { haptics.tick() },
        onCommit = { actions.upsertAll(it) },
    ).takeIf { model.canReorder && selected.isEmpty() }
    val title = viewTitle(state.view, state.lists)
    val listNames = remember(state.lists) { state.lists.map { QuickAddParser.ListName(it.syncId, it.name) } }

    fun withUndo(message: String, before: List<Todo>) =
        messenger.show(message, undo) { actions.restore(before) }

    val handlers = TodoListRowHandlers(
        onToggle = { row ->
            actions.toggleDone(row.todo)
            if (!row.todo.done) {
                haptics.confirm()
                val next = rolledDueDate(row.todo)
                withUndo(next?.let { resources.getString(R.string.task_rolled, it) } ?: completedMsg, listOf(row.todo))
            }
        },
        onDelete = { row ->
            actions.delete(listOf(row.todo))
            withUndo(deletedMsg, listOf(row.todo))
        },
        onClick = { row ->
            if (selected.isEmpty()) actions.openTodo(row.todo.id) else selected = selected.toggle(row.todo.id)
        },
        onLongClick = { row ->
            haptics.longPress()
            selected = selected.toggle(row.todo.id)
        },
        onMoveUp = if (model.canReorder) { row -> move(openRows, row, -1)?.let { actions.upsertAll(listOf(it)) } } else null,
        onMoveDown = if (model.canReorder) { row -> move(openRows, row, 1)?.let { actions.upsertAll(listOf(it)) } } else null,
    )

    val selectedTodos = selected.mapNotNull { byId[it]?.todo }
    val bulk = object : BulkActions {
        override fun clear() { selected = emptySet() }
        override fun selectAll() { selected = openRows.map { it.todo.id }.toSet() }
        override fun complete() {
            actions.complete(selectedTodos)
            withUndo(completedMsg, selectedTodos)
            selected = emptySet()
        }
        override fun delete() {
            actions.delete(selectedTodos)
            withUndo(resources.getQuantityString(R.plurals.tasks_deleted, selectedTodos.size, selectedTodos.size), selectedTodos)
            selected = emptySet()
        }
        override fun move(listSyncId: String?) {
            actions.move(selectedTodos, listSyncId)
            selected = emptySet()
        }
        override fun reschedule(dueAt: Long?) {
            actions.reschedule(selectedTodos, dueAt)
            selected = emptySet()
        }
        override fun setPriority(priority: Int) {
            actions.setPriority(selectedTodos, priority)
            selected = emptySet()
        }
    }

    LazyListScaffold(
        topBar = {
            if (selected.isNotEmpty()) {
                BulkActionBar(selected.size, state.lists, bulk)
            } else {
                TodoListTopBar(
                    title = title,
                    query = state.searchQuery,
                    sort = state.sort,
                    onQuery = actions::setSearchQuery,
                    onSort = actions::setSort,
                    onMenu = if (showMenu) actions::openDrawer else null,
                    scrollBehavior = null,
                    extraActions = extraActions,
                )
            }
        },
        floatingActionButton = {
            if (state.showAddButton && selected.isEmpty()) {
                FloatingActionButton(onClick = { actions.createTodo() }) { IconAdd() }
            }
        },
        state = listState,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        item(key = "quick-add") {
            QuickAddBar(lists = listNames, onAdd = actions::quickAdd, leading = quickAddLeading)
        }
        val display = { row: TodoRow ->
            TodoRowDisplay(
                query = state.searchQuery,
                now = state.now,
                showList = state.view !is TodoView.InList,
                selected = row.todo.id in selected,
                selectionMode = selected.isNotEmpty(),
            )
        }
        if (model.openCount == 0) {
            item(key = "empty") {
                TodoEmptyState(
                    when {
                        state.searchQuery.isNotBlank() -> EmptyKind.SEARCH
                        model.completed.isNotEmpty() -> EmptyKind.ALL_DONE
                        else -> EmptyKind.NOTHING
                    },
                )
            }
        }
        if (reorder != null) {
            items(reorder.rows, key = { it.id }) { todo ->
                val row = byId[todo.id]?.copy(todo = todo) ?: TodoRow(todo)
                TodoListRow(row, display(row), reorder, haptics, handlers)
            }
        } else {
            model.groups.forEach { group ->
                if (group.key != GroupKey.None && group.rows.isNotEmpty()) {
                    item(key = "header-${group.key}") { GroupHeader(group.key) }
                }
                items(group.rows, key = { it.todo.id }) { row -> TodoListRow(row, display(row), null, haptics, handlers) }
            }
        }
        if (model.completed.isNotEmpty()) {
            item(key = "completed-header") {
                CompletedHeader(
                    count = model.completed.size,
                    expanded = completedExpanded,
                    onToggle = { completedExpanded = !completedExpanded },
                    onClear = {
                        val done = model.completed.map { it.todo }
                        actions.delete(done)
                        withUndo(resources.getQuantityString(R.plurals.tasks_deleted, done.size, done.size), done)
                    },
                )
            }
            if (completedExpanded) {
                items(model.completed, key = { it.todo.id }) { row -> TodoListRow(row, display(row), null, haptics, handlers) }
            }
        }
    }
}

/** The next due date of a repeating task once completed now, formatted; null otherwise. */
private fun rolledDueDate(todo: Todo): String? {
    if (todo.recurrence == null) return null
    val zone = TimeZone.currentSystemDefault()
    val next = Completion.complete(todo, System.currentTimeMillis(), zone)
    if (next.done) return null
    return next.dueAt?.let { DateString.dateWeekdayNoYear(DueDates.localDate(it, zone)) }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

/** The row moved one place in [direction] (-1 up, +1 down), or null at an edge. */
private fun move(rows: List<TodoRow>, row: TodoRow, direction: Int): Todo? {
    val i = rows.indexOfFirst { it.todo.id == row.todo.id }
    val target = i + direction
    if (i < 0 || target !in rows.indices) return null
    val positions = rows.map { it.todo.position }
    return row.todo.withPosition(Positions.forMove(positions, i, target))
}
