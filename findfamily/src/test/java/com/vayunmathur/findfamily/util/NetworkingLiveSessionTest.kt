package com.vayunmathur.findfamily.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.util.DataStoreUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import kotlin.io.encoding.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Live-socket layer with a genuine PQC identity (the native library loads
 * on the Robolectric host) and a [WsSession] over in-memory streams.
 *
 * Publish paths seal real ciphertext into captured WebSocket frames; the
 * captured payload is then fed back through [dispatchLiveFrame] and must
 * decrypt to the original fix, so the wire format, pair-id handling and
 * key-lookup state machine are all asserted end to end. Only the TCP
 * transport is faked.
 */
@RunWith(AndroidJUnit4::class)
class NetworkingLiveSessionTest {

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
        out = ByteArrayOutputStream()
        session = fakeSession(out)
        Networking.wsSession = session
        Networking.repository = repo
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
        assertEquals(0x82, frame[0].toInt() and 0xff)
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

    private fun selfUser(id: Long): User {
        val bundle = Base64.encode(Networking.pqcIdentity.publicBundle)
        return User(
            name = "Peer", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
            id = id, pqcEncryptionKey = bundle, outboundPairId = "pair-1",
        )
    }

    private fun fix(userid: Long, battery: Float = 50f) = LocationValue(
        userid = userid, coord = Coord(12.5, -45.25), speed = 2f, acc = 5f,
        timestamp = Clock.System.now(), battery = battery,
        reportedAt = Clock.System.now(), source = LocationSource.LIVE,
    )

    @Test fun subscribeFrameCarriesUseridAndBundle() = runBlocking {
        session.sendSubscribe()
        val payload = capturedPayload()
        assertEquals(Networking.WS_OP_SUB, payload[0])
        assertEquals(Networking.userid, Networking.readU64Be(payload, 1))
        assertEquals(Networking.SUB_FRAME_HEADER_LEN + Networking.pqcIdentity.publicBundle.size, payload.size)
    }

    @Test fun publishLocationSealsFrameThatDecryptsBackToTheFix() = runBlocking {
        val peer = selfUser(777L)
        repo.upsertUser(peer)
        assertTrue(Networking.publishLocation(fix(Networking.userid, battery = 63f), peer))
        val frame = capturedPayload()
        assertEquals(0x02, frame[0].toInt())
        assertEquals(777L, Networking.readU64Be(frame, 2))
        // Feed the ciphertext back as an inbound MSG: it must decrypt and deliver.
        val delivered = mutableListOf<InboundFix>()
        val msg = byteArrayOf(Networking.WS_OP_MSG, 0) + frame.copyOfRange(10, frame.size)
        dispatchLiveFrame(msg, { delivered += it }, {})
        assertEquals(1, delivered.size)
        assertEquals(63f, delivered.single().location.battery)
        assertEquals(Coord(12.5, -45.25), delivered.single().location.coord)
        assertEquals("pair-1", delivered.single().pairId)
    }

    @Test fun publishToTemporaryLinkAndDirectIdSealFrames() = runBlocking {
        val bundle = Base64.encode(Networking.pqcIdentity.publicBundle)
        val link = TemporaryLink(name = "Trip", deleteAt = Clock.System.now(), pqcPublicKey = bundle, id = 88L)
        assertTrue(Networking.publishLocation(fix(1L), link))
        assertEquals(88L, Networking.readU64Be(capturedPayload(), 2))
        out.reset()
        assertTrue(Networking.publishLocation(fix(1L), 99L, bundle, pairId = "pair-9"))
        assertEquals(99L, Networking.readU64Be(capturedPayload(), 2))
    }

    @Test fun publishUwbMessageSealsEnvelope() = runBlocking {
        val peer = selfUser(778L)
        repo.upsertUser(peer)
        val envelope = UwbEnvelope(sessionId = "sess-1", sender = 1uL, senderPlatform = "android", kind = "request")
        assertTrue(Networking.publishUwbMessage(envelope, 778L, peer))
        val frame = capturedPayload()
        assertEquals(0x02, frame[0].toInt())
        assertEquals(1, frame[1].toInt() and Networking.WS_FLAG_UWB)
        // The UWB payload decrypts with our identity and parses back.
        val delivered = mutableListOf<UwbEnvelope>()
        val msg = byteArrayOf(Networking.WS_OP_MSG, Networking.WS_FLAG_UWB.toByte()) +
            frame.copyOfRange(10, frame.size)
        dispatchLiveFrame(msg, {}, { delivered += it })
        assertEquals(listOf(envelope), delivered)
    }

