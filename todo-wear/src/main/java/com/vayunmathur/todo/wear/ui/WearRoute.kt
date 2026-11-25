package com.vayunmathur.todo.wear.ui

/**
 * Watch navigation destinations mirroring the phone's `Route`: page routes
 * carry the phone `TodoView.key`, detail routes carry the task syncId —
 * never selection state held in a view model (root AGENTS.md rule).
 */
sealed interface WearRoute {
    /** Home: the phone drawer's view index (smart views, lists, tags) + quick add. */
    data object Home : WearRoute

    /** One `Route.TodoList(viewKey)` page: tasks of [pageKey] at its cached slices. */
    data class Page(val pageKey: String) : WearRoute

    /** One `Route.TodoDetail` page, identified by stable syncId. */
    data class TaskDetail(val syncId: String, val pageKey: String) : WearRoute

    /** Quick-add entry (text/dictation); the phone parses it. */
    data class QuickAdd(val pageKey: String) : WearRoute

    /** Watch-sized edit of one task: title, due date, priority. */
    data class EditTask(val syncId: String, val pageKey: String) : WearRoute

    /** Delete confirmation for one task. */
    data class ConfirmDelete(val syncId: String, val pageKey: String) : WearRoute
}
