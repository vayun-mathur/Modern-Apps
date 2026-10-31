package com.vayunmathur.todo.platform

import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.domain.SortMode
import com.vayunmathur.todo.domain.TodoListModel
import com.vayunmathur.todo.domain.TodoView

/**
 * The UI contract between [TodoViewModel] plus the nav back stack and the list screen.
 *
 * Screens take a state value and an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `platform` rather than `ui` so the dependency runs one way.
 */
data class TodoListUiState(
    val view: TodoView = TodoView.Inbox,
    val model: TodoListModel = TodoListModel(),
    val lists: List<TodoList> = emptyList(),
    val searchQuery: String = "",
    val sort: SortMode = SortMode.MANUAL,
    /** Hidden while a todo is already open beside the list in the list-detail layout. */
    val showAddButton: Boolean = true,
    /** Epoch ms the relative due labels are computed against. */
    val now: Long = System.currentTimeMillis(),
)

/** Every method has a no-op default so a preview can render without behaviour. */
interface TodoListActions {
    fun openTodo(id: Long) {}
    fun createTodo() {}
    fun openDrawer() {}
    fun setSearchQuery(query: String) {}
    fun setSort(sort: SortMode) {}

    /** Parses [text] as quick-add syntax and creates one task per entry. */
    fun quickAdd(texts: List<String>) {}

    /** Toggles done; returns nothing, the stored row comes back through state. */
    fun toggleDone(todo: Todo) {}
    fun delete(todos: List<Todo>) {}

    /** Writes the exact earlier copies back — the snackbar Undo. */
    fun restore(todos: List<Todo>) {}

    /** Persists moved rows; each already carries its new position. */
    fun upsertAll(todos: List<Todo>) {}
    fun complete(todos: List<Todo>) {}
    fun move(todos: List<Todo>, listSyncId: String?) {}
    fun reschedule(todos: List<Todo>, dueAt: Long?) {}
    fun setPriority(todos: List<Todo>, priority: Int) {}

    companion object {
        val Noop: TodoListActions = object : TodoListActions {}
    }
}
