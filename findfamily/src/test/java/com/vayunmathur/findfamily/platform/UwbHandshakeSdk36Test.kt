package com.vayunmathur.findfamily.platform

import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.domain.UwbEnvelope
import com.vayunmathur.findfamily.domain.UwbEnvelopeKind
import com.vayunmathur.findfamily.domain.UwbHandshake
import com.vayunmathur.findfamily.domain.UwbInbox
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.findfamily.network.dispatchLiveFrame
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.util.DataStoreUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.Executor
import kotlin.io.encoding.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Captures the [RangingSession.Callback] the controller registers, so tests can drive it. */
@Implements(RangingManager::class)
class ShadowRangingManager {
    @Implementation
    fun createRangingSession(executor: Executor, callback: RangingSession.Callback): RangingSession {
        lastCallback = callback
        sessionsCreated++
        return org.robolectric.shadow.api.Shadow.newInstanceOf(RangingSession::class.java)
    }

    companion object {
        var lastCallback: RangingSession.Callback? = null
        var sessionsCreated = 0
        fun reset() {
            lastCallback = null
            sessionsCreated = 0
        }
    }
}

/** No-op session lifecycle: the radio never answers on its own in the sandbox. */
@Implements(RangingSession::class)
class ShadowRangingSession {
    @Implementation
    fun start(preference: RangingPreference): android.os.CancellationSignal = android.os.CancellationSignal()

    @Implementation
    fun stop() = Unit

    @Implementation
    fun close() = Unit
}

