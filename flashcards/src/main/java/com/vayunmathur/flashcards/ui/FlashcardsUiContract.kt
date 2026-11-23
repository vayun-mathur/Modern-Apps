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
// Deck list
// ---------------------------------------------------------------------------

/** A deck plus its derived review counts, as drawn on the deck list. */
data class DeckSummary(
    val deck: Deck,
    val dueCount: Int = 0,
    val newCount: Int = 0,
    val totalCount: Int = 0,
    /** Fraction of cards that have graduated to the review state (0..1). */
    val mastery: Float = 0f,
)

/** Everything the deck list draws. */
data class DeckListUiState(
    val decks: List<DeckSummary> = emptyList(),
    /** False until the decks DAO emits at least once, so an empty list can show a spinner. */
    val hasLoaded: Boolean = true,
)

/** Callbacks for the deck list: opening, adding, deleting, reviewing, and reordering decks. */
interface DeckListActions {
    fun openDeck(id: Long) {}
    fun addDeck(name: String) {}
    fun deleteDeck(deck: Deck) {}
    fun startReview(deckId: Long) {}
    fun reorder(decks: List<Deck>) {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: DeckListActions = object : DeckListActions {}
    }
}

// ---------------------------------------------------------------------------
// Note list
// ---------------------------------------------------------------------------

/** A note plus the number of cards it currently generates, drawn on the note list. */
data class NoteRow(
    val note: Note,
    val cardCount: Int = 1,
    /** True when every card of this note is suspended. */
    val suspended: Boolean = false,
)

/** Everything the note list draws for a single deck. */
data class NoteListUiState(
    val deckName: String = "",
    val notes: List<NoteRow> = emptyList(),
    /** False until the notes DAO emits at least once, so an empty list can show a spinner. */
    val hasLoaded: Boolean = true,
    val dueCount: Int = 0,
    /** Distinct tags across the deck's notes, for the filter row. */
    val tags: List<String> = emptyList(),
    /** Decks available as move-to targets (excludes the current deck). */
    val decks: List<DeckOption> = emptyList(),
)

/** Callbacks for a deck's note list, including bulk selection actions. */
interface NoteListActions {
    fun back() {}
    fun openNote(id: Long) {}
    fun addNote() {}
    fun deleteNote(note: Note) {}
    fun study(tags: Set<String>) {}
    fun customStudy(params: StudyParams, tags: Set<String>) {}
    fun reorder(notes: List<Note>) {}
    fun openStats() {}
    fun share() {}
    fun exportCsv() {}
    // Bulk (selection-mode) actions, operating on a set of note ids.
    fun deleteNotes(ids: List<Long>) {}
    fun moveNotes(ids: List<Long>, deckId: Long) {}
    fun addTag(ids: List<Long>, tag: String) {}
    fun removeTag(ids: List<Long>, tag: String) {}
    fun setSuspended(ids: List<Long>, suspended: Boolean) {}
    fun resetScheduling(ids: List<Long>) {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: NoteListActions = object : NoteListActions {}
    }
}

// ---------------------------------------------------------------------------
// Note editor
// ---------------------------------------------------------------------------

/** A note type and its ordered field names, used to drive the note editor's fields. */
data class NoteTypeConfig(
    val id: Long,
    val name: String,
    val fieldNames: List<String>,
)

/** Everything the note editor draws: the selected note type, its fields, and tags. */
data class NoteEditUiState(
    val initialNoteTypeId: Long = 0,
    val initialDeckId: Long = 0,
    /** Field values in the selected note type's field order. */
    val initialFieldValues: List<String> = emptyList(),
    val initialTags: String = "",
    val isNew: Boolean = true,
    val isSuspended: Boolean = false,
    val noteTypes: List<NoteTypeConfig> = emptyList(),
    val decks: List<DeckOption> = emptyList(),
)

/** Callbacks for the note editor: saving, deleting, suspension, and image insertion. */
interface NoteEditActions {
    fun back() {}
    fun save(noteTypeId: Long, deckId: Long, fieldValues: List<String>, tags: String) {}
    fun deleteNote() {}
    fun setSuspended(suspended: Boolean) {}
    fun insertImage(fieldIndex: Int) {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: NoteEditActions = object : NoteEditActions {}
    }
}

