package com.vayunmathur.todo

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for the todo app; the back stack is the source of truth for the selected view. */
@Serializable
sealed interface Route : NavKey {
    /** The task list showing [viewKey] (a `TodoView.key`); the route is the source of truth for the selected view. */
    @Serializable
    data class TodoList(val viewKey: String) : Route

    /** [id] 0 opens a new task, identified across saves by [newSyncId]. */
    @Serializable
    data class TodoDetail(val id: Long, val newSyncId: String? = null) : Route

    /** The app settings screen. */
    @Serializable
    data object Settings : Route
}
