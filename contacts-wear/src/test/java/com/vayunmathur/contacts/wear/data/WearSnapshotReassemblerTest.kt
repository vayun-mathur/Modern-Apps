package com.vayunmathur.contacts.wear.data


import com.vayunmathur.contacts.wear.domain.WearContact
import com.vayunmathur.contacts.wear.domain.WearContactsChunk
import com.vayunmathur.contacts.wear.domain.WearContactsManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WearSnapshotReassemblerTest {

    private val manifest = WearContactsManifest(5L, 2, 3, 3, false, 1L)
    private val c0 = WearContactsChunk(5L, 0, listOf(WearContact(1L, "A"), WearContact(2L, "B")))
    private val c1 = WearContactsChunk(5L, 1, listOf(WearContact(3L, "C")))

    @Test
    fun `incomplete generations apply nothing, complete applies in order`() {
        val r = WearSnapshotReassembler()
        assertNull(r.onChunk(c0))
        assertNull(r.onManifest(manifest))
        val done = r.onChunk(c1)
        assertEquals(listOf("A", "B", "C"), done?.contacts?.map { it.name })
    }

    @Test
    fun `stale generation chunks are ignored`() {
        val r = WearSnapshotReassembler()
        r.onManifest(manifest)
        assertNull(r.onChunk(c0.copy(generation = 4L)))
        assertNull(r.onChunk(c1.copy(generation = 4L)))
        r.onChunk(c0)
        assertEquals(3, r.onChunk(c1)?.contacts?.size)
    }
}
