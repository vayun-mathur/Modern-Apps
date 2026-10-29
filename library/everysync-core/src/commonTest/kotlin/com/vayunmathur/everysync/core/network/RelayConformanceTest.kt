package com.vayunmathur.everysync.core.network

import com.vayunmathur.everysync.core.crypto.AesGcm
import com.vayunmathur.everysync.core.platform.MemorySecureStore
import com.vayunmathur.everysync.core.platform.RelayCursorStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.io.encoding.Base64

/**
 * Relay conformance against a fake in-memory transport: verifies channel
 * names, `B64(AES_GCM)` record shape, double-wrapped roster/epoch envelopes
 * are opaque strings here (policy tested separately), PQC-sealed invites
 * round-trip through the envelope codec, `everysync://join` link parse, and
 * the three invariants (null-on-failed-pull abort, tombstone-only delete
 * lives in RelayMerge tests, keys never appear in share links).
 *
 * Live-relay conformance (against staging `api.maproj.org/personal`, migrated
 * from findfamily.cc — findfamily.cc remains valid for backward compatibility)
 * reuses these same assertions with a Ktor `RelayTransport` actual — see Phase 1F.
 */
class RelayConformanceTest {

    private class FakeTransport(
        override val deviceId: String = "device-a",
        var failPull: Boolean = false,
    ) : RelayTransport {
        // Deterministic per-device bundles so two instances agree on keys.
        override val publicBundle: ByteArray = "bundle-$deviceId".encodeToByteArray()
        val log = mutableMapOf<String, MutableList<String>>()
        var seq = 0

        override suspend fun register(): Boolean = true
        override suspend fun getKey(id: String): ByteArray? = "bundle-$id".encodeToByteArray()

        override suspend fun appendRaw(channel: String, items: List<String>): Int? {
            log.getOrPut(channel) { mutableListOf() }.addAll(items)
            seq += items.size
            return seq
        }

        override suspend fun pullRaw(channel: String, since: Int): List<String>? {
            if (failPull) return null
            return log[channel]?.drop(since) ?: emptyList()
        }

        override fun subscribe(channel: String): Flow<String> =
            flowOf(*(log[channel]?.toTypedArray() ?: emptyArray()))

        // Identity crypto is stubbed: seal/unseal are symmetric here, which is
        // enough to verify envelope shape (real PQC lives in Rust + vectors).
        override suspend fun sign(data: ByteArray): ByteArray = ("sig:" + data.decodeToString()).encodeToByteArray()
        override suspend fun verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
            signature.decodeToString() == "sig:" + data.decodeToString()
        override suspend fun seal(bundle: ByteArray, data: ByteArray): ByteArray =
            (bundle.decodeToString() + "|" + data.decodeToString()).encodeToByteArray()
        override suspend fun unseal(data: ByteArray): ByteArray {
            val s = data.decodeToString()
            // Sealed to us iff prefixed with our bundle.
            val prefix = publicBundle.decodeToString() + "|"
            return if (s.startsWith(prefix)) s.drop(prefix.length).encodeToByteArray() else data
        }

        override fun newVaultKey(): ByteArray = AesGcm.newContentKey()
        override fun newVaultId(): String = "vault-1"
    }

    @Test
    fun recordChannelNames() {
        assertEquals("passwords:v1", recordChannel("passwords", "v1"))
        assertEquals("notes:v1", recordChannel("notes", "v1"))
        assertEquals("todos:v1", recordChannel("todos", "v1"))
        assertEquals("contacts:v1", recordChannel("contacts", "v1"))
        assertEquals("calendar:v1", recordChannel("calendar", "v1"))
        assertEquals("members:v1", membersChannel("v1"))
        assertEquals("keys:v1", keysChannel("v1"))
        assertEquals("owner:v1", ownerChannel("v1"))
        assertEquals("title:v1", titleChannel("v1"))
        assertEquals("inbox:dev", inboxChannel("dev"))
        assertEquals("notes-blobs:v1", blobChannel("v1"))
    }

    @Test
    fun appendPullRecordsRoundTrip() {
        val t = FakeTransport()
        val key = t.newVaultKey()
        val channel = recordChannel("notes", "v1")
        // Must be run in a coroutine — use runTest-style manual driver.
        kotlinx.coroutines.runBlocking {
            assertEquals(2, t.appendRecords(channel, key, listOf("""{"uid":"a"}""", """{"uid":"b"}""")))
            val pulled = t.pullRecords(channel, key, 0)
            assertNotNull(pulled)
            assertEquals(listOf("""{"uid":"a"}""", """{"uid":"b"}"""), pulled.items)
            assertEquals(2, pulled.rawCount)
            assertEquals(0, pulled.undecryptable)
            assertTrue(!pulled.fullyUndecryptable)
        }
    }

