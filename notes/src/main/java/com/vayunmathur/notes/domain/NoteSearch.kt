package com.vayunmathur.notes.domain

import com.vayunmathur.notes.data.Note

/**
 * Pure list-search rules for notes. No Android, no Compose: the ViewModel
 * builds a [noteSearchHaystack] once per note when the list changes and then
 * matches debounced queries against those precomputed strings off the main
 * thread, instead of scanning full note bodies inside `remember{}` during
 * composition.
 */

/**
 * The lowercased text a note is matched against: title, plain-text content
 * and tag names, run together. Built once per note (see the ViewModel's
 * search index), not per keystroke. Deliberately NOT the data-class
 * `toString()`: that also embeds id, position, blocks JSON, syncId,
 * updatedAt and the deleted flag, so queries like "image", "deleted" or a
 * digit from a timestamp matched nearly every note.
 */
fun noteSearchHaystack(note: Note): String = buildString {
    append(note.title)
    append('\n')
    append(note.content)
    append('\n')
    append(note.tags.joinToString("\n"))
}.lowercase()

/** True when [query] (already blank-checked by callers) appears in [haystack]. */
fun haystackMatches(haystack: String, query: String): Boolean =
    haystack.contains(query.lowercase())

/** True when [note] matches [query] in title, content or a tag name. */
fun noteMatchesSearchText(note: Note, query: String): Boolean =
    haystackMatches(noteSearchHaystack(note), query)

/**
 * What the list shows from a precomputed [indexed] haystack list: narrowed
 * to notes carrying [tag] (single-select, case-insensitive; null = All),
 * then to those matching [query] (blank = no search). Order is preserved.
 * The two compose with AND: picking a tag and typing a query searches
 * within that tag.
 */
fun filterIndexedNotes(
    indexed: List<Pair<Note, String>>,
    query: String,
    tag: String?,
): List<Note> {
    if (query.isBlank() && tag == null) return indexed.map { it.first }
    return indexed.filter { (note, haystack) ->
        (tag == null || hasTag(note.tags, tag)) && (query.isBlank() || haystackMatches(haystack, query))
    }.map { it.first }
}

/** [filterIndexedNotes] for callers without a precomputed index (tests, previews). */
fun filterNotesBySearch(notes: List<Note>, query: String, tag: String?): List<Note> {
    if (query.isBlank() && tag == null) return notes
    return filterIndexedNotes(notes.map { it to noteSearchHaystack(it) }, query, tag)
}
