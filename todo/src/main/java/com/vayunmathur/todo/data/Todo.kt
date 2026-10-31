package com.vayunmathur.todo.data

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.ReorderableDatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity(indices = [Index("syncId"), Index("listSyncId"), Index("parentSyncId")])
data class Todo(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val title: String = "",
    val notes: String = "",
    val done: Boolean = false,
    override val position: Double = 0.0,
    /**
     * Epoch ms the task is due, or null. When [dueAllDay] it is local midnight of the due
     * date; otherwise the exact instant.
     */
    val dueAt: Long? = null,
    /** Epoch ms the task was created; part of the relay adoption content key. */
    val createdAt: Long = System.currentTimeMillis(),
    val syncId: String = newSyncId(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    /** Set on subtasks: the parent task's [syncId]. Subtasks are one level deep. */
    val parentSyncId: String? = null,
    /** The owning [TodoList.syncId]; null is the Inbox. */
    val listSyncId: String? = null,
    /** Name of a section in the owning list, or null. */
    val section: String? = null,
    val tags: List<String> = emptyList(),
    /** 0 none, 1 highest … 4 lowest. */
    val priority: Int = 0,
    val dueAllDay: Boolean = true,
    /** Hidden from Today/Upcoming until this epoch ms. */
    val startAt: Long? = null,
    /** [Recurrence][com.vayunmathur.todo.domain.Recurrence] string, or null. */
    val recurrence: String? = null,
    val reminderAt: Long? = null,
    /** Epoch ms of the last completion. */
    val completedAt: Long? = null,
    val estimateMinutes: Int? = null,
    /** Sync ids of tasks that must be done first. */
    val blockedBy: List<String> = emptyList(),
) : ReorderableDatabaseItem<Todo> {
    override fun withPosition(position: Double) = copy(position = position)

    val isSubtask: Boolean get() = parentSyncId != null

    companion object {
        const val PRIORITY_NONE = 0
        const val PRIORITY_MAX = 4
    }
}
