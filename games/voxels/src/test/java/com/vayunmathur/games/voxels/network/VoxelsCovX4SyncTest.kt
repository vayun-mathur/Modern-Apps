package com.vayunmathur.games.voxels.network

import com.vayunmathur.e2ee.E2ee
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** Cov-x4: VoxelsSync crypto round-trips and protocol shapes (no relay, no identity). */
@OptIn(ExperimentalEncodingApi::class)
class VoxelsCovX4SyncTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun decryptRoundTripsRealAesCiphertext() {
        val key = VoxelsSync.newWorldKey()
        val cipher = Base64.encode(E2ee.aesEncrypt(key, "hello voxels".encodeToByteArray()))
        assertEquals("hello voxels", VoxelsSync.decrypt(key, cipher))
        val empty = Base64.encode(E2ee.aesEncrypt(key, ByteArray(0)))
        assertEquals("", VoxelsSync.decrypt(key, empty))
        val unicode = Base64.encode(E2ee.aesEncrypt(key, "héllo → world".encodeToByteArray()))
        assertEquals("héllo → world", VoxelsSync.decrypt(key, unicode))
    }

    @Test fun decryptRejectsWrongOrMalformedInput() {
        val key = VoxelsSync.newWorldKey()
        val other = VoxelsSync.newWorldKey()
        val cipher = Base64.encode(E2ee.aesEncrypt(key, "secret".encodeToByteArray()))
        assertNull(VoxelsSync.decrypt(other, cipher))
        assertNull(VoxelsSync.decrypt(key, "!!!not-base64!!!"))
        assertNull(VoxelsSync.decrypt(key, ""))
        assertNull(VoxelsSync.decrypt(key, Base64.encode(ByteArray(4))))
        assertNull(VoxelsSync.decrypt(ByteArray(16), cipher))
        // Tampered ciphertext fails GCM authentication.
        val raw = Base64.decode(cipher); raw[raw.lastIndex] = (raw[raw.lastIndex].toInt() xor 1).toByte()
        assertNull(VoxelsSync.decrypt(key, Base64.encode(raw)))
    }

    @Test fun worldKeysAndIdsAreFresh() {
        val keys = (1..8).map { VoxelsSync.newWorldKey() }
        assertTrue(keys.all { it.size == 32 })
        assertEquals(keys.size, keys.map { Base64.encode(it) }.toSet().size)
        val ids = (1..8).map { VoxelsSync.newWorldId() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.length >= 32 })
    }

    @Test fun memberSigningBytesAndRoles() {
        assertEquals("w|a|owner", VoxelsSync.memberSigningBytes("w", VoxelsSync.Member("a", "N", "owner")).decodeToString())
        assertEquals("|x|editor", VoxelsSync.memberSigningBytes("", VoxelsSync.Member("x")).decodeToString())
        assertNotEquals(
            VoxelsSync.memberSigningBytes("w1", VoxelsSync.Member("a")).decodeToString(),
            VoxelsSync.memberSigningBytes("w2", VoxelsSync.Member("a")).decodeToString(),
        )
        assertTrue(VoxelsRoles.canEdit("owner")); assertTrue(VoxelsRoles.canEdit("editor"))
        assertFalse(VoxelsRoles.canEdit("Owner")); assertFalse(VoxelsRoles.canEdit(" editor"))
    }

    @Test fun protocolShapesRoundTripWithDefaults() {
        val invite = VoxelsSync.Invite("wid", "a2V5", "World")
        assertEquals(0, invite.seed); assertEquals("editor", invite.role)
        assertEquals("", invite.ownerKey); assertEquals("", invite.ownerDeviceId)
        assertEquals(invite, json.decodeFromString<VoxelsSync.Invite>(json.encodeToString(invite)))
        val minimal = json.decodeFromString<VoxelsSync.Invite>("""{"worldId":"w","key":"k","name":"n"}""")
        assertEquals(VoxelsSync.Invite("w", "k", "n"), minimal)
        val req = json.decodeFromString<VoxelsSync.JoinRequest>("""{"worldId":"w","requesterId":"r"}""")
        assertEquals("req", req.kind); assertEquals("", req.name)
        val signedOp = VoxelsSync.SignedOp("author", "c2ln", """{"type":"Place"}""")
        assertEquals(signedOp, json.decodeFromString<VoxelsSync.SignedOp>(json.encodeToString(signedOp)))
        val member = VoxelsSync.SignedMember(VoxelsSync.Member("d", "Ada", "owner"), "c2ln")
        assertEquals(member, json.decodeFromString<VoxelsSync.SignedMember>(json.encodeToString(member)))
        val liveDefaults = json.decodeFromString<VoxelsSync.LiveMsg>("{}")
        assertEquals(VoxelsSync.LiveMsg("", "", emptyList(), 0, ""), liveDefaults)
        val live = json.decodeFromString<VoxelsSync.LiveMsg>(
            """{"t":"presence","channel":"world:x","actions":[],"seq":9,"data":"zz","extra":1}""",
        )
        assertEquals(9, live.seq); assertEquals("zz", live.data)
    }

    @Test fun parseLiveAcceptsAndRejects() {
        assertNull(VoxelsSync.parseLive("{}".drop(2)))
        assertNull(VoxelsSync.parseLive("""{"t":123}"""))
        assertNull(VoxelsSync.parseLive("[]"))
        val m = VoxelsSync.parseLive("""{"t":"actions","channel":"c","actions":["a","b"],"seq":2,"data":"d"}""")
        assertEquals(listOf("a", "b"), m?.actions); assertEquals("c", m?.channel)
    }

    @Test fun identityDependentHelpersDegradeWithoutInit() = runBlocking {
        // No native PQC library / identity on the JVM: verify is false, security code is null,
        // signing and sealing surface the uninitialized identity rather than fabricating output.
        assertFalse(VoxelsSync.verify(ByteArray(8), "data".encodeToByteArray(), ByteArray(8)))
        assertFalse(VoxelsSync.verify(ByteArray(0), ByteArray(0), ByteArray(0)))
        assertNull(VoxelsSync.securityCode(ByteArray(8)))
        assertEquals("", VoxelsSync.deviceId)
        assertFalse(VoxelsSync.isLive)
        VoxelsSync.stopLive()
        assertFalse(VoxelsSync.isLive)
    }

    @Test fun resultHoldersKeepValues() {
        val ops = VoxelsSync.OpsResult(listOf("a", "b"), 7)
        assertEquals(listOf("a", "b"), ops.items); assertEquals(7, ops.seq)
        val inbox = VoxelsSync.InboxResult(
            listOf(VoxelsSync.Invite("w", "k", "n")),
            listOf(VoxelsSync.JoinRequest(worldId = "w", requesterId = "r")), 3,
        )
        assertEquals(1, inbox.invites.size); assertEquals("r", inbox.requests[0].requesterId); assertEquals(3, inbox.seq)
        val invites = VoxelsSync.InvitesResult(listOf(VoxelsSync.Invite("w", "k", "n")), 5)
        assertEquals(5, invites.seq); assertEquals("w", invites.invites[0].worldId)
    }
}
