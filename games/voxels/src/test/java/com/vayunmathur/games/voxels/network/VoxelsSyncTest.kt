package com.vayunmathur.games.voxels.network

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-JVM tests for [VoxelsSync] protocol helpers that need no relay or identity. */
class VoxelsSyncTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun memberSigningBytesBindsWorldAndRole() {
        val m = VoxelsSync.Member(id = "dev1", name = "A", role = VoxelsRoles.OWNER)
        assertEquals("w1|dev1|owner", VoxelsSync.memberSigningBytes("w1", m).decodeToString())
    }
    @Test fun rolesCanEdit() {
        assertTrue(VoxelsRoles.canEdit(VoxelsRoles.OWNER))
        assertTrue(VoxelsRoles.canEdit(VoxelsRoles.EDITOR))
        assertFalse(VoxelsRoles.canEdit("viewer")); assertFalse(VoxelsRoles.canEdit(""))
    }
    @Test fun inviteAndRequestJsonRoundTrip() {
        val inv = VoxelsSync.Invite("wid", "a2V5", "World", 7, VoxelsRoles.EDITOR, "ownerKey", "dev0")
        val back = json.decodeFromString<VoxelsSync.Invite>(json.encodeToString(inv))
        assertEquals(inv, back)
        val req = VoxelsSync.JoinRequest(worldId = "wid", requesterId = "dev9", name = "Bob")
        assertEquals("req", req.kind)
        assertEquals(req, json.decodeFromString<VoxelsSync.JoinRequest>(json.encodeToString(req)))
    }
    @Test fun parseLiveValidAndInvalid() {
        val msg = VoxelsSync.parseLive("""{"t":"append","channel":"world:x","actions":["a"],"seq":3}""")
        assertEquals("world:x", msg?.channel); assertEquals(3, msg?.seq)
        assertNull(VoxelsSync.parseLive("not json")); assertNull(VoxelsSync.parseLive(""))
    }
    @Test fun decryptGarbageReturnsNullAndDefaults() {
        assertNull(VoxelsSync.decrypt(ByteArray(32), "!!!not-base64!!!"))
        assertFalse(VoxelsSync.isLive)
        assertEquals(32, VoxelsSync.newWorldKey().size)
        assertTrue(VoxelsSync.newWorldId().isNotBlank())
        VoxelsSync.stopLive()
    }
}
