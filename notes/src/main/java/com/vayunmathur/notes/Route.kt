package com.vayunmathur.notes

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for the notes app. */
@Serializable
sealed interface Route: NavKey {
    /** Notes list destination. */
    @Serializable
    data object NotesList: Route
    /** Note editor destination for the note with the given id. */
    @Serializable
    data class Note(val id: Long): Route
    /** External note destination opened from a shared or dropped file URI. */
    @Serializable
    data class ExternalNote(val uri: String): Route
    /**
     * Full-screen ink editor for one ink block. Block ids are the stable
     * [com.vayunmathur.notes.data.NoteBlock.id] values (UUID strings), so
     * [blockId] is a String, not a Long.
     */
    @Serializable
    data class InkEditor(val noteId: Long, val blockId: String): Route
}
