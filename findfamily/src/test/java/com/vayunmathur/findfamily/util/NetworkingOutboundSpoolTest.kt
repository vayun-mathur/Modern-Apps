package com.vayunmathur.findfamily.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.OutboundSpool
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.network.InboundFix
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.findfamily.network.dispatchLiveFrame
import com.vayunmathur.findfamily.network.flushOutboundSpool
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.util.DataStoreUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Networking-level outbound spool: a location publish whose socket send fails is kept
 * (sealed) as the recipient's latest unpublished fix, flushed on reconnect, and cleared
 * once delivered. Uses the same in-memory [WsSession] harness as
 * [NetworkingLiveSessionTest]; the spool itself is asserted through [OutboundSpool] and
 * by decrypting payloads back through [dispatchLiveFrame].
 */
@RunWith(AndroidJUnit4::class)
class NetworkingOutboundSpoolTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var repo: com.vayunmathur.findfamily.data.FindFamilyRepository
    private lateinit var out: ByteArrayOutputStream
    private lateinit var session: WsSession

    @BeforeTest
    fun setup() = runBlocking {
        repo = TestFindFamilyRepository.install(ctx)
        Networking.stopLive()
        Networking.init(repo, DataStoreUtils.getInstance(ctx), "Me")
        assertTrue(Networking.pqcReady, "PQC native library must load on the test host")
        Networking.bindSpoolContext(ctx)
        cleanSpool()
        out = ByteArrayOutputStream()
        session = fakeSession(out)
        Networking.wsSession = null
        Networking.repository = repo
    }

    @AfterTest
    fun tearDown() {
        Networking.wsSession = null
        cleanSpool()
    }

    private fun cleanSpool() {
        val dir = ctx.createDeviceProtectedStorageContext().filesDir
        dir.listFiles()?.filter { it.name.startsWith("ff_outbound_spool") }?.forEach { File(it.absolutePath).delete() }
    }

    private fun fakeSession(output: ByteArrayOutputStream): WsSession {
        val clientConstructor = WebSocketClient::class.java
            .getDeclaredConstructor(
                Socket::class.java, java.io.InputStream::class.java,
                java.io.OutputStream::class.java, Map::class.java, Map::class.java,
            ).apply { isAccessible = true }
        val client = clientConstructor.newInstance(
            Socket(), ByteArrayInputStream(ByteArray(0)), output,
            emptyMap<String, List<String>>(), emptyMap<String, String>(),
        )
        val sessionConstructor = WsSession::class.java
            .getDeclaredConstructor(WebSocketClient::class.java)
            .apply { isAccessible = true }
        return sessionConstructor.newInstance(client)
    }

    /** Unmasks the single client frame written to [out] and returns its payload. */
    private fun capturedPayload(): ByteArray {
        val frame = out.toByteArray()
        assertTrue(frame.size >= 6, "a masked client frame was written")
        var off = 2
        var len = frame[1].toInt() and 0x7f
        if (len == 126) {
            len = ((frame[off].toInt() and 0xff) shl 8) or (frame[off + 1].toInt() and 0xff)
            off += 2
        } else if (len == 127) {
            var wide = 0L
            for (i in 0 until 8) wide = (wide shl 8) or (frame[off + i].toLong() and 0xff)
            len = wide.toInt()
            off += 8
        }
        val mask = frame.copyOfRange(off, off + 4)
        off += 4
        return ByteArray(len) { i -> (frame[off + i].toInt() xor mask[i % 4].toInt()).toByte() }
    }

    private fun peer(id: Long): User {
        val bundle = Base64.encode(Networking.pqcIdentity.publicBundle)
        return User(
            name = "Peer", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION,
            id = id, pqcEncryptionKey = bundle, outboundPairId = "pair-1",
        )
    }

    private fun fix(reportedAtMs: Long, battery: Float) = LocationValue(
        userid = Networking.userid, coord = Coord(12.5, -45.25), speed = 2f, acc = 5f,
        timestamp = Instant.fromEpochMilliseconds(reportedAtMs), battery = battery,
        reportedAt = Instant.fromEpochMilliseconds(reportedAtMs), source = LocationSource.LIVE,
    )

    /** Decrypts a sealed payload by feeding it back as an inbound MSG frame. */
    private fun decrypt(ciphertext: ByteArray): InboundFix {
        val delivered = mutableListOf<InboundFix>()
        runBlocking {
            dispatchLiveFrame(byteArrayOf(Networking.WS_OP_MSG, 0) + ciphertext, { delivered += it }, {})
        }
        assertEquals(1, delivered.size, "spooled payload must decrypt to exactly one fix")
        return delivered.single()
    }

    @Test
    fun publishWhileSocketDownSpoolsSealedFix() = runBlocking {
        val peer = peer(777L)
        repo.upsertUser(peer)
        assertFalse(Networking.publishLocation(fix(1_700_000_000_000L, battery = 63f), peer))
        val pending = OutboundSpool.pending(ctx)
        assertEquals(setOf(777L), pending.keys, "failed publish is spooled per recipient")
        val fix = decrypt(pending.getValue(777L))
        assertEquals(63f, fix.location.battery)
        assertEquals(Coord(12.5, -45.25), fix.location.coord)
        assertEquals("pair-1", fix.pairId, "spooled payload carries the pair ID, like a live send")
    }

    @Test
    fun newerPublishWhileDownReplacesSpooledFix() = runBlocking {
        val peer = peer(777L)
        repo.upsertUser(peer)
        assertFalse(Networking.publishLocation(fix(1_700_000_000_000L, battery = 63f), peer))
        assertFalse(Networking.publishLocation(fix(1_700_000_060_000L, battery = 64f), peer))
        val pending = OutboundSpool.pending(ctx)
        assertEquals(1, pending.size, "still one slot for the recipient")
        assertEquals(64f, decrypt(pending.getValue(777L)).location.battery)
    }

    @Test
    fun flushOnReconnectDeliversSpooledFixAndClears() = runBlocking {
        val peer = peer(777L)
        repo.upsertUser(peer)
        assertFalse(Networking.publishLocation(fix(1_700_000_000_000L, battery = 63f), peer))
        assertFalse(Networking.publishLocation(fix(1_700_000_060_000L, battery = 64f), peer))

        // Reconnect: the spool flushes before the next live fix, over the new socket.
        Networking.wsSession = session
        Networking.flushOutboundSpool()
        val frame = capturedPayload()
        assertEquals(0x02, frame[0].toInt(), "flushed frame is a PUB")
        assertEquals(777L, Networking.readU64Be(frame, 2))
        assertTrue(OutboundSpool.pending(ctx).isEmpty(), "delivered spool slot is cleared (ack)")
        assertEquals(64f, decrypt(frame.copyOfRange(10, frame.size)).location.battery)
    }

    @Test
    fun publishWhileConnectedLeavesNoSpool() = runBlocking {
        val peer = peer(777L)
        repo.upsertUser(peer)
        Networking.wsSession = session
        assertTrue(Networking.publishLocation(fix(1_700_000_000_000L, battery = 63f), peer))
        assertTrue(OutboundSpool.pending(ctx).isEmpty(), "connected publish behaviour unchanged")
        // A successful live publish also retires an older spooled fix for the recipient.
        Networking.wsSession = null
        assertFalse(Networking.publishLocation(fix(1_700_000_000_000L, battery = 63f), peer))
        Networking.wsSession = session
        out.reset()
        assertTrue(Networking.publishLocation(fix(1_700_000_060_000L, battery = 64f), peer))
        assertTrue(OutboundSpool.pending(ctx).isEmpty(), "newer live fix retires the spooled one")
    }
}
