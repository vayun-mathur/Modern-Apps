package com.vayunmathur.notes.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.ReorderableDatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Note(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val title: String,
    val content: String,
    override val position: Double = 0.0,
    // JSON list of [NoteBlock] (text / image / ink). Null for legacy text-only
    // notes, which are read back as a single text block built from [content].
    val blocks: String? = null,
): ReorderableDatabaseItem<Note> {
    override fun withPosition(position: Double) = copy(position = position)
}