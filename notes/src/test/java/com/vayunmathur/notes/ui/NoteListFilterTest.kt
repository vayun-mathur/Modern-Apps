package com.vayunmathur.notes.ui

import com.vayunmathur.notes.data.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the list's tag filter and tag-aware search ([filterNotes], [noteMatchesSearch]) from #913. */
class NoteListFilterTest {
    private val notes = listOf(
        Note(id = 1, title = "Standup", content = "agenda", position = 1.0, tags = listOf("Work", "daily")),
        Note(id = 2, title = "Groceries", content = "eggs", position = 2.0, tags = listOf("home")),
        Note(id = 3, title = "Roadmap", content = "q3 plan", position = 3.0, tags = listOf("work")),
        Note(id = 4, title = "Untagged", content = "nothing", position = 4.0),
    )

    private fun ids(list: List<Note>) = list.map { it.id }

    @Test fun `no tag and no query keeps everything in order`() {
        assertEquals(notes, filterNotes(notes, "", null))
        assertEquals(notes, filterNotes(notes, "   ", null))
    }

    @Test fun `tag filter is case-insensitive and keeps order`() {
        assertEquals(listOf(1L, 3L), ids(filterNotes(notes, "", "WORK")))
        assertEquals(listOf(2L), ids(filterNotes(notes, "", "home")))
        assertEquals(emptyList(), ids(filterNotes(notes, "", "missing")))
    }

    @Test fun `tag filter matches whole tags, not substrings`() {
        assertEquals(emptyList(), ids(filterNotes(notes, "", "wor")))
    }

    @Test fun `search matches tag names`() {
        assertTrue(noteMatchesSearch(notes[0], "dail"))
        assertTrue(noteMatchesSearch(notes[1], "HOME"))
        assertEquals(listOf(1L, 3L), ids(filterNotes(notes, "work", null)))
    }

    @Test fun `search still matches title and content`() {
        assertEquals(listOf(2L), ids(filterNotes(notes, "eggs", null)))
        assertEquals(listOf(3L), ids(filterNotes(notes, "roadmap", null)))
    }

    @Test fun `tag and search combine with AND`() {
        assertEquals(listOf(3L), ids(filterNotes(notes, "plan", "work")))
        assertEquals(emptyList(), ids(filterNotes(notes, "eggs", "work")))
    }
}
