package com.vayunmathur.todo.domain.format

import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList

/**
 * A task read from a file, before it gets an identity in this app. Lists are by name (created
 * on import when missing); [ref]/[parentRef] link subtasks within the same file.
 */
data class ImportedTask(
    val title: String,
    val notes: String = "",
    val done: Boolean = false,
    val dueAt: Long? = null,
    val dueAllDay: Boolean = true,
    val priority: Int = 0,
    val listName: String? = null,
    val section: String? = null,
    val tags: List<String> = emptyList(),
    val recurrence: String? = null,
    val startAt: Long? = null,
    val createdAt: Long? = null,
    val completedAt: Long? = null,
    val ref: String? = null,
    val parentRef: String? = null,
)

/** What an exporter needs: every task plus list names. */
data class ExportSource(val todos: List<Todo>, val lists: List<TodoList>) {
    private val listNames = lists.associate { it.syncId to it.name }
    fun listName(todo: Todo): String? = todo.listSyncId?.let { listNames[it] }
}

enum class ExportFormat(val extension: String, val mimeType: String) {
    TODO_TXT("txt", "text/plain"),
    CSV("csv", "text/csv"),
    ICALENDAR("ics", "text/calendar"),
}
