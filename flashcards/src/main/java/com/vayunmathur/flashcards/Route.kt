package com.vayunmathur.flashcards

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for decks, notes, note types, review, and stats. */
@Serializable
sealed interface Route : NavKey {
    /** The deck-list tab (sibling route, the start destination). */
    @Serializable
    data object Decks : Route

    /** The settings tab (sibling route). */
    @Serializable
    data object Settings : Route

    /**
     * The review-statistics screen: both a sibling tab and a destination
     * pushed over a deck's note list (NoteListPage.openStats) — one route
     * for both, not a tab duplicate.
     */
    @Serializable
    data object Stats : Route

    /** The note-type management list. */
    @Serializable
    data object NoteTypeList : Route

    /** The editor for the note type identified by [noteTypeId]. */
    @Serializable
    data class NoteTypeEdit(val noteTypeId: Long) : Route

    /** The note list for the deck identified by [deckId]. */
    @Serializable
    data class CardList(val deckId: Long) : Route

    /** The editor for note [noteId] in deck [deckId]. */
    @Serializable
    data class NoteEdit(val deckId: Long, val noteId: Long) : Route

    /** A review session for deck [deckId] with its study mode and limits. */
    @Serializable
    data class Review(
        val deckId: Long,
        val mode: Int = 0,
        val count: Int = 20,
        val daysAhead: Int = 3,
        val tags: List<String> = emptyList(),
    ) : Route
}
