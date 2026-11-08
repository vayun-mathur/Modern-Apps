package com.vayunmathur.notes.data

import com.vayunmathur.notes.ui.noteMatchesSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the list search predicate from `NotesListScreen` ([noteMatchesSearch]):
 * a blank query keeps every row, otherwise a row matches when the query
 * appears in its title or plain-text content, case-insensitively. Internal
 * fields (id, position, blocks JSON, syncId, updatedAt, deleted) must never
 * make a note match.
 */
class NoteSearchFilterTest {

    private fun filter(notes: List<Note>, query: String): List<Note> {
        if (query.isBlank()) return notes
        return notes.filter { noteMatchesSearch(it, query) }
    }

    private fun note(id: Long, title: String, content: String) =
        Note(id = id, title = title, content = content)

    @Test
    fun `blank query keeps every note in order`() {
        val notes = listOf(note(1, "a", "x"), note(2, "b", "y"))
        assertEquals(notes, filter(notes, ""))
        assertEquals(notes, filter(notes, "   "))
    }

    @Test
    fun `match is case-insensitive over title and content`() {
        val notes = listOf(
            note(1, "Shopping", "eggs"),
            note(2, "Work", "SHOPPING list"),
            note(3, "Other", "nothing"),
        )
        assertEquals(listOf(1L, 2L), filter(notes, "shopping").map { it.id })
        assertEquals(listOf(1L, 2L), filter(notes, "SHOPPING").map { it.id })
    }

    @Test
    fun `no match returns an empty list`() {
        val notes = listOf(note(1, "a", "b"))
        assertEquals(emptyList(), filter(notes, "zzz"))
    }

    @Test
    fun `empty note list stays empty for any query`() {
        assertEquals(emptyList(), filter(emptyList(), "a"))
        assertEquals(emptyList(), filter(emptyList(), ""))
    }

    @Test
    fun `unicode queries match unicode titles`() {
        val notes = listOf(
            note(1, "記メモ", "plain"),
            note(2, "plain", "مرحبا world"),
            note(3, "emoji 😀 title", "x"),
        )
        assertEquals(listOf(1L), filter(notes, "記").map { it.id })
        assertEquals(listOf(2L), filter(notes, "مرحبا").map { it.id })
        assertEquals(listOf(3L), filter(notes, "😀").map { it.id })
    }

    @Test
    fun `query with surrounding spaces matches literally like the screen does`() {
        val notes = listOf(note(1, "a b", "c"))
        assertEquals(emptyList(), filter(notes, " a "))
        assertEquals(listOf(1L), filter(notes, "a b").map { it.id })
    }

    @Test
    fun `multiline content is searchable through the newline`() {
        val notes = listOf(note(1, "t", "line one\nline two"))
        assertTrue(filter(notes, "line two").isNotEmpty())
        assertTrue(filter(notes, "LINE ONE").isNotEmpty())
    }

    @Test
    fun `internal fields never make a note match`() {
        val withImage = Note(id = 7, title = "plain", content = "")
            .withBody(NoteBody(listOf(NoteBlock.Text("words", "t1"), NoteBlock.Image("deadbeef.jpg", id = "i1"))))
            .copy(syncId = "sync-abc", updatedAt = 1_700_000_000_000, position = 42.0)
        val notes = listOf(withImage)
        // Each of these appears in the old data-class toString() rendering
        // (blocks JSON, syncId, timestamps, position, field names) but in
        // neither the title nor the content.
        for (query in listOf("image", "deadbeef", "sync-abc", "1700000000000", "42", "deleted", "markdown")) {
            assertEquals(emptyList(), filter(notes, query), "query=$query must not match")
        }
        // The visible text still matches.
        assertEquals(listOf(7L), filter(notes, "words").map { it.id })
        assertEquals(listOf(7L), filter(notes, "plain").map { it.id })
    }
}
