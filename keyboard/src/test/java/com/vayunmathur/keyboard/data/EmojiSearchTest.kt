package com.vayunmathur.keyboard.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards emoji search ranking. The set is ~1900 emoji sharing a small vocabulary of
 * keywords, so ordering is the whole feature: "cat" matches over a hundred entries and the
 * cat has to be the first of them.
 */
class EmojiSearchTest {

    private val data = EmojiData.build(
        EmojiData.parse(
            sequenceOf(
                "# a comment",
                "😀\tSmileys & Emotion\tgrinning face\thappy|smile|grin",
                "🐱\tAnimals & Nature\tcat face\tanimal|kitten|pet",
                "😹\tSmileys & Emotion\tcat with tears of joy\tanimal|laugh|lol",
                "🎂\tFood & Drink\tbirthday cake\tcake|celebration|dessert",
                "🐶\tAnimals & Nature\tdog face\tanimal|pet|puppy",
                "",
            ),
        ),
    )

    @Test
    fun `a name prefix outranks a later word and a keyword`() {
        // "cat face" starts with it; "cat with tears of joy" does too; the dog only lists
        // "animal" alongside them, so it must not appear at all.
        assertEquals(listOf("🐱", "😹"), data.search("cat"))
    }

    @Test
    fun `a word inside the name still matches`() {
        assertEquals(listOf("🐱", "🐶"), data.search("face").filter { it != "😀" })
        assertTrue("😀" in data.search("face"))
    }

    @Test
    fun `keyword matches come last`() {
        // "pet" is a keyword of both animals and starts no name.
        assertEquals(listOf("🐱", "🐶"), data.search("pet"))
    }

    @Test
    fun `search is case-insensitive and trims`() {
        assertEquals(data.search("cat"), data.search("  CAT "))
    }

    @Test
    fun `a blank query matches nothing`() {
        assertTrue(data.search("").isEmpty())
        assertTrue(data.search("   ").isEmpty())
        assertTrue(data.search("cat", limit = 0).isEmpty())
    }

    @Test
    fun `no match returns nothing rather than everything`() {
        assertTrue(data.search("xyzzy").isEmpty())
    }

    @Test
    fun `the limit is respected across ranking buckets`() {
        assertEquals(1, data.search("animal", limit = 1).size)
    }

    @Test
    fun `groups become tabs in palette order, with the two people groups merged`() {
        assertEquals(listOf("😀", "🐶", "🍔"), data.categories.map { it.label })
        assertEquals(listOf("🐱", "🐶"), data.categories[1].emojis)
    }

    @Test
    fun `parsing skips comments, blanks and short lines`() {
        val parsed = EmojiData.parse(sequenceOf("# header", "", "broken\tline", "😀\tSymbols\tname\t"))
        assertEquals(1, parsed.size)
        assertEquals(emptyList(), parsed[0].second.keywords)
    }

    @Test
    fun `an unusable asset falls back to the curated set`() {
        val empty = EmojiData.build(emptyList())
        assertEquals(EmojiData.BUILTIN.categories, empty.categories)
        assertFalse(EmojiData.BUILTIN.categories.isEmpty())
    }

    // --- recents ---

    @Test
    fun `the newest pick goes first and is never listed twice`() {
        var recents = RecentEmoji.add(emptyList(), "😀")
        recents = RecentEmoji.add(recents, "🐱")
        recents = RecentEmoji.add(recents, "😀")
        assertEquals(listOf("😀", "🐱"), recents)
    }

    @Test
    fun `recents are capped at a gridful`() {
        val recents = (1..RecentEmoji.MAX + 10)
            .fold(emptyList<String>()) { acc, i -> RecentEmoji.add(acc, "e$i") }
        assertEquals(RecentEmoji.MAX, recents.size)
        assertEquals("e${RecentEmoji.MAX + 10}", recents.first())
    }

    @Test
    fun `recents round-trip through storage`() {
        val recents = listOf("😀", "👍🏽", "❤️", "🏳️‍🌈")
        assertEquals(recents, RecentEmoji.decode(RecentEmoji.encode(recents)))
        assertEquals(emptyList(), RecentEmoji.decode(null))
        assertEquals(emptyList(), RecentEmoji.decode(""))
    }
}
