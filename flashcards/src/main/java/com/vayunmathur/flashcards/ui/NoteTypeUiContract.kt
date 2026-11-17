package com.vayunmathur.flashcards.ui
import com.vayunmathur.flashcards.domain.Grade
import com.vayunmathur.flashcards.platform.FlashcardsViewModel

import com.vayunmathur.flashcards.data.CardTemplate
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.NoteType
import com.vayunmathur.flashcards.data.NoteTypeField

/**
 * The UI contract between [FlashcardsViewModel] plus the nav back stack and the screens.
 *
 * Screens take a state value and an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. The binders in `ui` implement these interfaces. Every actions
 * method has a no-op default so a preview can use the `Noop` implementation with no VM.
 */


// ---------------------------------------------------------------------------
// Note type list + editor
// ---------------------------------------------------------------------------

/** A note type plus its counts, drawn on the note-type management list. */
data class NoteTypeSummary(
    val id: Long,
    val name: String,
    val fieldCount: Int,
    val templateCount: Int,
    val noteCount: Int,
    val isCloze: Boolean,
)

/** Everything the note-type management list draws. */
data class NoteTypeListUiState(
    val noteTypes: List<NoteTypeSummary> = emptyList(),
)

/** Callbacks for the note-type management list. */
interface NoteTypeListActions {
    fun back() {}
    fun openNoteType(id: Long) {}
    fun addNoteType() {}
    fun deleteNoteType(id: Long) {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: NoteTypeListActions = object : NoteTypeListActions {}
    }
}

/** An editable template draft in the note-type editor. */
data class TemplateDraft(
    val name: String,
    val qfmt: String,
    val afmt: String,
)

/** Everything the note-type editor draws for one note type. */
data class NoteTypeEditUiState(
    val id: Long = 0,
    val name: String = "",
    val css: String = "",
    /** [com.vayunmathur.flashcards.data.NoteTypeKind]. Cloze note types have a fixed single template. */
    val type: Int = 0,
    val fields: List<String> = emptyList(),
    val templates: List<TemplateDraft> = emptyList(),
    val isNew: Boolean = true,
)

/** Callbacks for the note-type editor: saving and deleting the note type. */
interface NoteTypeEditActions {
    fun back() {}
    fun save(name: String, css: String, type: Int, fields: List<String>, templates: List<TemplateDraft>) {}
    fun delete() {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: NoteTypeEditActions = object : NoteTypeEditActions {}
    }
}

/** Bundles a note type with its ordered fields and templates for in-memory caching. */
data class NoteTypeWithConfig(
    val noteType: NoteType,
    val fields: List<NoteTypeField>,
    val templates: List<CardTemplate>,
)

// ---------------------------------------------------------------------------
// Review session
