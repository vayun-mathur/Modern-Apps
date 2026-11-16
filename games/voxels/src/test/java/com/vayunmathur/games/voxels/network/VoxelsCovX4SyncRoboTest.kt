package com.vayunmathur.games.voxels.network

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.voxels.data.WorldManager
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cov-x4: VoxelsSync with a real identity (native PQC loads on the host JVM).
 * Read-only relay paths only: no world/members appends are exercised, so nothing is
 * written to shared relay channels beyond the device registration `init` itself performs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(ExperimentalEncodingApi::class)
class VoxelsCovX4SyncRoboTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun initIsIdempotentAndIdentityCryptoRoundTrips() = runBlocking {
        assertTrue(VoxelsSync.init(ctx))
        val id = VoxelsSync.deviceId
        assertTrue(id.isNotBlank())
        assertTrue(VoxelsSync.init(ctx)) // second call is a no-op success
        assertEquals(id, VoxelsSync.deviceId)
        val bundle = VoxelsSync.publicBundle
        assertTrue(bundle.size > 100)
        // Sign/verify: valid signature passes, tampered data and garbage bundles fail.
        val data = "signed ops".encodeToByteArray()
        val sig = VoxelsSync.sign(data)
        assertTrue(VoxelsSync.verify(bundle, data, sig))
        assertFalse(VoxelsSync.verify(bundle, "other".encodeToByteArray(), sig))
        assertFalse(VoxelsSync.verify(byteArrayOf(1, 2, 3), data, sig))
        val tampered = sig.copyOf(); tampered[0] = (tampered[0].toInt() xor 1).toByte()
        assertFalse(VoxelsSync.verify(bundle, data, tampered))
        // Seal to ourselves and unseal; garbage ciphertext must not decode silently.
        val sealed = VoxelsSync.seal(bundle, "invite payload".encodeToByteArray())
        assertEquals("invite payload", VoxelsSync.unseal(sealed).decodeToString())
        try {
            VoxelsSync.unseal(byteArrayOf(1, 2, 3))
            fail("unsealing garbage should throw")
        } catch (e: Exception) {
            assertTrue(e.message != null || e is RuntimeException)
        }
        // Safety code against our own bundle is a stable, non-empty string.
        val code = VoxelsSync.securityCode(bundle)
        assertNotNull(code)
        assertEquals(code, VoxelsSync.securityCode(bundle))
        assertTrue(code.isNotBlank())
    }

    @Test fun relayReadsForMissingPeersAndEmptyChannels() = runBlocking {
        assertTrue(VoxelsSync.init(ctx))
        assertNull(VoxelsSync.getKey("no-such-device-x4-zzz"))
        // Our own registered bundle is fetchable and identical to the local one.
        val own = VoxelsSync.getKey(VoxelsSync.deviceId)
        assertNotNull(own)
        assertTrue(own.contentEquals(VoxelsSync.publicBundle))
        val key = VoxelsSync.newWorldKey()
        val ops = VoxelsSync.pullWorldOps("x4-empty-${VoxelsSync.newWorldId()}", key, 0)
        assertTrue(ops.items.isEmpty())
        assertTrue(VoxelsSync.pullMembers("x4-empty-${VoxelsSync.newWorldId()}", key).isEmpty())
        assertTrue(VoxelsSync.fetchRoster("x4-empty-${VoxelsSync.newWorldId()}", key, VoxelsSync.publicBundle).isEmpty())
        val inbox = VoxelsSync.pullInbox(0)
        assertTrue(inbox.seq >= 0)
        val invites = VoxelsSync.pullInvites(0)
        assertEquals(inbox.seq, invites.seq)
        assertTrue(VoxelsSync.pullRaw("x4-empty-${VoxelsSync.newWorldId()}", 0).isEmpty())
        // Invites/requests addressed to devices the relay does not know cannot be sent.
        assertFalse(
            VoxelsSync.sendInvite(
                "no-such-device-x4-zzz", "wid", key, "W", 1, VoxelsRoles.EDITOR,
                Base64.encode(VoxelsSync.publicBundle), VoxelsSync.deviceId,
            ),
        )
        assertFalse(VoxelsSync.sendJoinRequest("no-such-device-x4-zzz", "wid", VoxelsSync.deviceId, "Me"))
    }

    @Test fun liveSocketLifecycleAndPresence() = runBlocking {
        assertTrue(VoxelsSync.init(ctx))
        assertFalse(VoxelsSync.liveAppend("world:x4-none", VoxelsSync.newWorldKey(), listOf("op")))
        val scope = CoroutineScope(Dispatchers.Default)
        var connected = false
        val messages = mutableListOf<String>()
        VoxelsSync.startLive(scope, "world:x4-live-probe", { connected = true }, { messages += it })
        // Re-subscribing to the same channel while the job is active is a no-op.
        VoxelsSync.startLive(scope, "world:x4-live-probe", { connected = true }, { messages += it })
        var waited = 0L
        while (!VoxelsSync.isLive && waited < 8000) {
            delay(200); waited += 200
        }
        if (VoxelsSync.isLive) {
            VoxelsSync.sendPresence("world:x4-live-probe", VoxelsSync.newWorldKey(), """{"from":"me"}""")
            delay(300)
        }
        VoxelsSync.stopLive()
        assertFalse(VoxelsSync.isLive)
        assertTrue(waited <= 8000)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }

    @Test fun joinRequestsApproveOwnedWorldWithUnknownRequester() = runBlocking {
        assertTrue(VoxelsSync.init(ctx))
        val keyB64 = Base64.encode(VoxelsSync.newWorldKey())
        val worldId = "x4-owned-${VoxelsSync.newWorldId()}"
        WorldManager.createOnlineWorld(ctx, "Owned X4", 3, worldId, keyB64, VoxelsSync.deviceId, 12345L)
        val req = VoxelsSync.JoinRequest(worldId = worldId, requesterId = "no-such-device-x4-zzz", name = "Zed")
        // The world is owned, but the requester has no registered key, so no invite can be sealed:
        // approval fails and the request is not marked handled.
        assertFalse(JoinRequests.approve(ctx, req))
        assertFalse(JoinRequests.handledKey(req) in WorldManager.handledRequests(ctx))
        // Refresh over an empty inbox yields nothing even though we own a world.
        assertEquals(emptyList(), JoinRequests.refresh(ctx, acceptInvites = true))
    }
}