/**
 * [UwbSessionManager] handshakes on SDK 36 with a real PQC identity and a fake
 * [WsSession], so envelope publishes succeed and the ACK/CONFIG waits can be driven
 * through [UwbInbox]. The captured outbound frames are decrypted with
 * [dispatchLiveFrame] to learn the random session id the manager chose.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], shadows = [ShadowRangingManager::class, ShadowRangingSession::class])
class UwbHandshakeSdk36Test {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo: FindFamilyRepository get() = repoRef!!
    private lateinit var out: ByteArrayOutputStream

    @BeforeTest
    fun setup() {
        runBlocking {
            if (repoRef == null) {
                repoRef = TestFindFamilyRepository.install(ctx)
                Networking.init(repoRef!!, DataStoreUtils.getInstance(ctx), "Me")
            }
            Networking.repository = repoRef!!
            out = ByteArrayOutputStream()
            Networking.wsSession = fakeSession(out)
            ShadowRangingManager.reset()
            UwbSessionManager.init(ctx, repoRef!!)
            UwbSessionManager.stop()
            settle()
        }
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

    /** Unmasks the client frame(s) written to [out] and returns the last payload. */
    private fun capturedPayload(): ByteArray {
        val frame = out.toByteArray()
        assertTrue(frame.size >= 6, "a masked client frame was written")
        var off = 0
        var last: ByteArray? = null
        while (off + 2 <= frame.size) {
            assertEquals(0x82, frame[off].toInt() and 0xff)
            var len = frame[off + 1].toInt() and 0x7f
            var p = off + 2
            if (len == 126) {
                len = ((frame[p].toInt() and 0xff) shl 8) or (frame[p + 1].toInt() and 0xff)
                p += 2
            } else if (len == 127) {
                var wide = 0L
                for (i in 0 until 8) wide = (wide shl 8) or (frame[p + i].toLong() and 0xff)
                len = wide.toInt()
                p += 8
            }
            val mask = frame.copyOfRange(p, p + 4)
            p += 4
            last = ByteArray(len) { i -> (frame[p + i].toInt() xor mask[i % 4].toInt()).toByte() }
            off = p + len
        }
        return assertNotNull(last)
    }

    /** The most recent UWB envelope the manager published, decrypted from the wire. */
    private suspend fun lastPublishedEnvelope(): UwbEnvelope {
        val frame = capturedPayload()
        val delivered = mutableListOf<UwbEnvelope>()
        val msg = byteArrayOf(Networking.WS_OP_MSG, Networking.WS_FLAG_UWB.toByte()) +
            frame.copyOfRange(10, frame.size)
        dispatchLiveFrame(msg, {}, { delivered += it })
        return delivered.single()
    }

    private suspend fun settle(timeoutMs: Long = 8_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (UwbSessionManager.state.value is UwbSessionManager.UwbSessionState.Idle &&
                UwbSessionManager.peerUserId.value == null
            ) return true
            delay(50)
        }
        return false
    }

    private suspend fun awaitState(timeoutMs: Long = 8_000, match: (UwbSessionManager.UwbSessionState) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (match(UwbSessionManager.state.value)) return true
            delay(50)
        }
        return match(UwbSessionManager.state.value)
    }

    private suspend fun addPeer(platform: String?): Long {
        val bundle = Base64.encode(Networking.pqcIdentity.publicBundle)
        return repo.upsertUser(
            User(
                name = "Peer-$platform",
                photo = null,
                locationName = "Home",
                sendingEnabled = true,
                requestStatus = RequestStatus.MUTUAL_CONNECTION,
                lastLocationChangeTime = Clock.System.now(),
                platform = platform,
                pqcEncryptionKey = bundle,
            )
        )
    }

    @Test fun initiatorReachesWaitingThenRangesOnAck() = runBlocking {
        val peerId = addPeer(platform = "android")
        out.reset()
        UwbSessionManager.startAsInitiator(peerId)
        assertTrue(
            awaitState { it is UwbSessionManager.UwbSessionState.WaitingForPeer },
            "initiator must wait for the ACK, was ${UwbSessionManager.state.value}",
        )
        val request = lastPublishedEnvelope()
        assertEquals(UwbEnvelopeKind.REQUEST, request.kind)
        assertEquals(peerId, UwbSessionManager.peerUserId.value)
        // Starting again mid-session is ignored (one session at a time).
        UwbSessionManager.startAsInitiator(peerId)
        UwbInbox.tryEmit(
            UwbEnvelope(
                sessionId = request.sessionId,
                sender = peerId.toULong(),
                senderPlatform = "android",
                kind = UwbEnvelopeKind.ACK,
                payload = UwbHandshake(addressB64 = UwbBytes.b64(byteArrayOf(5, 6))),
            )
        )
        // The ranging stream opens (shadow radio never reports): still waiting,
        // but the controller session was created and its callback registered.
        assertTrue(awaitState(5_000) { ShadowRangingManager.lastCallback != null })
        ShadowRangingManager.lastCallback!!.onOpened()
        // A ranging result folds into the state machine as a Ranging sample.
        // (RangingMeasurement/RangingDevice builders are hidden in the stub SDK,
        // so the fixtures are built reflectively against the runtime classes.)
        val data = rangingData(distance = 2.5, azimuthDeg = 30.0)
        val device = rangingDevice()
        ShadowRangingManager.lastCallback!!.onResults(device, data)
        assertTrue(
            awaitState { it is UwbSessionManager.UwbSessionState.Ranging },
            "sample must surface as Ranging, was ${UwbSessionManager.state.value}",
        )
        val ranging = UwbSessionManager.state.value as UwbSessionManager.UwbSessionState.Ranging
        assertEquals(2.5f, ranging.sample.distanceMeters)
        // Peer going away surfaces as PeerDisconnected.
        ShadowRangingManager.lastCallback!!.onStopped(device, android.ranging.RangingManager.UWB)
        assertTrue(
            awaitState { it is UwbSessionManager.UwbSessionState.PeerDisconnected },
            "stop must surface as PeerDisconnected, was ${UwbSessionManager.state.value}",
        )
        UwbSessionManager.stop()
        assertTrue(settle())
    }

    @Test fun initiatorSeesPeerUnsupportedOnDecline() = runBlocking {
        val peerId = addPeer(platform = "android")
        out.reset()
        UwbSessionManager.startAsInitiator(peerId)
        assertTrue(awaitState { it is UwbSessionManager.UwbSessionState.WaitingForPeer })
        val request = lastPublishedEnvelope()
        UwbInbox.tryEmit(
            UwbEnvelope(
                sessionId = request.sessionId,
                sender = peerId.toULong(),
                senderPlatform = "android",
                kind = UwbEnvelopeKind.CANCEL,
                reason = com.vayunmathur.findfamily.domain.UwbCancelReason.UNSUPPORTED,
            )
        )
        assertTrue(
            awaitState { it is UwbSessionManager.UwbSessionState.PeerUnsupported },
            "decline must land in PeerUnsupported, was ${UwbSessionManager.state.value}",
        )
        assertEquals(peerId, UwbSessionManager.peerUserId.value)
        UwbSessionManager.stop()
        assertTrue(settle())
    }

    @Test fun initiatorFailsOnAckWithoutAddress() = runBlocking {
        val peerId = addPeer(platform = "android")
        out.reset()
        UwbSessionManager.startAsInitiator(peerId)
        assertTrue(awaitState { it is UwbSessionManager.UwbSessionState.WaitingForPeer })
        val request = lastPublishedEnvelope()
        UwbInbox.tryEmit(
            UwbEnvelope(
                sessionId = request.sessionId,
                sender = peerId.toULong(),
                senderPlatform = "android",
                kind = UwbEnvelopeKind.ACK,
            )
        )
        assertTrue(settle(), "address-less ACK must fail closed to Idle, was ${UwbSessionManager.state.value}")
    }

    @Test fun iosInitiatorFailsWhenShareableConfigUnparseable() = runBlocking {
        val peerId = addPeer(platform = "ios")
        out.reset()
        UwbSessionManager.startAsInitiator(peerId)
        assertTrue(awaitState { it is UwbSessionManager.UwbSessionState.WaitingForPeer })
        val request = lastPublishedEnvelope()
        assertEquals(UwbEnvelopeKind.REQUEST, request.kind)
        UwbInbox.tryEmit(
            UwbEnvelope(
                sessionId = request.sessionId,
                sender = peerId.toULong(),
                senderPlatform = "ios",
                kind = UwbEnvelopeKind.CONFIG,
                payload = UwbHandshake(shareableConfigDataB64 = UwbBytes.b64(byteArrayOf(1, 2, 3))),
            )
        )
        // The accessory parser is stubbed and throws: the session fails closed.
        assertTrue(settle(), "unparseable iOS config must fail closed to Idle, was ${UwbSessionManager.state.value}")
    }

    @Test fun responderAcksAndroidRequestAndStartsRanging() = runBlocking {
        val peerId = addPeer(platform = "android")
        out.reset()
        UwbInbox.tryEmit(
            UwbEnvelope(
                sessionId = "resp-session-1",
                sender = peerId.toULong(),
                senderPlatform = "android",
                kind = UwbEnvelopeKind.REQUEST,
                payload = UwbHandshake(
                    addressB64 = UwbBytes.b64(byteArrayOf(3, 4)),
                    sessionId = 21,
                    sessionKeyB64 = UwbBytes.b64(ByteArray(8) { 2 }),
                    channelNumber = 9,
                    preambleIndex = 10,
                ),
            )
        )
        assertTrue(
            awaitState(5_000) { ShadowRangingManager.lastCallback != null },
            "responder must open ranging after ACK, state=${UwbSessionManager.state.value}",
        )
        val ack = lastPublishedEnvelope()
        assertEquals(UwbEnvelopeKind.ACK, ack.kind)
        assertEquals("resp-session-1", ack.sessionId)
        UwbSessionManager.stop()
        assertTrue(settle())
    }

    @Test fun responderIosRequestFailsWhenShareableConfigUnparseable() = runBlocking {
        val peerId = addPeer(platform = "ios")
        out.reset()
        UwbInbox.tryEmit(
            UwbEnvelope(
                sessionId = "resp-ios-1",
                sender = peerId.toULong(),
                senderPlatform = "ios",
                kind = UwbEnvelopeKind.REQUEST,
            )
        )
        assertTrue(awaitState { it is UwbSessionManager.UwbSessionState.WaitingForPeer })
        val config = lastPublishedEnvelope()
        assertEquals(UwbEnvelopeKind.CONFIG, config.kind)
        UwbInbox.tryEmit(
            UwbEnvelope(
                sessionId = "resp-ios-1",
                sender = peerId.toULong(),
                senderPlatform = "ios",
                kind = UwbEnvelopeKind.CONFIG,
                payload = UwbHandshake(shareableConfigDataB64 = UwbBytes.b64(byteArrayOf(9))),
            )
        )
        assertTrue(settle(), "unparseable iOS config must fail closed to Idle, was ${UwbSessionManager.state.value}")
    }

    @Test fun openFailedRetryDropsTierThenDisconnects() = runBlocking {
        val peerId = addPeer(platform = "android")
        out.reset()
        UwbSessionManager.startAsInitiator(peerId)
        assertTrue(awaitState { it is UwbSessionManager.UwbSessionState.WaitingForPeer })
        val request = lastPublishedEnvelope()
        UwbInbox.tryEmit(
            UwbEnvelope(
                sessionId = request.sessionId,
                sender = peerId.toULong(),
                senderPlatform = "android",
                kind = UwbEnvelopeKind.ACK,
                payload = UwbHandshake(addressB64 = UwbBytes.b64(byteArrayOf(5, 6))),
            )
        )
        assertTrue(awaitState(5_000) { ShadowRangingManager.lastCallback != null })
        val cb = ShadowRangingManager.lastCallback!!
        val before = ShadowRangingManager.sessionsCreated
        // REASON_UNSUPPORTED (3) at tier 2: drop a tier and retry with a new session.
        cb.onOpenFailed(3)
        assertEquals(before + 1, ShadowRangingManager.sessionsCreated)
        // A non-retryable failure ends the stream as a disconnect (which the
        // stream collector then tears down to Idle).
        ShadowRangingManager.lastCallback!!.onOpenFailed(4)
        assertTrue(
            awaitState {
                it is UwbSessionManager.UwbSessionState.PeerDisconnected ||
                    (it is UwbSessionManager.UwbSessionState.Idle && UwbSessionManager.peerUserId.value == null)
            },
            "open failure must surface as disconnect, was ${UwbSessionManager.state.value}",
        )
        UwbSessionManager.stop()
        assertTrue(settle())
    }

    private fun builder(className: String): Any {
        val cls = Class.forName(className)
        val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
        return ctor.newInstance()
    }

    private fun Any.call(name: String, vararg args: Any?): Any? {
        val method = javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: javaClass.getDeclaredMethod(name, *args.map { paramType(it) }.toTypedArray())
                .apply { isAccessible = true }
        try {
            return method.invoke(this, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw AssertionError("reflective $name failed: ${e.cause}", e.cause)
        }
    }

    private fun paramType(arg: Any?): Class<*> = when (arg) {
        is Double -> Double::class.javaPrimitiveType!!
        is Int -> Int::class.javaPrimitiveType!!
        is Long -> Long::class.javaPrimitiveType!!
        null -> Any::class.java
        else -> arg.javaClass
    }

    private fun measurement(value: Double, confidence: Int): Any {
        val b = builder("android.ranging.RangingMeasurement\$Builder")
        b.call("setMeasurement", value)
        b.call("setConfidence", confidence)
        return b.call("build")!!
    }

    private fun rangingData(distance: Double, azimuthDeg: Double): android.ranging.RangingData {
        val b = builder("android.ranging.RangingData\$Builder")
        b.call("setRangingTechnology", android.ranging.RangingManager.UWB)
        b.call("setDistance", measurement(distance, 2))
        b.call("setAzimuth", measurement(Math.toRadians(azimuthDeg), 2))
        b.call("setElevation", measurement(Math.toRadians(5.0), 0))
        b.call("setTimestampMillis", System.currentTimeMillis())
        return b.call("build") as android.ranging.RangingData
    }

    private fun rangingDevice(): android.ranging.RangingDevice {
        val inner = builder("android.ranging.RangingDevice\$Builder")
        inner.call("setUuid", java.util.UUID(0L, 1L))
        val device = inner.call("build")!!
        return device as android.ranging.RangingDevice
    }

    private companion object {
        var repoRef: FindFamilyRepository? = null
    }
}