    @Test fun securityCodeAndSignaturesVerify() = runBlocking {
        val peer = selfUser(779L)
        repo.upsertUser(peer)
        val code = Networking.securityCode(peer)
        assertNotNull(code)
        assertEquals(code, Networking.securityCodePqc(peer))
        assertNull(Networking.securityCode(selfUser(780L).copy(pqcEncryptionKey = null)))
        val data = "grant".encodeToByteArray()
        val signature = Networking.signAsMe(data)
        assertNotNull(signature)
        assertTrue(Networking.verifyFrom(779L, data, signature))
        assertFalse(Networking.verifyFrom(779L, "tampered".encodeToByteArray(), signature))
        assertFalse(Networking.verifyFrom(123456L, data, signature))
    }

    @Test fun peerCryptoStatusFromKeyResponses() = runBlocking {
        // Classic-only peer: the relay answer completes the pending lookup.
        val classic = async { Networking.peerCryptoStatus(555L) }
        delay(150)
        dispatchKeyResp(status = 1, target = 555L, bundle = null)
        assertEquals(Networking.PeerCrypto.NEEDS_UPDATE, classic.await().status)

        val pqc = async { Networking.peerCryptoStatus(556L) }
        delay(150)
        dispatchKeyResp(status = 2, target = 556L, bundle = Networking.pqcIdentity.publicBundle)
        val pqcResult = pqc.await()
        assertEquals(Networking.PeerCrypto.PQC, pqcResult.status)
        assertNull(pqcResult.verifiedBundleB64)
        // The looked-up bundle is cached on the user row when one exists.
        val cached = async { Networking.peerCryptoStatus(557L) }
        delay(150)
        val peerRow = repo.upsertUser(selfUser(557L).copy(pqcEncryptionKey = null, outboundPairId = null))
        assertTrue(peerRow > 0)
        dispatchKeyResp(status = 2, target = 557L, bundle = Networking.pqcIdentity.publicBundle)
        assertEquals(Networking.PeerCrypto.PQC, cached.await().status)

        val unknown = async { Networking.peerCryptoStatus(558L) }
        delay(150)
        dispatchKeyResp(status = 0, target = 558L, bundle = null)
        assertEquals(Networking.PeerCrypto.UNKNOWN, unknown.await().status)
    }

    @Test fun peerCryptoStatusRejectsFingerprintMismatch() = runBlocking {
        val fingerprint = Networking.myInviteFingerprint()
        assertNotNull(fingerprint)
        // Our own bundle with our own fingerprint verifies and pins the bundle.
        val ok = async { Networking.peerCryptoStatus(Networking.userid, fingerprint) }
        delay(150)
        dispatchKeyResp(status = 2, target = Networking.userid, bundle = Networking.pqcIdentity.publicBundle)
        val okResult = ok.await()
        assertEquals(Networking.PeerCrypto.PQC, okResult.status)
        assertEquals(Base64.encode(Networking.pqcIdentity.publicBundle), okResult.verifiedBundleB64)
        // A different bundle does not hash to that fingerprint.
        val other = Networking.generatePqcIdentityKeyPair()
        val mismatch = async { Networking.peerCryptoStatus(600L, fingerprint) }
        delay(150)
        dispatchKeyResp(status = 2, target = 600L, bundle = Base64.decode(other.publicBundleB64))
        assertEquals(Networking.PeerCrypto.KEY_MISMATCH, mismatch.await().status)
        // A malformed fingerprint never verifies.
        val malformed = async { Networking.peerCryptoStatus(601L, "!!!not-base64!!!") }
        delay(150)
        dispatchKeyResp(status = 2, target = 601L, bundle = Networking.pqcIdentity.publicBundle)
        assertEquals(Networking.PeerCrypto.KEY_MISMATCH, malformed.await().status)
    }

