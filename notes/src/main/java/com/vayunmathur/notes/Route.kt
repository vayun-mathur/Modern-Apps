package com.vayunmathur.notes

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object NotesList: Route
    @Serializable
    data class Note(val id: Long): Route
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
