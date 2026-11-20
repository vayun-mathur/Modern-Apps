package com.vayunmathur.notes.ui

import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.domain.filterNotesBySearch
import com.vayunmathur.notes.domain.noteMatchesSearchText

/**
 * List search predicate, kept as a thin alias over the pure domain rules in
 * `domain/NoteSearch.kt` (which the ViewModel's off-main-thread filtered flow
 * also uses). A note matches when the query appears in its title, its
 * plain-text content, or one of its tag names (#913).
 */
internal fun noteMatchesSearch(note: Note, query: String): Boolean =
    noteMatchesSearchText(note, query)

/**
 * What the list shows for callers without a precomputed haystack index
 * (tests / previews): [notes] narrowed to [tag], then to [query]. Production
 * filtering runs in the ViewModel over precomputed haystacks; see
 * `domain.filterIndexedNotes`.
 */
internal fun filterNotes(notes: List<Note>, query: String, tag: String?): List<Note> =
    filterNotesBySearch(notes, query, tag)