    private suspend fun dispatchKeyResp(status: Int, target: Long, bundle: ByteArray?) {
        val frame = ByteArray(Networking.KEYRESP_HEADER_LEN + (bundle?.size ?: 0))
        frame[0] = Networking.WS_OP_GETKEY_RESP
        frame[Networking.KEYRESP_STATUS_OFFSET] = status.toByte()
        Networking.putU64Be(frame, Networking.KEYRESP_TARGET_OFFSET, target.toULong())
        bundle?.copyInto(frame, Networking.KEYRESP_HEADER_LEN)
        dispatchLiveFrame(frame, {}, {})
    }

    @Test fun trackerHelpersWriteFramesAndCompleteLookups() = runBlocking {
        assertTrue(Networking.registerTracker(42L, byteArrayOf(1, 2), byteArrayOf(3)))
        var frame = capturedPayload()
        assertEquals(WS_OP_TRACKER_REGISTER, frame[0])
        assertEquals(42L, Networking.readU64Be(frame, 1))
        out.reset()
        assertTrue(Networking.uploadTrackerReport(ByteArray(16) { 1 }, byteArrayOf(9)))
        assertEquals(WS_OP_REPORT_PUT, capturedPayload()[0])
        out.reset()

        val epoch = ByteArray(16) { (it + 1).toByte() }
        val resolved = async { Networking.resolveTrackerBundle(epoch) }
        delay(150)
        val resp = ByteArray(Networking.RESOLVE_RESP_MIN_LEN + 2)
        resp[0] = WS_OP_RESOLVE_RESP
        resp[Networking.RESOLVE_STATUS_OFFSET] = Networking.RESOLVE_FOUND_STATUS.toByte()
        epoch.copyInto(resp, Networking.RESOLVE_EPOCH_OFFSET)
        byteArrayOf(5, 6).copyInto(resp, Networking.RESOLVE_RESP_MIN_LEN)
        dispatchLiveFrame(resp, {}, {})
        assertContentEquals(byteArrayOf(5, 6), resolved.await())

        val reports = async { Networking.fetchTrackerReports(listOf(epoch, epoch)) }
        delay(150)
        val getFrame = ByteArray(Networking.REPORT_GET_RESP_HEADER_LEN + 4 + 1)
        getFrame[0] = WS_OP_REPORT_GET_RESP
        getFrame[Networking.REPORT_COUNT_OFFSET + 1] = 1
        getFrame[Networking.REPORT_GET_RESP_HEADER_LEN + 3] = 1
        getFrame[Networking.REPORT_GET_RESP_HEADER_LEN + 4] = 42
        dispatchLiveFrame(getFrame, {}, {})
        assertEquals(1, reports.await().size)

        out.reset()
        assertTrue(Networking.registerPoweredOffEids(42L, listOf(ByteArray(20)), byteArrayOf(1, 2)))
        assertEquals(WS_OP_POF_REGISTER, capturedPayload()[0])
    }

    @Test fun generatedKeysHaveUsableShapes() {
        val linkKey = Networking.generatePqcLinkKey()
        assertTrue(linkKey.seedB64Url.isNotBlank())
        assertFalse(linkKey.seedB64Url.contains('='))
        assertTrue(Base64.decode(linkKey.publicBundleB64).isNotEmpty())
        val pair = Networking.generatePqcIdentityKeyPair()
        assertTrue(Base64.decode(pair.publicBundleB64).isNotEmpty())
        assertTrue(Base64.decode(pair.privateBundleB64).isNotEmpty())
    }

    @Test fun superviseConnectionPingsAndAbortsOnCancel() = runBlocking {
        val job = async {
            session.superviseConnection(
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
                this,
                {},
                {},
            )
        }
        delay(300)
        // The supervisor's first act is a ping frame on the wire.
        assertTrue(out.size() > 0, "supervisor pinged the session")
        job.cancel()
        delay(100)
        assertTrue(job.isCancelled || job.isCompleted)
    }
}
