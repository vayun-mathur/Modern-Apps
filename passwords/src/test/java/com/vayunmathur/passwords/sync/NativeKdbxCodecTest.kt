package com.vayunmathur.passwords.sync

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeKdbxCodecTest {

    @Test fun roundTripsEntriesThroughRealKdbx() {
        val entries = listOf(
            mapOf("Title" to "GitHub", "UserName" to "octocat", "Password" to "s3cret", "_SyncId" to "sync-1"),
            mapOf("Title" to "Mail", "UserName" to "alice", "Password" to "pw2"),
        )
        val bytes = NativeKdbxCodec.write("vault-pw", entries)
        assertNotNull(bytes)
        val restored = NativeKdbxCodec.read("vault-pw", bytes)
        assertNotNull(restored)
        assertEquals(2, restored.size)
        val github = restored.first { it["Title"] == "GitHub" }
        assertEquals("octocat", github["UserName"])
        assertEquals("s3cret", github["Password"])
    }

    @Test fun wrongPasswordFailsToRead() {
        val bytes = NativeKdbxCodec.write("right", listOf(mapOf("Title" to "A")))
        assertNotNull(bytes)
        assertNull(NativeKdbxCodec.read("wrong", bytes))
    }

    @Test fun garbageBytesFailToRead() {
        assertNull(NativeKdbxCodec.read("pw", byteArrayOf(1, 2, 3, 4)))
    }
}
