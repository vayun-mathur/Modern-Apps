package com.vayunmathur.todo.domain

import kotlinx.serialization.Serializable

/** What the main list is showing. */
@Serializable
sealed interface TodoView {
    /** Tasks that belong to no list. */
    @Serializable data object Inbox : TodoView
    /** Open tasks due today or overdue, plus tasks completed today. */
    @Serializable data object Today : TodoView
    /** Tasks with a future due date that are not yet overdue. */
    @Serializable data object Upcoming : TodoView
    /** Tasks that have a priority set. */
    @Serializable data object Priority : TodoView
    /** Every task, regardless of list or due date. */
    @Serializable data object All : TodoView
    /** Tasks that have been completed. */
    @Serializable data object Completed : TodoView
    /** Tasks belonging to the list identified by listSyncId. */
    @Serializable data class InList(val listSyncId: String) : TodoView
    /** Tasks carrying the given tag. */
    @Serializable data class Tagged(val tag: String) : TodoView

    /** The smart views and conversion between views and their stable preference keys. */
    companion object {
        // A getter, not a stored list: the nested objects may not be initialised yet when the
        // companion is, which would leave nulls in the list.
        val smartViews: List<TodoView> get() = listOf(Inbox, Today, Upcoming, Priority, All, Completed)

        fun fromKey(key: String?): TodoView = when {
            key == null -> Inbox
            key.startsWith(LIST_PREFIX) -> InList(key.removePrefix(LIST_PREFIX))
            key.startsWith(TAG_PREFIX) -> Tagged(key.removePrefix(TAG_PREFIX))
            else -> smartViews.firstOrNull { it.key == key } ?: Inbox
        }

        private const val LIST_PREFIX = "list:"
        private const val TAG_PREFIX = "tag:"
    }

    /** Stable string form for preferences and widget config. */
    val key: String
        get() = when (this) {
            Inbox -> "inbox"
            Today -> "today"
            Upcoming -> "upcoming"
            Priority -> "priority"
            All -> "all"
            Completed -> "completed"
            is InList -> LIST_PREFIX + listSyncId
            is Tagged -> TAG_PREFIX + tag
        }
}

/** Orderings available for the task list. */
enum class SortMode { MANUAL, DUE, PRIORITY, CREATED, TITLE }
