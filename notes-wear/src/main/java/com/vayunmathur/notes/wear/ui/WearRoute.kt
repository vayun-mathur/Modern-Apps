package com.vayunmathur.notes.wear.ui

/** Watch navigation destinations: detail/editor routes carry the note syncId, never selection state. */
sealed interface WearRoute {
    /** Recent-notes list. */
    data object NoteList : WearRoute

    /** One note's view, identified by its stable syncId. */
    data class NoteView(val syncId: String) : WearRoute

    /** Quick-note creation (dictation-friendly title/body entry). */
    data object CreateNote : WearRoute

    /** Edit of one text-only note, identified by its stable syncId. */
    data class EditNote(val syncId: String) : WearRoute
}
