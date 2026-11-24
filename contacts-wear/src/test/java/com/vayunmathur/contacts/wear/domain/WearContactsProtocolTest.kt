package com.vayunmathur.contacts.wear.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class WearContactsProtocolTest {

    @Test
    fun `golden wire json is stable across both apps`() {
        // The wear module pins these exact strings in its own copy of this test.
        val chunk = WearContactsChunk(7L, 0, listOf(WearContact(1L, "Ada", listOf("123"), emptyList(), true)))
        assertEquals(
            """{"generation":7,"index":0,"contacts":[{"id":1,"name":"Ada","phones":["123"],"isFavorite":true}]}""",
            WearContactsProtocol.json.encodeToString(chunk),
        )
        val manifest = WearContactsManifest(7L, 2, 3000, 1000, true, 99L)
        assertEquals(
            """{"generation":7,"chunkCount":2,"totalContacts":3000,"includedContacts":1000,"truncated":true,"sentAtMillis":99}""",
            WearContactsProtocol.json.encodeToString(manifest),
        )
        assertEquals("/contacts/snapshot/chunk/3", WearContactsProtocol.chunkPath(3))
        assertEquals(3, WearContactsProtocol.chunkIndexFromPath("/contacts/snapshot/chunk/3"))
        assertNull(WearContactsProtocol.chunkIndexFromPath(WearContactsProtocol.MANIFEST_PATH))
    }

    @Test
    fun `cap keeps favourites first then A-Z and says it truncated`() {
        val many = (1..1_005).map { WearContact(it.toLong(), "Person %04d".format(it)) } +
            WearContact(9_999L, "Zed Favourite", isFavorite = true)
        val selected = WearSnapshotBuilder.select(many)
        assertEquals(WearContactsProtocol.MAX_CONTACTS, selected.size)
        assertEquals("Zed Favourite", selected.first().name)
        assertEquals("Person 0001", selected[1].name)
        val manifest = WearSnapshotBuilder.manifest(1L, many.size, selected, 1, 0L)
        assertTrue(manifest.truncated)
        assertEquals(many.size, manifest.totalContacts)
    }

    @Test
    fun `per-contact lists and fields are capped`() {
        val raw = WearContact(
            id = 1L,
            name = "N".repeat(200),
            phones = listOf("1", "2", "3", "4", "1", "  "),
            emails = listOf("a@x", "b@x", "c@x", "d@x"),
        )
        val selected = WearSnapshotBuilder.select(listOf(raw)).single()
        assertEquals(WearContactsProtocol.MAX_NAME_CHARS, selected.name.length)
        assertEquals(listOf("1", "2", "3"), selected.phones)
        assertEquals(3, selected.emails.size)
    }

    @Test
    fun `chunks stay under the byte cap and cover every contact once`() {
        val selected = WearSnapshotBuilder.select(
            (1..600).map {
                WearContact(it.toLong(), "Person Number $it With A Fairly Long Name", listOf("+1 415 555 %04d".format(it)), listOf("person$it@example.com"))
            },
        )
        val chunks = WearSnapshotBuilder.chunk(42L, selected)
        assertTrue(chunks.size > 1, "600 contacts must chunk")
        for (chunk in chunks) {
            val bytes = WearContactsProtocol.json.encodeToString(chunk).encodeToByteArray().size
            assertTrue(bytes <= WearContactsProtocol.MAX_CHUNK_BYTES, "chunk ${chunk.index} is $bytes bytes")
        }
        assertEquals(selected, chunks.flatMap { it.contacts })
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
        assertTrue(chunks.all { it.generation == 42L })
    }

    @Test
    fun `search matches name phone digits and email by token`() {
        val contacts = listOf(
            WearContact(1L, "Ada Lovelace", listOf("+1 (415) 555-0100"), listOf("ada@example.com")),
            WearContact(2L, "Grace Hopper", listOf("+1 202 555 0199")),
        )
        assertEquals(contacts, WearContactSearch.filter(contacts, ""))
        assertEquals(listOf(contacts[0]), WearContactSearch.filter(contacts, "ada"))
        assertEquals(listOf(contacts[0]), WearContactSearch.filter(contacts, "415 0100"))
        assertEquals(listOf(contacts[0]), WearContactSearch.filter(contacts, "ADA example"))
        assertEquals(emptyList(), WearContactSearch.filter(contacts, "hopper 0100"))
    }

    @Test
    fun `capability absent means no watch presence and no dead buttons`() {
        assertEquals(WearPresence.NO_WATCH, WearPresencePolicy.presence(0, null))
        assertFalse(WearPresencePolicy.statusText(WearPresence.NO_WATCH, null).contains("synced", ignoreCase = true))
        val contact = WearContact(1L, "Ada", listOf("123"), listOf("a@x"))
        // No handlers, no phone: nothing is offered.
        assertEquals(
            emptyList(),
            WearActionPolicy.actionsFor(contact, hasTelephony = false, hasSmsHandler = false, hasEmailHandler = false, phoneReachable = false),
        )
        // Phone reachable only: handoffs, never a fake on-watch call.
        assertEquals(
            listOf(WearAction.CALL_VIA_PHONE, WearAction.MESSAGE_VIA_PHONE),
            WearActionPolicy.actionsFor(contact, hasTelephony = false, hasSmsHandler = false, hasEmailHandler = false, phoneReachable = true),
        )
        // Telephony watch prefers its own call; email needs a handler.
        assertEquals(
            listOf(WearAction.CALL_ON_WATCH),
            WearActionPolicy.actionsFor(contact, hasTelephony = true, hasSmsHandler = false, hasEmailHandler = false, phoneReachable = false),
        )
    }
}
