package com.vayunmathur.email.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins [OutboxSendWorker] JSON codecs: corrupt stored JSON must degrade to empty, never crash the sender. */
class OutboxCodecTest {

    @Test
    fun `paths round trip and preserve order`() {
        val paths = listOf("/a/one.pdf", "/b/two.png")
        assertEquals(paths, OutboxSendWorker.decodePaths(OutboxSendWorker.encodePaths(paths)))
        assertEquals(emptyList(), OutboxSendWorker.decodePaths(OutboxSendWorker.encodePaths(emptyList())))
    }

    @Test
    fun `corrupt path json decodes to empty`() {
        assertTrue(OutboxSendWorker.decodePaths("not json").isEmpty())
        assertTrue(OutboxSendWorker.decodePaths("").isEmpty())
        assertTrue(OutboxSendWorker.decodePaths("{}").isEmpty())
    }

    @Test
    fun `inline entries round trip all fields`() {
        val entries = listOf(
            OutboxSendWorker.Companion.InlineJsonEntry(cid = "c1", path = "/p.png", mime = "image/png", name = "p.png"),
        )
        val decoded = OutboxSendWorker.decodeInline(OutboxSendWorker.encodeInline(entries))
        assertEquals(entries, decoded)
        assertTrue(OutboxSendWorker.decodeInline("[]").isEmpty())
        assertTrue(OutboxSendWorker.decodeInline("bogus").isEmpty())
    }
}
