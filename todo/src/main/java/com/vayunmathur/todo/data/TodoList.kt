package com.vayunmathur.todo.data

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.ReorderableDatabaseItem
import kotlinx.serialization.Serializable

/** A user list (project). Tasks point at it through [Todo.listSyncId]. */
@Serializable
@Entity(indices = [Index("syncId")])
data class TodoList(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val syncId: String = newSyncId(),
    val name: String = "",
    /** ARGB, or null for the theme accent. */
    val color: Int? = null,
    /** Key into the list icon set drawn by the UI, or null for the default. */
    val icon: String? = null,
    override val position: Double = 0.0,
    /** Section names in display order. */
    val sections: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) : ReorderableDatabaseItem<TodoList> {
    override fun withPosition(position: Double) = copy(position = position)
}
