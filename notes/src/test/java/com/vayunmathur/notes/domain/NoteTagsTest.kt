package com.vayunmathur.notes.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins the pure tag rules (#913): normalisation, rename/merge, delete, counts, suggestions. */
class NoteTagsTest {

    // ---- normalizeTag / normalizeTags ----

    @Test fun `trims and collapses internal whitespace`() {
        assertEquals("home office", normalizeTag("  home \t\n  office  "))
        assertEquals("a b c", normalizeTag("a  b\u00a0c".replace('\u00a0', ' ')))
    }

    @Test fun `blank input is dropped`() {
        assertNull(normalizeTag(""))
        assertNull(normalizeTag("   \t\n"))
        assertEquals(listOf("x"), normalizeTags(listOf("", " ", "x", "\n")))
    }

    @Test fun `long tags are cut to the limit and re-trimmed`() {
        val long = "a".repeat(MAX_TAG_LENGTH + 10)
        assertEquals(MAX_TAG_LENGTH, normalizeTag(long)!!.length)
        // A cut landing on a space must not leave a trailing space.
        val spaced = "b".repeat(MAX_TAG_LENGTH - 1) + " tail"
        assertEquals("b".repeat(MAX_TAG_LENGTH - 1), normalizeTag(spaced))
    }

    @Test fun `cut never splits a surrogate pair`() {
        val tag = "a".repeat(MAX_TAG_LENGTH - 1) + "😀" + "zz"
        val cut = normalizeTag(tag)!!
        assertEquals("a".repeat(MAX_TAG_LENGTH - 1), cut)
        assertFalse(cut.last().isHighSurrogate())
        // An emoji that fits whole is kept whole.
        assertEquals("😀 work", normalizeTag(" 😀  work "))
    }

    @Test fun `dedupes case-insensitively keeping the first spelling and order`() {
        assertEquals(listOf("Work", "home"), normalizeTags(listOf("Work", "home", "work", "WORK ", " Home")))
    }

    @Test fun `keeps at most the per-note maximum`() {
        val many = (1..MAX_TAGS_PER_NOTE + 5).map { "t$it" }
        val out = normalizeTags(many)
        assertEquals(MAX_TAGS_PER_NOTE, out.size)
        assertEquals("t1", out.first())
        assertEquals("t$MAX_TAGS_PER_NOTE", out.last())
        // Duplicates do not use up the budget.
        val withDupes = listOf("a", "A", "a") + (1..MAX_TAGS_PER_NOTE).map { "u$it" }
        assertEquals(MAX_TAGS_PER_NOTE, normalizeTags(withDupes).size)
        assertEquals("a", normalizeTags(withDupes).first())
    }

    @Test fun `normalisation is idempotent`() {
        val raw = listOf("  Work ", "work", "a  b", "", "x".repeat(60))
        val once = normalizeTags(raw)
        assertEquals(once, normalizeTags(once))
    }

    // ---- canonical / has ----

    @Test fun `typed tag adopts an existing spelling`() {
        assertEquals("Work", canonicalTag(" work ", listOf("Home", "Work")))
        assertEquals("new tag", canonicalTag("new   tag", listOf("Work")))
        assertNull(canonicalTag("  ", listOf("Work")))
    }

    @Test fun `hasTag ignores case`() {
        assertTrue(hasTag(listOf("Work"), "work"))
        assertFalse(hasTag(listOf("Work"), "wor"))
        assertFalse(hasTag(emptyList(), "work"))
    }

    // ---- rename / merge / delete ----

    @Test fun `rename replaces in place`() {
        assertEquals(listOf("a", "office", "c"), withTagRenamed(listOf("a", "work", "c"), "Work", "office"))
    }

    @Test fun `rename into an existing tag merges and keeps the existing spelling`() {
        // existing target before the source: target stays, source dropped
        assertEquals(listOf("Home", "a"), withTagRenamed(listOf("Home", "a", "house"), "house", "home"))
        // existing target after the source: merged at the source's position, first spelling wins
        assertEquals(listOf("home", "a"), withTagRenamed(listOf("house", "a", "Home"), "house", "home"))
    }

    @Test fun `rename can change only the case`() {
        assertEquals(listOf("Work"), withTagRenamed(listOf("work"), "work", "Work"))
    }

    @Test fun `rename is a no-op when absent or the target is blank`() {
        val tags = listOf("a", "b")
        assertEquals(tags, withTagRenamed(tags, "zzz", "c"))
        assertEquals(tags, withTagRenamed(tags, "a", "   "))
    }

    @Test fun `rename normalises the target`() {
        assertEquals(listOf("new name"), withTagRenamed(listOf("old"), "old", "  new    name "))
    }

    @Test fun `delete removes case-insensitively and leaves others`() {
        assertEquals(listOf("a", "c"), withoutTag(listOf("a", "B", "c"), "b"))
        assertEquals(listOf("a"), withoutTag(listOf("a"), "missing"))
    }

    // ---- counts ----

    @Test fun `counts sort by count then name and group case-insensitively`() {
        val counts = tagCounts(
            listOf(
                listOf("work", "home"),
                listOf("Work", "travel"),
                listOf("work", "Home", "alpha"),
                emptyList(),
            ),
        )
        assertEquals(
            listOf(TagCount("work", 3), TagCount("home", 2), TagCount("alpha", 1), TagCount("travel", 1)),
            counts,
        )
    }

    @Test fun `display spelling is the most used one, ties to the first seen`() {
        assertEquals("Work", tagCounts(listOf(listOf("Work"), listOf("Work"), listOf("work"))).single().name)
        assertEquals("b", tagCounts(listOf(listOf("b"), listOf("B"))).single().name)
    }

    @Test fun `a note counts once even if it repeats a tag`() {
        assertEquals(listOf(TagCount("x", 1)), tagCounts(listOf(listOf("x", "X"))))
        assertTrue(tagCounts(emptyList()).isEmpty())
    }

    // ---- suggestions ----

    @Test fun `suggestions keep usage order, prefer prefixes and skip existing`() {
        val known = listOf("work", "homework", "home", "wishlist")
        assertEquals(listOf("work", "homework"), suggestTags(known, "wor", exclude = emptyList(), limit = 5))
        assertEquals(listOf("homework", "home"), suggestTags(known, "ho", exclude = emptyList(), limit = 5))
        assertEquals(listOf("homework"), suggestTags(known, "wor", exclude = listOf("WORK"), limit = 5))
    }

    @Test fun `blank query suggests top tags and the limit applies`() {
        val known = listOf("a", "b", "c")
        assertEquals(listOf("a", "b"), suggestTags(known, " ", exclude = emptyList(), limit = 2))
    }

    @Test fun `an exact match is not suggested back`() {
        assertEquals(listOf("work trip"), suggestTags(listOf("work", "work trip"), "Work", emptyList(), 5))
    }
}
