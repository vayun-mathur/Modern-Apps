package com.vayunmathur.todo.platform

import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList

/** Everything the todo editor draws. */
data class TodoUiState(
    val todo: Todo = Todo(),
    val subtasks: List<Todo> = emptyList(),
    val lists: List<TodoList> = emptyList(),
    /** Every tag in use, for suggestions. */
    val allTags: List<String> = emptyList(),
    /** Open tasks this one could wait on. */
    val blockerCandidates: List<Todo> = emptyList(),
    val isNew: Boolean = false,
    val now: Long = System.currentTimeMillis(),
)

/** Todo editor callbacks, no-op by default for previews. */
interface TodoActions {
    fun back() {}

    /** Any field edit; [transform] gets the current task. */
    fun update(transform: (Todo) -> Todo) {}

    /** Completing a repeating task rolls it forward instead. */
    fun setDone(done: Boolean) {}
    fun addSubtask(title: String) {}
    fun updateSubtask(subtask: Todo) {}
    fun toggleSubtask(subtask: Todo) {}
    fun deleteSubtask(subtask: Todo) {}
    fun duplicate() {}
    fun deleteTodo() {}

    companion object {
        val Noop: TodoActions = object : TodoActions {}
    }
}
