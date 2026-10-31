package com.vayunmathur.todo

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object TodoList : Route

    /** [id] 0 opens a new task, identified across saves by [newSyncId]. */
    @Serializable
    data class TodoDetail(val id: Long, val newSyncId: String? = null) : Route

    @Serializable
    data object Settings : Route
}
