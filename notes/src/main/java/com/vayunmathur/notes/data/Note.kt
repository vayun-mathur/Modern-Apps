package com.vayunmathur.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Note(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val title: String,
    val content: String,
    override val position: Double = 0.0
): DatabaseItem<Note>() {
    override fun withPosition(position: Double) = copy(position = position)
}