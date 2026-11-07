package com.vayunmathur.games.voxels.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full VoxelsSync protocol against [FakeVoxelsRelay] (installed through the
 * [VoxelsSync.relayTransport] seam): identity provisioning, world op log,
 * invites/join-requests inbox sorting, and the owner-signed roster.
 */
@OptIn(ExperimentalEncodingApi::class)
@RunWith(RobolectricTestRunner::class)
class VoxelsSyncRelayTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest fun installFake() {
        FakeVoxelsRelay.reset()
        FakeVoxelsRelay.install()
        // init() only registers once per process; re-seed our own key after a
        // reset so getKey(self) keeps working in later tests of this class.
        if (VoxelsSync.deviceId.isNotBlank()) {
            runCatching {
                FakeVoxelsRelay.keys[VoxelsSync.deviceId] = Base64.encode(VoxelsSync.publicBundle)
            }
        }
    }

    @AfterTest fun uninstallFake() = FakeVoxelsRelay.uninstall()

    private fun initSync(): Boolean = runBlocking { VoxelsSync.init(ctx) }

    @Test fun initProvisionsIdentityAndRegisters() {
        assertTrue(initSync())
        assertTrue(VoxelsSync.deviceId.isNotBlank())
        assertTrue(initSync()) // idempotent
        // registered: the fake relay can serve our own bundle back
        val own = runBlocking { VoxelsSync.getKey(VoxelsSync.deviceId) }
        assertNotNull(own)
        assertTrue(own.contentEquals(VoxelsSync.publicBundle))
        assertNull(runBlocking { VoxelsSync.getKey("no-such-device") })
        assertTrue(VoxelsSync.publicBundle.isNotEmpty())
    }

    @Test fun worldOpsRoundTripAndDecryptFiltering() {
        assertTrue(initSync())
        val key = VoxelsSync.newWorldKey()
        runBlocking {
            assertEquals(2, VoxelsSync.appendWorldOps("w-ops", key, listOf("op-one", "op-two")))
            assertEquals(3, VoxelsSync.appendWorldOps("w-ops", key, listOf("op-three")))
            val all = VoxelsSync.pullWorldOps("w-ops", key, 0)
            assertEquals(listOf("op-one", "op-two", "op-three"), all.items)
            assertEquals(3, all.seq)
            val tail = VoxelsSync.pullWorldOps("w-ops", key, 2)
            assertEquals(listOf("op-three"), tail.items)
            // wrong key: entries are skipped, seq still advances
            val wrong = VoxelsSync.pullWorldOps("w-ops", VoxelsSync.newWorldKey(), 0)
            assertTrue(wrong.items.isEmpty())
            assertEquals(3, wrong.seq)
            // raw channel access
            assertEquals(1, VoxelsSync.appendRaw("misc:chan", listOf("raw-1")))
            assertEquals(listOf("raw-1"), VoxelsSync.pullRaw("misc:chan", 0))
            assertEquals(emptyList(), VoxelsSync.pullRaw("misc:chan", 5))
            // garbage in the log is skipped by decrypt filtering
            VoxelsSync.appendRaw("world:w-ops", listOf("%%%garbage%%%"))
            assertEquals(3, VoxelsSync.pullWorldOps("w-ops", key, 0).items.size)
        }
    }

    @Test fun invitesAndJoinRequestsSortThroughInbox() {
        assertTrue(initSync())
        val myId = VoxelsSync.deviceId
        val key = VoxelsSync.newWorldKey()
        runBlocking {
            // invite addressed to ourselves
            assertTrue(
                VoxelsSync.sendInvite(
                    recipientId = myId, worldId = "w-inv", key = key, name = "Invited World",
                    seed = 77, role = VoxelsRoles.EDITOR,
                    ownerKeyB64 = Base64.encode(VoxelsSync.publicBundle), ownerDeviceId = "owner-9",
                )
            )
            // join request addressed to ourselves (as if we were the owner)
            assertTrue(VoxelsSync.sendJoinRequest(ownerId = myId, worldId = "w-inv", requesterId = "req-42", name = "Alice"))
            // a foreign undecryptable blob in the same inbox must be skipped
            VoxelsSync.appendRaw("inbox:$myId", listOf(Base64.encode(byteArrayOf(9, 9, 9, 9))))
            val inbox = VoxelsSync.pullInbox(0)
            assertEquals(1, inbox.invites.size)
            assertEquals("Invited World", inbox.invites.single().name)
            assertEquals(77, inbox.invites.single().seed)
            assertEquals("owner-9", inbox.invites.single().ownerDeviceId)
            assertEquals(1, inbox.requests.size)
            assertEquals("req-42", inbox.requests.single().requesterId)
            assertEquals("Alice", inbox.requests.single().name)
            // pullInvites view only exposes invites
            val invitesOnly = VoxelsSync.pullInvites(0)
            assertEquals(1, invitesOnly.invites.size)
            // invites to unknown devices fail at getKey
            assertFalse(
                VoxelsSync.sendInvite("ghost-device", "w", key, "N", 1, VoxelsRoles.EDITOR, "", "")
            )
            assertFalse(VoxelsSync.sendJoinRequest("ghost-device", "w", myId, "Me"))
        }
    }

    @Test fun rosterRecordAndVerifyRejectsForged() {
        assertTrue(initSync())
        val key = VoxelsSync.newWorldKey()
        val ownerBundle = VoxelsSync.publicBundle
        runBlocking {
            val members = listOf(
                VoxelsSync.Member(VoxelsSync.deviceId, "", VoxelsRoles.OWNER),
                VoxelsSync.Member("editor-1", "Ed", VoxelsRoles.EDITOR),
            )
            assertTrue(VoxelsSync.recordMembers("w-roster", key, members))
            val roster = VoxelsSync.fetchRoster("w-roster", key, ownerBundle)
            assertEquals(
                mapOf(VoxelsSync.deviceId to VoxelsRoles.OWNER, "editor-1" to VoxelsRoles.EDITOR),
                roster,
            )
            // forged record: valid JSON, invalid signature -> excluded
            val forged = json.encodeToString(
                VoxelsSync.SignedMember.serializer(),
                VoxelsSync.SignedMember(VoxelsSync.Member("mallory", "", VoxelsRoles.OWNER), Base64.encode(byteArrayOf(1, 2, 3))),
            )
            VoxelsSync.appendMembers("w-roster", key, listOf(forged, "not-a-member-json"))
            val after = VoxelsSync.fetchRoster("w-roster", key, ownerBundle)
            assertFalse(after.containsKey("mallory"))
            assertEquals(2, after.size)
            // wrong content key decrypts to nothing
            assertTrue(VoxelsSync.fetchRoster("w-roster", VoxelsSync.newWorldKey(), ownerBundle).isEmpty())
            assertEquals(emptyList(), VoxelsSync.pullMembers("no-such-world", key))
        }
    }

    @Test fun signVerifySealAndSecurityCode() {
        assertTrue(initSync())
        runBlocking {
            val data = "signed payload".encodeToByteArray()
            val sig = VoxelsSync.sign(data)
            assertTrue(VoxelsSync.verify(VoxelsSync.publicBundle, data, sig))
            assertFalse(VoxelsSync.verify(VoxelsSync.publicBundle, "tampered".encodeToByteArray(), sig))
            assertFalse(VoxelsSync.verify(VoxelsSync.publicBundle, data, byteArrayOf(1, 2, 3)))
            val sealed = VoxelsSync.seal(VoxelsSync.publicBundle, data)
            assertEquals("signed payload", VoxelsSync.unseal(sealed).decodeToString())
            val code = VoxelsSync.securityCode(VoxelsSync.publicBundle)
            assertNotNull(code)
            assertTrue(code.isNotBlank())
            // securityCode is a fingerprint: it produces a code for any bundle bytes
            assertNotNull(VoxelsSync.securityCode(byteArrayOf(1, 2)))
        }
    }
}
