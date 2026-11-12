package com.vayunmathur.notes.ui

import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.domain.hasTag

/**
 * List search predicate: a note matches when the query appears in its title,
 * its plain-text content (which [com.vayunmathur.notes.data.withBody] keeps in
 * sync with the text blocks), or one of its tag names (#913). Deliberately NOT
 * the data-class `toString()`: that rendering also embeds the id, position,
 * blocks JSON, syncId, updatedAt and deleted flag, so queries like "image",
 * "deleted" or a digit from a timestamp matched nearly every note.
 */
internal fun noteMatchesSearch(note: Note, query: String): Boolean =
    note.title.contains(query, ignoreCase = true) ||
        note.content.contains(query, ignoreCase = true) ||
        note.tags.any { it.contains(query, ignoreCase = true) }

/**
 * What the list shows: [notes] narrowed to those carrying [tag] (single-select
 * filter, case-insensitive; null = All), then to those matching [query] (blank
 * = no search). Order is preserved. The two compose with AND: picking a tag and
 * typing a query searches within that tag.
 */
internal fun filterNotes(notes: List<Note>, query: String, tag: String?): List<Note> {
    if (query.isBlank() && tag == null) return notes
    return notes.filter { note ->
        (tag == null || hasTag(note.tags, tag)) && (query.isBlank() || noteMatchesSearch(note, query))
    }
}
