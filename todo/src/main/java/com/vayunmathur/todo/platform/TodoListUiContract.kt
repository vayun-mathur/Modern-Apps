package com.vayunmathur.todo.platform

import com.vayunmathur.todo.data.Todo

/**
 * The UI contract between [TodoViewModel] plus the nav back stack and the list screen.
 *
 * Screens take a state value and an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `platform` rather than `ui` so the dependency runs one way.
 */
data class TodoListUiState(
    val todos: List<Todo> = emptyList(),
    val searchQuery: String = "",
    /** Hidden while a todo is already open beside the list in the list-detail layout. */
    val showAddButton: Boolean = true,
) {
    /** Manual order is only meaningful over the unfiltered list. */
    val canReorder: Boolean get() = searchQuery.isBlank() && todos.size > 1
}

/** Every method has a no-op default so a preview can render without behaviour. */
interface TodoListActions {
    fun openTodo(id: Long) {}
    fun createTodo() {}
    fun setSearchQuery(query: String) {}
    fun toggleDone(todo: Todo) {}
    fun delete(todo: Todo) {}

    /** Persists moved rows; each already carries its new position. */
    fun upsertAll(todos: List<Todo>) {}

    companion object {
        val Noop: TodoListActions = object : TodoListActions {}
    }
}
