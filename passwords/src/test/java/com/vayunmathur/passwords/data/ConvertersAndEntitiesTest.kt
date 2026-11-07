package com.vayunmathur.passwords.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConvertersAndEntitiesTest {

    @Test fun websitesRoundTrip() {
        val sites = listOf("a.com", "b.com/x")
        assertEquals(sites, Converters.toWebsites(Converters.fromWebsites(sites)))
    }

    @Test fun websitesNullAndEmpty() {
        assertEquals("", Converters.fromWebsites(null))
        assertEquals("", Converters.fromWebsites(emptyList()))
        assertEquals(emptyList(), Converters.toWebsites(null))
        assertEquals(emptyList(), Converters.toWebsites(""))
    }

    @Test fun syncIdsAre32HexCharsAndUnique() {
        val a = newSyncId()
        val b = newSyncId()
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(a, b)
    }

    @Test fun snapshotKindConstants() {
        assertEquals("password", SyncSnapshot.KIND_PASSWORD)
        assertEquals("passkey", SyncSnapshot.KIND_PASSKEY)
        val snap = SyncSnapshot("s1", SyncSnapshot.KIND_PASSWORD, "h", 1, 2, 3)
        assertEquals("s1", snap.syncId)
        assertEquals(3L, snap.lastSyncedAt)
    }

    @Test fun passwordDefaults() {
        val pw = Password(name = "A")
        assertEquals("", pw.username)
        assertEquals(null, pw.totpSecret)
        assertTrue(pw.syncId.isNotBlank())
    }
}
