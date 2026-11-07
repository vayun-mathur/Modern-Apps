package com.vayunmathur.keyboard.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the two clipboard rules that fail quietly: what counts as sensitive (get it wrong
 * and a password is drawn in the clear) and what reaches disk (get it wrong and the password
 * outlives the session).
 */
class ClipboardTest {

    private fun text(id: Long, body: String, sensitive: Boolean = false) =
        ClipItem(id = id, text = body, sensitive = sensitive)

    private fun image(id: Long) = ClipItem(id = id, imagePath = "/tmp/$id.png", mimeType = "image/png")

    // --- sensitivity ---

    @Test
    fun `password-shaped tokens are flagged`() {
        assertTrue(ClipboardStore.looksSensitive("Tr0ub4dor&3"))
        assertTrue(ClipboardStore.looksSensitive("hunter2"))
        assertTrue(ClipboardStore.looksSensitive("s3cret-token"))
    }

    @Test
    fun `ordinary prose and short words are not`() {
        assertFalse(ClipboardStore.looksSensitive("correct horse battery staple"))
        assertFalse(ClipboardStore.looksSensitive("Meet me at six"))
        assertFalse(ClipboardStore.looksSensitive("hey"))
        assertFalse(ClipboardStore.looksSensitive("keyboard"))
        assertFalse(ClipboardStore.looksSensitive(""))
    }

    /** Copied far more often than passwords, and password-shaped by every other measure. */
    @Test
    fun `urls and email addresses are exempt`() {
        assertFalse(ClipboardStore.looksSensitive("https://example.com/a?b=1"))
        assertFalse(ClipboardStore.looksSensitive("someone@example.com"))
    }

    @Test
    fun `something longer than a credential is not one`() {
        assertFalse(ClipboardStore.looksSensitive("a1" + "x".repeat(80)))
    }

    /** PINs, 2FA codes and card numbers are digit runs, often grouped with spaces/dashes. */
    @Test
    fun `pins and card numbers are flagged`() {
        assertTrue(ClipboardStore.looksSensitive("123456"))
        assertTrue(ClipboardStore.looksSensitive("1234"))
        assertTrue(ClipboardStore.looksSensitive("4111 1111 1111 1111"))
        assertTrue(ClipboardStore.looksSensitive("123-456"))
    }

    @Test
    fun `short digit runs that are clearly not pins are not`() {
        assertFalse(ClipboardStore.looksSensitive("123"))
        assertFalse(ClipboardStore.looksSensitive("1".repeat(25)))
    }

    // --- history ---

    @Test
    fun `the history is capped, newest first, and dropped clips are reported`() {
        val dropped = mutableListOf<ClipItem>()
        val items = (1..ClipboardStore.MAX_ITEMS + 5).map { text(it.toLong(), "clip $it") }
        val kept = ClipboardStore.cap(items) { dropped.add(it) }
        assertEquals(ClipboardStore.MAX_ITEMS, kept.size)
        assertEquals(items.first(), kept.first())
        assertEquals(5, dropped.size)
    }

    /** Images are real files on disk, so they get a much tighter cap of their own. */
    @Test
    fun `images are capped separately without evicting text`() {
        val items = (1..ClipboardStore.MAX_IMAGES + 4).map { image(it.toLong()) } +
            listOf(text(100, "still here"))
        val kept = ClipboardStore.cap(items)
        assertEquals(ClipboardStore.MAX_IMAGES, kept.count { it.isImage })
        assertTrue(kept.any { it.text == "still here" })
    }

    @Test
    fun `re-copying something moves it back to the front instead of duplicating it`() {
        val store = ClipboardStore(java.io.File("/tmp/clip-test-does-not-exist"))
        assertEquals(text(1, "one"), store.add(text(1, "one")))
        store.add(text(2, "two"))
        assertEquals(null, store.add(text(3, "one")), "a re-copy is not a new clip")
        assertEquals(listOf("one", "two"), store.items.map { it.text })
    }

    /** The same text copied from a password field must not stay plain in the history. */
    @Test
    fun `re-copying as sensitive upgrades the existing entry`() {
        val store = ClipboardStore(java.io.File("/tmp/clip-test-does-not-exist"))
        store.add(text(1, "correct horse battery staple"))
        assertEquals(null, store.add(text(2, "correct horse battery staple", sensitive = true)))
        assertEquals(1, store.items.size)
        assertTrue(store.items.single().sensitive)
        // And it therefore stays out of the persisted form.
        assertFalse(ClipboardStore.encode(store.items).contains("correct horse"))
        // A later plain re-copy never downgrades it back.
        store.add(text(3, "correct horse battery staple"))
        assertTrue(store.items.single().sensitive)
    }

    @Test
    fun `ids are unique and monotonic even within one millisecond`() {
        val ids = (1..1000).map { ClipboardStore.nextId() }
        assertEquals(ids.size, ids.toSet().size, "duplicate clip id")
        assertTrue(ids.zipWithNext().all { (a, b) -> b > a }, "ids must be monotonic")
    }

    // --- persistence ---

    @Test
    fun `clips round-trip through json`() {
        val items = listOf(text(1, "hello"), image(2))
        val restored = ClipboardStore.decode(ClipboardStore.encode(items))
        assertEquals(items, restored)
    }

    @Test
    fun `the history persists to its state file and restores from it`() {
        val dir = kotlin.io.path.createTempDirectory("clips").toFile()
        try {
            val store = ClipboardStore(dir)
            store.add(text(1, "hello"))
            store.add(text(2, "world"))
            store.persistState()
            val restored = ClipboardStore(dir)
            restored.restoreState()
            assertEquals(listOf("world", "hello"), restored.items.map { it.text })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a missing state file restores to an empty history rather than throwing`() {
        val store = ClipboardStore(java.io.File("/tmp/clip-test-does-not-exist"))
        store.restoreState()
        assertEquals(emptyList(), store.items)
    }

    @Test
    fun `sensitive clips never reach the serialized form`() {
        val items = listOf(text(1, "hunter2", sensitive = true), text(2, "plain"))
        val encoded = ClipboardStore.encode(items)
        assertFalse(encoded.contains("hunter2"))
        assertEquals(listOf("plain"), ClipboardStore.decode(encoded).map { it.text })
    }

    @Test
    fun `unreadable storage decodes to an empty history rather than throwing`() {
        assertEquals(emptyList(), ClipboardStore.decode(null))
        assertEquals(emptyList(), ClipboardStore.decode(""))
        assertEquals(emptyList(), ClipboardStore.decode("not json"))
    }

    @Test
    fun `previews collapse whitespace so a multi-line clip stays one line`() {
        assertEquals("one two three", text(1, " one\ntwo\t three ").preview)
    }
}
