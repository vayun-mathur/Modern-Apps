package com.vayunmathur.games.voxels.network

import com.vayunmathur.e2ee.E2ee
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline-only VoxelsSync coverage: crypto/parsing helpers that never touch
 * the relay. Network-backed methods (init/register/append/pull/startLive's
 * socket) are deliberately not called - they would reach api.maproj.org.
 */
@OptIn(ExperimentalEncodingApi::class)
@RunWith(RobolectricTestRunner::class)
class VoxelsSyncTest {

    @Test fun `new world ids and keys are well formed`() {
        val id1 = VoxelsSync.newWorldId(); val id2 = VoxelsSync.newWorldId()
        assertEquals(36, id1.length)
        assertTrue(id1 != id2)
        val k1 = VoxelsSync.newWorldKey(); val k2 = VoxelsSync.newWorldKey()
        assertEquals(32, k1.size)
        assertFalse(k1.contentEquals(k2))
    }

    @Test fun `parse live accepts valid and rejects malformed`() {
        val msg = VoxelsSync.parseLive("""{"t":"actions","channel":"world:x","actions":["a","b"],"seq":7,"data":"d"}""")
        assertNotNull(msg)
        assertEquals("actions", msg.t)
        assertEquals(listOf("a", "b"), msg.actions)
        assertEquals(7, msg.seq)
        assertEquals("d", msg.data)
        val defaulted = VoxelsSync.parseLive("""{}""")
        assertNotNull(defaulted)
        assertEquals("", defaulted.t)
        assertEquals(emptyList(), defaulted.actions)
        assertNull(VoxelsSync.parseLive("not json"))
        assertNull(VoxelsSync.parseLive("""{"t":123}"""))
    }

    @Test fun `decrypt round trips aes content and rejects wrong key or garbage`() {
        val key = VoxelsSync.newWorldKey()
        val blob = Base64.encode(E2ee.aesEncrypt(key, "hello voxels".encodeToByteArray()))
        assertEquals("hello voxels", VoxelsSync.decrypt(key, blob))
        assertNull(VoxelsSync.decrypt(VoxelsSync.newWorldKey(), blob))
        assertNull(VoxelsSync.decrypt(key, "!!!not-base64!!!"))
        assertNull(VoxelsSync.decrypt(key, Base64.encode(byteArrayOf(1, 2, 3))))
        assertNull(VoxelsSync.decrypt(ByteArray(3), blob))
    }

    @Test fun `member signing bytes bind world id and role`() {
        val m = VoxelsSync.Member(id = "dev-1", name = "Alice", role = VoxelsRoles.OWNER)
        assertEquals("world-9|dev-1|owner", VoxelsSync.memberSigningBytes("world-9", m).decodeToString())
        val e = VoxelsSync.Member(id = "dev-2")
        assertEquals(VoxelsRoles.EDITOR, e.role)
        assertEquals("", e.name)
        assertEquals("w|dev-2|editor", VoxelsSync.memberSigningBytes("w", e).decodeToString())
    }

    @Test fun `sync data classes serialize`() {
        val json = Json { ignoreUnknownKeys = true }
        val invite = VoxelsSync.Invite(worldId = "w1", key = "k", name = "World", seed = 5, role = VoxelsRoles.EDITOR, ownerKey = "ok", ownerDeviceId = "own")
        val decoded = json.decodeFromString(VoxelsSync.Invite.serializer(), json.encodeToString(VoxelsSync.Invite.serializer(), invite))
        assertEquals(invite, decoded)
        val minimal = json.decodeFromString(VoxelsSync.Invite.serializer(), """{"worldId":"w2","key":"k2","name":"N"}""")
        assertEquals(0, minimal.seed)
        assertEquals(VoxelsRoles.EDITOR, minimal.role)
        val req = VoxelsSync.JoinRequest(worldId = "w", requesterId = "r", name = "Bob")
        assertEquals("req", req.kind)
        assertEquals(req, json.decodeFromString(VoxelsSync.JoinRequest.serializer(), json.encodeToString(VoxelsSync.JoinRequest.serializer(), req)))
        val signed = VoxelsSync.SignedMember(VoxelsSync.Member("d1", "N", VoxelsRoles.OWNER), "sig")
        assertEquals(signed, json.decodeFromString(VoxelsSync.SignedMember.serializer(), json.encodeToString(VoxelsSync.SignedMember.serializer(), signed)))
        val live = VoxelsSync.LiveMsg(t = "presence", channel = "c", actions = listOf("x"), seq = 3, data = "y")
        assertEquals(live, json.decodeFromString(VoxelsSync.LiveMsg.serializer(), json.encodeToString(VoxelsSync.LiveMsg.serializer(), live)))
        val op = VoxelsSync.SignedOp("author", "sig", "ops")
        assertEquals(op, json.decodeFromString(VoxelsSync.SignedOp.serializer(), json.encodeToString(VoxelsSync.SignedOp.serializer(), op)))
        // result holders
        assertEquals(9, VoxelsSync.InvitesResult(listOf(invite, invite), 9).seq)
        val inbox = VoxelsSync.InboxResult(listOf(invite), listOf(req), 4)
        assertEquals(1, inbox.invites.size); assertEquals(1, inbox.requests.size)
        assertEquals(listOf("a"), VoxelsSync.OpsResult(listOf("a"), 1).items)
    }

    @Test fun `live helpers without a session are safe no ops`() = runBlocking {
        VoxelsSync.stopLive()
        assertFalse(VoxelsSync.isLive)
        assertFalse(VoxelsSync.liveAppend("world:x", VoxelsSync.newWorldKey(), listOf("op")))
        VoxelsSync.sendPresence("world:x", VoxelsSync.newWorldKey(), "presence-plain") // no session: swallowed
        VoxelsSync.stopLive() // idempotent
        assertFalse(VoxelsSync.isLive)
        Unit
    }
}
