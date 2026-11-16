package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalConversation
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalEvent
import com.vayunmathur.communicate.data.signal.SignalGroups
import com.vayunmathur.communicate.data.signal.SignalProtocol
import com.vayunmathur.communicate.data.signal.acceptCall
import com.vayunmathur.communicate.data.signal.call.SignalCallManager
import com.vayunmathur.communicate.data.signal.emitCallState
import com.vayunmathur.communicate.data.signal.endCall
import com.vayunmathur.communicate.data.signal.handleCallMessage
import com.vayunmathur.communicate.data.signal.placeCall
import com.vayunmathur.communicate.data.signal.placeGroupCall
import com.vayunmathur.communicate.data.signal.publishCallState
import com.vayunmathur.communicate.data.signal.rejectCall
import com.vayunmathur.communicate.data.signal.setCallAudioEnabled
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * Y1: SignalCalls — placing / answering / rejecting / ending calls and
 * inbound CallMessage dispatch, all with no RingRTC manager
 * (appContext = null, so the lazy manager is null) and no server.
 * Everything must fail closed: no crashes, no spurious CallOffer, and
 * the shared call registry only moves when publishCallState drives it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y1SignalCallsTest {

    private lateinit var db: SignalDatabase
    private val selfAci = "aaaaaaaa-1111-2222-3333-444455556666"
    private val peer = "3f0e5a2c-1111-2222-3333-444455556666"

    @BeforeTest
    fun open() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        SignalClient.db = db
        SignalClient.e2e = null
        SignalClient.authData = SignalAuthData(phoneNumber = "+14155552671", aci = selfAci, password = "secret")
        SignalClient.socket = null
        SignalClient.unauthSocket = null
        SignalClient.appContext = null
        SignalClient.pendingIncomingCallAci = null
        SignalClient.pendingIncomingCallId = null
        SignalClient.pendingIdentityChanges.clear()
        resetRegistry()
    }

    @AfterTest
    fun close() {
        SignalClient.db = null
        SignalClient.authData = null
        SignalClient.e2e = null
        SignalClient.appContext = null
        SignalClient.pendingIncomingCallAci = null
        SignalClient.pendingIncomingCallId = null
        SignalClient.pendingIdentityChanges.clear()
        resetRegistry()
        db.close()
    }

    private fun resetRegistry() {
        InAppCallRegistry.onEnded("reset")
        InAppCallRegistry.clearEnded()
        InAppCallRegistry.videoController = null
    }

    private fun envelope(): SignalProtocol.SignalEnvelope = SignalProtocol.SignalEnvelope(
        type = SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET,
        sourceAci = peer, sourceDevice = 1,
        timestamp = 1000L, serverTimestamp = 61_000L,
        content = ByteArray(0), urgent = true,
    )

    private fun callMessage(block: SignalServiceProtos.CallMessage.Builder.() -> Unit): SignalServiceProtos.CallMessage =
        SignalServiceProtos.CallMessage.newBuilder().apply(block).build()

    // ---- place / accept / reject / end without a manager ----

    @Test
    fun placeCall_withoutManagerOrRegistrationDoesNotThrow() = runTest {
        // Registered identity but no RingRTC: returns silently (fire-and-forget).
        SignalClient.placeCall(peer, video = false)
        SignalClient.placeCall(peer, video = true)
        SignalClient.placeCall("+14155550001", video = false)
        // No registration at all: the very first guard refuses.
        SignalClient.authData = SignalAuthData(phoneNumber = "+14155552671", aci = "", password = "x")
        SignalClient.placeCall(peer, video = false)
        SignalClient.authData = null
        SignalClient.placeCall(peer, video = false)
        assertEquals(InAppCallPhase.Idle, InAppCallRegistry.state.value.phase)
    }

    @Test
    fun acceptCall_parsesIdAndFailsWithoutManager() {
        assertFalse(SignalClient.acceptCall("12345"))
        assertFalse(SignalClient.acceptCall("0"))
        assertFalse(SignalClient.acceptCall("-7"))
        assertFalse(SignalClient.acceptCall(""))
        assertFalse(SignalClient.acceptCall("12x"))
        assertFalse(SignalClient.acceptCall("999999999999999999999"))
    }

    @Test
    fun rejectCall_withoutManagerEmitsEndedRejectedAndReturnsTrue() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.rejectCall("4242"))
        job.cancel()
        val ended = seen.filterIsInstance<SignalEvent.CallEnded>().single()
        assertEquals("4242", ended.callId)
        assertEquals("rejected", ended.reason)
    }

    @Test
    fun rejectCall_nonNumericIdStillEndsLocally() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.rejectCall("not-a-call"))
        job.cancel()
        assertEquals("not-a-call", seen.filterIsInstance<SignalEvent.CallEnded>().single().callId)
    }

    @Test
    fun endAndMute_withoutManagerFailClosed() {
        assertFalse(SignalClient.endCall())
        assertFalse(SignalClient.setCallAudioEnabled(true))
        assertFalse(SignalClient.setCallAudioEnabled(false))
    }

    @Test
    fun sharedControllers_withoutManagerAreSafeNoOps() = runTest {
        // The controller the call UI / Telecom binds to must not throw offline.
        SignalClient.signalCallController.reject()
        SignalClient.signalCallController.hangup()
        SignalClient.signalCallController.setMuted(true)
        SignalClient.signalCallController.setMuted(false)
        SignalClient.signalCallController.setSpeaker(true)
        // Answer with nothing pending: logged and dropped, pending stays null.
        SignalClient.signalCallController.answer()
        assertNull(SignalClient.pendingIncomingCallId)
        // Answer with a stale pending id but no manager: still no throw, id kept.
        SignalClient.pendingIncomingCallId = 99L
        SignalClient.signalCallController.answer()
        assertEquals(99L, SignalClient.pendingIncomingCallId)
        // Video controller mirrors the same manager-less contract.
        SignalClient.signalVideoController.setVideoEnabled(true)
        SignalClient.signalVideoController.flipCamera()
        assertNull(SignalClient.signalVideoController.eglContext())
        SignalClient.signalVideoController.attachLocalRenderer(null)
        SignalClient.signalVideoController.attachRemoteRenderer(null)
        SignalClient.signalVideoController.setScreenShareEnabled(true, null)
    }

    @Test
    fun placeGroupCall_guardsNonGroupUnknownAndKeyless() = runTest {
        assertFalse(SignalClient.placeGroupCall(peer))
        assertFalse(SignalClient.placeGroupCall("+14155550001"))
        assertFalse(SignalClient.placeGroupCall("group:absent"))
        db.conversationDao().upsert(SignalConversation(chatId = "group:keyless-call", isGroup = true))
        assertFalse(SignalClient.placeGroupCall("group:keyless-call"))
        // Even a fully-formed group cannot start: there is no RingRTC manager.
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        db.conversationDao().upsert(
            SignalConversation(chatId = convId, isGroup = true, name = "G", participants = peer, groupMasterKey = masterKey),
        )
        assertFalse(SignalClient.placeGroupCall(convId))
        assertEquals(InAppCallPhase.Idle, InAppCallRegistry.state.value.phase)
    }

    // ---- emitCallState mapping ----

    @Test
    fun emitCallState_mapsEveryPhaseToEvents() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        SignalClient.emitCallState(11L, SignalCallManager.CallState.Ringing, isVideo = true)
        SignalClient.emitCallState(11L, SignalCallManager.CallState.Connecting, isVideo = true)
        SignalClient.emitCallState(11L, SignalCallManager.CallState.Connected, isVideo = false)
        SignalClient.emitCallState(11L, SignalCallManager.CallState.Ended, isVideo = false)
        job.cancel()
        val phases = seen.filterIsInstance<SignalEvent.CallStateChanged>().map { it.phase }
        assertEquals(listOf("ringing", "connecting", "connected"), phases)
        assertTrue(seen.filterIsInstance<SignalEvent.CallStateChanged>().first().isVideo)
        val ended = seen.filterIsInstance<SignalEvent.CallEnded>().single()
        assertEquals("11", ended.callId)
        assertEquals("ended", ended.reason)
    }

    // ---- publishCallState / registry mirroring ----

    @Test
    fun publishCallState_outgoingLifecycleDrivesRegistry() = runTest {
        SignalClient.publishCallState(peer, SignalCallManager.CallState.Ringing, isVideo = true)
        var state = InAppCallRegistry.state.value
        assertEquals(InAppCallPhase.Outgoing, state.phase)
        assertEquals(peer, state.peerId)
        assertEquals(CommunicateLine.Signal, state.line)
        assertTrue(state.isVideo)

        SignalClient.publishCallState(peer, SignalCallManager.CallState.Connecting, isVideo = true)
        assertEquals(InAppCallPhase.Connecting, InAppCallRegistry.state.value.phase)
        SignalClient.publishCallState(peer, SignalCallManager.CallState.Connected, isVideo = true)
        state = InAppCallRegistry.state.value
        assertEquals(InAppCallPhase.Active, state.phase)
        assertTrue(state.connectedAtMs > 0L)

        SignalClient.publishCallState(peer, SignalCallManager.CallState.Ended, isVideo = true)
        assertEquals(InAppCallPhase.Ended, InAppCallRegistry.state.value.phase)
    }

    @Test
    fun publishCallState_incomingRingingUsesPendingOffer() = runTest {
        SignalClient.pendingIncomingCallAci = peer
        SignalClient.pendingIncomingCallId = 777L
        SignalClient.publishCallState(peer, SignalCallManager.CallState.Ringing, isVideo = false)
        val state = InAppCallRegistry.state.value
        assertEquals(InAppCallPhase.Incoming, state.phase)
        assertEquals(peer, state.peerId)
        // Ending clears the pending offer marker.
        SignalClient.publishCallState(peer, SignalCallManager.CallState.Ended, isVideo = false)
        assertNull(SignalClient.pendingIncomingCallAci)
        assertEquals(InAppCallPhase.Ended, InAppCallRegistry.state.value.phase)
    }

    @Test
    fun publishCallState_endedWhileIdleStaysIdleAndSecondCallRebinds() = runTest {
        SignalClient.publishCallState(peer, SignalCallManager.CallState.Ended, isVideo = false)
        assertEquals(InAppCallPhase.Idle, InAppCallRegistry.state.value.phase)
        // A later call to a different peer rebinds from scratch.
        val other = "11111111-2222-3333-4444-555555555555"
        SignalClient.publishCallState(other, SignalCallManager.CallState.Ringing, isVideo = false)
        assertEquals(other, InAppCallRegistry.state.value.peerId)
        assertEquals(InAppCallPhase.Outgoing, InAppCallRegistry.state.value.phase)
        // While non-idle, a state for another peer does not steal the binding.
        SignalClient.publishCallState(peer, SignalCallManager.CallState.Connected, isVideo = false)
        assertEquals(other, InAppCallRegistry.state.value.peerId)
        assertEquals(InAppCallPhase.Active, InAppCallRegistry.state.value.phase)
    }

    // ---- inbound CallMessage dispatch without RingRTC ----

    @Test
    fun inboundOffer_forOtherDeviceIsIgnoredEvenByPendingMarkers() = runTest {
        val msg = callMessage {
            setDestinationDeviceId(99)
            setOffer(
                SignalServiceProtos.CallMessage.Offer.newBuilder()
                    .setId(5L).setOpaque(ByteString.copyFrom(byteArrayOf(1)))
                    .setType(SignalServiceProtos.CallMessage.Offer.Type.OFFER_VIDEO_CALL),
            )
        }
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        SignalClient.handleCallMessage(msg, peer, 1, envelope(), 1000L)
        job.cancel()
        assertTrue(seen.isEmpty())
        assertNull(SignalClient.pendingIncomingCallAci)
        assertNull(SignalClient.pendingIncomingCallId)
    }

    @Test
    fun inboundOffer_forOurDeviceButNoManagerIsDroppedSilently() = runTest {
        SignalClient.authData = SignalAuthData(
            phoneNumber = "+14155552671", aci = selfAci, password = "secret", deviceId = 2,
        )
        val forUs = callMessage {
            setDestinationDeviceId(2)
            setOffer(
                SignalServiceProtos.CallMessage.Offer.newBuilder()
                    .setId(6L).setOpaque(ByteString.copyFrom(byteArrayOf(1, 2))),
            )
        }
        val wrongDevice = callMessage {
            setDestinationDeviceId(1)
            setOffer(
                SignalServiceProtos.CallMessage.Offer.newBuilder()
                    .setId(6L).setOpaque(ByteString.copyFrom(byteArrayOf(1))),
            )
        }
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        SignalClient.handleCallMessage(wrongDevice, peer, 1, envelope(), 1000L)
        SignalClient.handleCallMessage(forUs, peer, 1, envelope(), 1000L)
        job.cancel()
        // No manager: no CallOffer is fabricated and nothing is left pending.
        assertTrue(seen.filterIsInstance<SignalEvent.CallOffer>().isEmpty())
        assertNull(SignalClient.pendingIncomingCallAci)
    }

    @Test
    fun inboundAnswerIceHangupBusyOpaque_withoutManagerEmitNothing() = runTest {
        val answer = callMessage {
            setAnswer(SignalServiceProtos.CallMessage.Answer.newBuilder().setId(7L).setOpaque(ByteString.copyFrom(byteArrayOf(3))))
        }
        val ice = callMessage {
            addIceUpdate(SignalServiceProtos.CallMessage.IceUpdate.newBuilder().setId(7L).setOpaque(ByteString.copyFrom(byteArrayOf(4))))
            addIceUpdate(SignalServiceProtos.CallMessage.IceUpdate.newBuilder().setId(7L).setOpaque(ByteString.copyFrom(byteArrayOf(5))))
        }
        val hangup = callMessage {
            setHangup(
                SignalServiceProtos.CallMessage.Hangup.newBuilder().setId(7L)
                    .setType(SignalServiceProtos.CallMessage.Hangup.Type.HANGUP_NORMAL).setDeviceId(1),
            )
        }
        val busy = callMessage {
            setBusy(SignalServiceProtos.CallMessage.Busy.newBuilder().setId(7L))
        }
        val opaque = callMessage {
            setOpaque(SignalServiceProtos.CallMessage.Opaque.newBuilder().setData(ByteString.copyFrom(byteArrayOf(9))))
        }
        val empty = SignalServiceProtos.CallMessage.getDefaultInstance()
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        for (msg in listOf(answer, ice, hangup, busy, opaque, empty)) {
            SignalClient.handleCallMessage(msg, peer, 1, envelope(), 2000L)
        }
        job.cancel()
        // With no manager every branch drops before emitting; in particular
        // hangup/busy do NOT emit CallEnded (that only happens past RingRTC).
        assertTrue(seen.isEmpty(), "got $seen")
        assertEquals(InAppCallPhase.Idle, InAppCallRegistry.state.value.phase)
    }

    @Test
    fun inboundCallMessage_withBlankLocalAciIsDropped() = runTest {
        SignalClient.authData = SignalAuthData(phoneNumber = "+14155552671", aci = "", password = "x")
        val offer = callMessage {
            setOffer(SignalServiceProtos.CallMessage.Offer.newBuilder().setId(8L).setOpaque(ByteString.copyFrom(byteArrayOf(1))))
        }
        SignalClient.handleCallMessage(offer, peer, 1, envelope(), 1000L)
        assertNull(SignalClient.pendingIncomingCallAci)
    }
}
