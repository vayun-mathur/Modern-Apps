package com.vayunmathur.todo.platform

/** Everything the todo editor draws. */
data class TodoUiState(
    val title: String = "",
    val notes: String = "",
    val done: Boolean = false,
    val dueAt: Long? = null,
)

/** Todo editor callbacks, no-op by default for previews. */
interface TodoActions {
    fun back() {}
    fun setTitle(title: String) {}
    fun setNotes(notes: String) {}
    fun setDone(done: Boolean) {}
    fun setDueAt(dueAt: Long?) {}
    fun deleteTodo() {}

    companion object {
        val Noop: TodoActions = object : TodoActions {}
    }
}
