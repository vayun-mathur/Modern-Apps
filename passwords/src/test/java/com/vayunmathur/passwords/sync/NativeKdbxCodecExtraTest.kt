package com.vayunmathur.passwords.sync

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeKdbxCodecExtraTest {

    @Test fun specialCharacterEntriesRoundTrip() {
        val entries = listOf(
            mapOf("Title" to "Git Hub (work) #1", "Password" to "p@ss:w0rd!"),
            mapOf("Title" to "Café", "UserName" to "rené", "Password" to "päss"),
        )
        val bytes = NativeKdbxCodec.write("vault-pw", entries)
        assertNotNull(bytes)
        val restored = NativeKdbxCodec.read("vault-pw", bytes)
        assertNotNull(restored)
        assertEquals(2, restored.size)
        assertEquals("p@ss:w0rd!", restored.first { it["Title"] == "Git Hub (work) #1" }["Password"])
        assertEquals("päss", restored.first { it["Title"] == "Café" }["Password"])
    }

    @Test fun emptyVaultRoundTrips() {
        val bytes = NativeKdbxCodec.write("vault-pw", emptyList())
        assertNotNull(bytes)
        assertEquals(0, NativeKdbxCodec.read("vault-pw", bytes)!!.size)
    }

    @Test fun manyEntriesRoundTripWithSyncFields() {
        val entries = (1..25).map { i ->
            mapOf("Title" to "Entry $i", "UserName" to "user$i", "Password" to "pw$i", "_SyncId" to "sync-$i")
        }
        val restored = NativeKdbxCodec.read("vault-pw", NativeKdbxCodec.write("vault-pw", entries)!!)!!
        assertEquals(25, restored.size)
        assertEquals("user25", restored.first { it["_SyncId"] == "sync-25" }["UserName"])
    }
}