    @Test
    fun recordEnvelopeIsB64Aes() {
        val t = FakeTransport()
        val key = t.newVaultKey()
        kotlinx.coroutines.runBlocking {
            t.appendRecords(recordChannel("passwords", "v1"), key, listOf("hello"))
            val raw = t.log[recordChannel("passwords", "v1")]!!.single()
            // Valid Base64 that decrypts under the key.
            val bytes = Base64.decode(raw)
            assertEquals("hello", AesGcm.decrypt(key, bytes).decodeToString())
        }
    }

    @Test
    fun failedPullIsNullNotEmpty() {
        val t = FakeTransport(failPull = true)
        kotlinx.coroutines.runBlocking {
            assertNull(t.pullRaw("notes:v1", 0))
            assertNull(t.pullRecords("notes:v1", t.newVaultKey(), 0))
        }
    }

    @Test
    fun fullyUndecryptableFlagsKeyProblem() {
        val t = FakeTransport()
        kotlinx.coroutines.runBlocking {
            t.appendRecords(recordChannel("notes", "v1"), t.newVaultKey(), listOf("secret"))
            val pulled = t.pullRecords(recordChannel("notes", "v1"), t.newVaultKey(), 0)
            assertNotNull(pulled)
            assertEquals(1, pulled.rawCount)
            assertEquals(0, pulled.items.size)
            assertEquals(1, pulled.undecryptable)
            assertTrue(pulled.fullyUndecryptable)
        }
    }

    @Test
    fun inviteJoinRequestRoundTrip() {
        val owner = FakeTransport(deviceId = "owner-dev")
        val joiner = FakeTransport(deviceId = "joiner-dev")
        // Share one log so they see each other's appends.
        val shared = mutableMapOf<String, MutableList<String>>()
        // Wire both to the shared log via delegation is complex; instead test
        // the codec shape: sendInvite seals to the recipient bundle.
        kotlinx.coroutines.runBlocking {
            // Owner invites joiner: seal uses joiner's bundle.
            val key = owner.newVaultKey()
            val ok = owner.sendInvite(
                recipientId = "joiner-dev",
                vaultId = "v1",
                dataType = "notes",
                key = key,
                title = "Shared",
                role = RelayRoles.EDITOR,
                ownerKeyB64 = "owner-bundle",
                ownerDeviceId = "owner-dev",
            )
            assertTrue(ok)
            // Invite landed on joiner's inbox channel.
            val inbox = owner.log[inboxChannel("joiner-dev")]!!
            assertEquals(1, inbox.size)
            // Joiner can unseal it (fake seal is bundle-prefixed).
            joiner.log.putAll(owner.log)
            val result = joiner.pullInvites(0)
            assertEquals(1, result.invites.size)
            assertEquals("v1", result.invites.single().vaultId)
            assertEquals("notes", result.invites.single().dataType)
        }
    }

    @Test
    fun joinLinksCarryIdsOnly() {
        val link = RelayLinks.shareLinkFor("vault-1", "owner-dev", "notes")
        assertTrue(link.startsWith(RelayLinks.SHARE_BASE))
        // Keys never appear in links: only vault/owner ids + type.
        assertTrue(!link.contains("key", ignoreCase = true))
        val parsed = RelayLinks.parseJoinLink("everysync", "join", "vault-1", "owner-dev", "notes")
        assertEquals(Triple("vault-1", "owner-dev", "notes"), parsed)
        assertNull(RelayLinks.parseJoinLink("https", "join", "vault-1", "owner-dev", "notes"))
        assertNull(RelayLinks.parseJoinLink("everysync", "join", "", "owner-dev", "notes"))
    }

    @Test
    fun rolesGatePasswordsViewer() {
        assertTrue(RelayRoles.canEdit(RelayRoles.OWNER))
        assertTrue(RelayRoles.canEdit(RelayRoles.EDITOR))
        assertTrue(!RelayRoles.canEdit(RelayRoles.VIEWER))
        assertTrue(RelayRoles.canRead(RelayRoles.VIEWER))
        assertTrue(!RelayRoles.canRead(RelayRoles.REVOKED))
        assertEquals(listOf(RelayRoles.EDITOR), RelayRoles.offerable("passwords"))
        assertEquals(listOf(RelayRoles.EDITOR, RelayRoles.VIEWER), RelayRoles.offerable("notes"))
    }

    @Test
    fun emptyLogPullIsEmptyNotNull() {
        // A provably-empty log returns an empty list (merge proceeds); only
        // transport FAILURE returns null (merge aborts). This distinction is
        // the loss-prevention invariant.
        val t = FakeTransport()
        kotlinx.coroutines.runBlocking {
            val pulled = t.pullRecords(recordChannel("notes", "v99"), t.newVaultKey(), 0)
            assertNotNull(pulled)
            assertTrue(pulled.items.isEmpty())
            assertTrue(!pulled.fullyUndecryptable)
        }
    }
}
