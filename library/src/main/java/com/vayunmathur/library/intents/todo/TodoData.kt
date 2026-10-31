package com.vayunmathur.library.intents.todo

import kotlinx.serialization.Serializable

/** One task as the assistant sees it. [id] is the task's stable sync id. */
@Serializable
data class TodoData(
    val id: String,
    val title: String,
    val notes: String = "",
    /** Epoch ms, or null. */
    val dueAt: Long? = null,
    val done: Boolean = false,
    /** 0 none, 1 highest … 4 lowest. */
    val priority: Int = 0,
    val list: String? = null,
)

/** Which tasks [TodoData] lists come from: "today", "upcoming" or "all". */
@Serializable
data class GetTodosQuery(val scope: String = "today")

/**
 * A new task. [title] may carry quick-add syntax ("Call mom tomorrow 6pm #family"), which is
 * honoured when [dueAt] is null.
 */
@Serializable
data class InsertTodoData(
    val title: String,
    val notes: String = "",
    val dueAt: Long? = null,
    val priority: Int = 0,
)

/** Identify a task by [id], or failing that by exact [title]. */
@Serializable
data class CompleteTodoData(val id: String = "", val title: String = "")
