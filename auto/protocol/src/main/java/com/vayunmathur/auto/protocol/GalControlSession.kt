package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.AudioFocusNotification
import com.vayunmathur.auto.protocol.gal.AudioFocusRequestMessage
import com.vayunmathur.auto.protocol.gal.AudioFocusRequestType
import com.vayunmathur.auto.protocol.gal.AuthComplete
import com.vayunmathur.auto.protocol.gal.ByeByeReason
import com.vayunmathur.auto.protocol.gal.ByeByeRequest
import com.vayunmathur.auto.protocol.gal.CallAvailabilityStatus
import com.vayunmathur.auto.protocol.gal.ChannelOpenRequest
import com.vayunmathur.auto.protocol.gal.ChannelOpenResponse
import com.vayunmathur.auto.protocol.gal.MessageStatus
import com.vayunmathur.auto.protocol.gal.NavigationFocusNotification
import com.vayunmathur.auto.protocol.gal.PingRequest
import com.vayunmathur.auto.protocol.gal.PingResponse
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.ServiceDiscoveryRequest
import com.vayunmathur.auto.protocol.gal.ServiceDiscoveryResponse
import com.vayunmathur.auto.protocol.gal.ServiceDiscoveryUpdate
import com.vayunmathur.auto.protocol.gal.VideoFocusIndication
import com.vayunmathur.auto.protocol.gal.VideoFocusMode
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status

/** A control message the session wants sent. */
class OutboundMessage(
    val type: Int,
    val payload: ByteArray,
    /** Whether the frame should be TLS-wrapped and flagged [FrameFlags.ENCRYPTED]. */
    val encrypted: Boolean,
    /**
     * Whether to set the frame's [FrameFlags.CONTROL] bit.
     *
     * Only the channel-open request sets this today: DHU 2.0 answers a
     * CONTROL-less 0x7 with an empty 0xff and no 0x8 (Run 5), while a
     * CONTROL-less 0x5 is accepted -- so the bit is scoped to the 0x7 path
     * rather than applied to every encrypted channel-0 send.
     */
    val isControl: Boolean = false,
    /**
     * Frame channel for sends that don't ride channel 0. The channel-open
     * request targets the channel being opened: gearhead's `izd.b()` sends via
     * `izl.g(this.b, ...)`, and a ch0-framed 0x7 is refused with
     * STATUS_INVALID_CHANNEL (-5, Run 6). Ignored for other message types.
     */
    val channelId: Int = 0,
)

/** Where the session has got to. */
enum class SessionState {
    AWAITING_VERSION,
    HANDSHAKING,
    AWAITING_AUTH_COMPLETE,
    DISCOVERING,
    ACTIVE,
    CLOSED,
}

/**
 * The GAL control channel, driven entirely by messages in and messages out.
 *
 * No transport, no threads and no I/O, so the whole bring-up can be tested against a
 * stand-in head unit on the JVM. A driver reads frames off a [GalTransport], reassembles
 * them with [FrameReader], and feeds the control channel's payloads to [onMessage].
 *
 * The order is not the one you would guess from the phone being the thing that drives
 * projection:
 *
 *  1. The **head unit** sends VersionRequest; we answer VersionResponse.
 *  2. The **head unit** opens TLS as the client; we are the server and pump the engine
 *     through message 3 in both directions.
 *  3. The **head unit** confirms with AuthComplete.
 *  4. Only then do we send ServiceDiscoveryRequest, and only at version >= 1.6.
 *
 * Everything from AuthComplete onwards is TLS-wrapped; versions and the handshake itself
 * travel in the clear.
 */
class GalControlSession(
    private val engine: SSLEngine,
    /** Android `Build.MODEL`, e.g. "Pixel 8". */
    private val deviceModel: String,
    /** Android `Build.MANUFACTURER`, e.g. "Google". Defaults for host tests. */
    private val deviceManufacturer: String = deviceModel,
    private val supportedVersion: GalVersion = VersionNegotiation.SUPPORTED,
) {
    var state: SessionState = SessionState.AWAITING_VERSION
        private set

    var negotiatedVersion: GalVersion? = null
        private set

    /** Services the head unit advertised, empty until discovery completes. */
    var services: List<Service> = emptyList()
        private set

    /** Set when the session ends abnormally. */
    var failure: String? = null
        private set

    /** Service ids whose ChannelOpenResponse came back STATUS_SUCCESS. */
    var openChannels: Set<Int> = emptySet()
        private set

    /**
     * Service ids the head unit rejected with a non-SUCCESS ChannelOpenResponse.
     * Kept so the driver can move on to the next service instead of retrying a
     * refused open forever.
     */
    var refusedChannels: Set<Int> = emptySet()
        private set

    /** Ids with an open request in flight, in the order requested. */
    var pendingChannels: List<Int> = emptyList()
        private set

    /**
     * Last call-availability state the head unit reported (control 24), or
     * null before the first one. Observed by the phone-status owner; the
     * control channel itself never answers.
     */
    var callAvailable: Boolean? = null
        private set

    /**
     * Who owns the screen and the speakers. Fed by the control notifications below
     * and, for video, by [onVideoFocusIndication] (the 0x8008 rides the video
     * channel, so the sink forwards it). The driver observes [onFocusChange].
     */
    val focus = FocusArbitration()

    /**
     * Fires whenever [focus] accepts a change, on whatever thread drives the session.
     * The Android side mirrors this into `AutoSessionState` without polling.
     */
    var onFocusChange: (FocusChange) -> Unit = {}

    /** Server-side TLS prober: buffers message-3 records and runs the engine. */
    private val handshakePump = HandshakePump(
        engine,
        onFinished = { state = SessionState.AWAITING_AUTH_COMPLETE },
        onFailed = { reason -> fail(reason) },
    )

    /** Handles one control-channel message and returns whatever should go back. */
    fun onMessage(type: Int, payload: ByteArray): List<OutboundMessage> =
        onSessionMessage(type, payload)
            ?: onKeepAliveMessage(type, payload)
            ?: onFocusMessage(type, payload)
            ?: fail("unexpected control message type 0x${type.toString(HEX_RADIX)}")

    /**
     * Bring-up traffic: version, TLS, auth, discovery and channel opens.
     * Null when [type] belongs to another group below.
     */
    private fun onSessionMessage(type: Int, payload: ByteArray): List<OutboundMessage>? = when (type) {
        GalMessage.Control.VERSION_REQUEST -> onVersionRequest(payload)
        GalMessage.Control.SSL_HANDSHAKE -> onHandshakeData(payload)
        GalMessage.Control.AUTH_COMPLETE -> onAuthComplete(payload)
        GalMessage.Control.SERVICE_DISCOVERY_RESPONSE -> onServiceDiscovery(payload)
        GalMessage.Control.CHANNEL_OPEN_RESPONSE -> onChannelOpen(payload)
        // Hot-add/update of one service after the initial discovery (FINDINGS.md
        // control table, id 26): merged into [services] so the driver's
        // wire-order open loop picks it up without a reconnect. This is how an
        // input source that was absent from the initial 0x6 can still appear.
        GalMessage.Control.SERVICE_DISCOVERY_UPDATE -> onServiceDiscoveryUpdate(payload)
        // The head unit's rejection of one of our messages. Observed, not fatal:
        // gearhead's `izu` logs it (`ai(1698)`) and carries on, and channel-level
        // errors are per-channel (`iza` answers 0xff on the channel itself). The
        // transport stays alive -- pings keep flowing -- so the session must too.
        // The payload names the offending message; the trace in GalConnection
        // prints it. Killing the session here is what turned a recoverable
        // per-message rejection into a dead bring-up.
        //
        // When the error is bare (empty payload, no channel context) and an open
        // is in flight, it is that open's refusal: DHU 2.0 answers this way
        // instead of sending 0x8. Draining the queue lets the driver move on to
        // the next service instead of stalling with a phantom pending open.
        GalMessage.Control.MESSAGE_ERROR -> onMessageError()
        // The head unit says we framed badly: answered in kind, then gone.
        GalMessage.Control.FRAMING_ERROR -> onFramingError()
        else -> null
    }

    /**
     * Liveness traffic: pings and the polite teardown.
     * Null when [type] belongs to another group.
     */
    private fun onKeepAliveMessage(type: Int, payload: ByteArray): List<OutboundMessage>? = when (type) {
        GalMessage.Control.PING_REQUEST -> onPing(payload)
        GalMessage.Control.BYEBYE_REQUEST -> onByeBye()
        GalMessage.Control.BYEBYE_RESPONSE -> {
            state = SessionState.CLOSED
            emptyList()
        }
        // An inbound ping response answers our own pings at the transport
        // layer; the control channel itself has nothing to answer.
        GalMessage.Control.PING_RESPONSE -> emptyList()
        else -> null
    }

    /**
     * Observed state feeds: call availability and focus notifications flow into
     * [callAvailable] and [focus], never answered.
     * Null when [type] belongs to another group.
     */
    private fun onFocusMessage(type: Int, payload: ByteArray): List<OutboundMessage>? = when (type) {
        // Call availability is parsed into [callAvailable] for the higher
        // layers; the control channel itself has nothing to answer.
        GalMessage.Control.CALL_AVAILABILITY_STATUS -> onCallAvailability(payload)
        GalMessage.Control.AUDIO_FOCUS_NOTIFICATION -> onAudioFocusNotification(payload)
        GalMessage.Control.NAVIGATION_FOCUS_NOTIFICATION -> onNavigationFocusNotification(payload)
        else -> null
    }

    /**
     * Asks the head unit to open [service]'s channel.
     *
     * The channel opens asynchronously: the head unit answers with
     * ChannelOpenResponse, and [openChannels] gains the id only on
     * STATUS_SUCCESS ([refusedChannels] on anything else). Nothing may be sent
     * on the channel before that -- gearhead's `jdk.Q()` sends media setup from
     * `onChannelOpened`.
     *
     * The frame carries the CONTROL bit: Dalvik ground truth (`Ljbe.i` flag
     * assembly) shows every `jbj.k` send builds `Lizm.f = true`, which ORs in
     * 0x04, so gearhead's 0x7 goes out as FIRST|LAST|CONTROL|ENCRYPTED = 0x0F.
     * Scoped to this path only -- the CONTROL-less 0x5 is live-accepted, so no
     * other channel-0 send takes the bit.
     *
     * The frame rides the TARGET channel, not channel 0: `izd.b()` sends via
     * `izl.g(this.b, ...)`, and a ch0-framed 0x7 is refused with
     * STATUS_INVALID_CHANNEL (-5, Run 6) even though it parses.
     */
    fun openChannel(service: Service, priority: Int = 0): OutboundMessage {
        pendingChannels += service.id
        return OutboundMessage(
            type = GalMessage.Control.CHANNEL_OPEN_REQUEST,
            payload = ChannelOpenRequest.newBuilder()
                .setPriority(priority)
                .setServiceId(service.id)
                .build()
                .toByteArray(),
            encrypted = true,
            isControl = true,
            channelId = service.id,
        )
    }

    /** Ends the session politely. */
    fun disconnect(reason: ByeByeReason = ByeByeReason.USER_SELECTION): OutboundMessage =
        OutboundMessage(
            type = GalMessage.Control.BYEBYE_REQUEST,
            payload = ByeByeRequest.newBuilder().setReason(reason).build().toByteArray(),
            encrypted = true,
        )

    /**
     * Rejects one of the head unit's messages. Fire-and-forget like
     * [disconnect]: the head unit observes it and carries on.
     *
     * Bare, mirroring what a DHU sends us: the head-unit-side payload shape
     * (which message was rejected) was never recovered from the teardown, so
     * nothing is claimed here beyond the 0xff type itself.
     */
    fun sendMessageError(): OutboundMessage = OutboundMessage(
        type = GalMessage.Control.MESSAGE_ERROR,
        payload = ByteArray(0),
        encrypted = true,
    )

    /**
     * Folds a video focus indication (0x8008) into [focus].
     *
     * The indication rides the video channel, not channel 0, so the video sink parses
     * it and forwards it here; the session stays the single owner of the
     * arbitration state. Observed, never answered.
     *
     * An unset mode is ignored: the lite runtime drops unknown enum values on parse,
     * so a future mode arrives as absent rather than as a wrong known one -- never
     * clobber a known state with it.
     */
    fun onVideoFocusIndication(indication: VideoFocusIndication) {
        if (!indication.hasMode()) return
        notifyFocus(focus.onVideoFocus(indication.mode))
    }

    /**
     * Asks the head unit for audio focus (control 0x18) for the audio sinks.
     *
     * Fire-and-forget: the head unit answers with an 0x13 notification, which lands
     * in [focus] via [onAudioFocusNotification]. Encrypted like every post-auth send.
     */
    fun requestAudioFocus(type: AudioFocusRequestType): OutboundMessage = OutboundMessage(
        type = GalMessage.Control.AUDIO_FOCUS_REQUEST,
        payload = AudioFocusRequestMessage.newBuilder().setRequest(type).build().toByteArray(),
        encrypted = true,
    )

    // ---- handlers ----

    private fun onVersionRequest(payload: ByteArray): List<OutboundMessage> {
        val requested = VersionNegotiation.parseRequest(payload)
        val agreed = VersionNegotiation.negotiate(requested, supportedVersion)
        val status = if (agreed == null) {
            MessageStatus.STATUS_NO_COMPATIBLE_VERSION.number
        } else {
            MessageStatus.STATUS_SUCCESS.number
        }

        val response = OutboundMessage(
            type = GalMessage.Control.VERSION_RESPONSE,
            payload = VersionNegotiation.buildResponse(agreed ?: supportedVersion, status),
            encrypted = false,
        )

        if (agreed == null) {
            // Answer before giving up, so the head unit learns why rather than timing out.
            failure = "head unit asked for $requested, we speak $supportedVersion"
            state = SessionState.CLOSED
            return listOf(response)
        }

        negotiatedVersion = agreed
        state = SessionState.HANDSHAKING
        engine.beginHandshake()
        // The head unit is the TLS client, so it sends the first record; nothing to pump yet.
        return listOf(response)
    }

    private fun onHandshakeData(payload: ByteArray): List<OutboundMessage> {
        if (state != SessionState.HANDSHAKING) {
            return fail("handshake data arrived in state $state")
        }
        handshakePump.appendInbound(payload)
        return handshakePump.pump()
    }

    private fun onAuthComplete(payload: ByteArray): List<OutboundMessage> {
        val status = AuthComplete.parseFrom(payload).status
        if (status != MessageStatus.STATUS_SUCCESS.number) {
            return fail("head unit rejected authentication: status $status")
        }
        val version = negotiatedVersion
        if (version == null || version < VersionNegotiation.MINIMUM_FOR_DISCOVERY) {
            return fail("negotiated $version is below the service-discovery floor")
        }
        state = SessionState.DISCOVERING
        return listOf(
            OutboundMessage(
                type = GalMessage.Control.SERVICE_DISCOVERY_REQUEST,
                // Field 5 ONLY, composed exactly as gearhead does it in `jog.run()`:
                // `Build.MODEL`, with `"$MANUFACTURER "` prepended unless MODEL
                // already starts with MANUFACTURER (e.g. "Google Pixel 8").
                // Field 5 (`xoa.g`, hasbit 16) is the only unconditional field;
                // fields 1-3 (`izu.g/h/i`), 4 (`izu.f`) and 6 (`izu.j`) are sent
                // only when that state is non-null, which it is not on the DHU
                // path -- and `jog` nulls them after the first send, so repeats
                // are field-5-only by construction. A DHU answers anything else
                // here with a bare MessageError (0xff). Framing is unchanged:
                // `jbj.k(5)` routes to `o(..., true, ...)` where the `true` is
                // isEncrypted (izn.a -> 0x08), NOT the CONTROL bit, which stays
                // clear on this message -- flags FIRST|LAST|ENCRYPTED = 0x0B.
                payload = ServiceDiscoveryRequest.newBuilder()
                    .setDeviceBrand(composeDiscoveryLabel(deviceModel, deviceManufacturer))
                    .build()
                    .toByteArray(),
                encrypted = true,
            ),
        )
    }

    private fun onServiceDiscovery(payload: ByteArray): List<OutboundMessage> {
        services = ServiceDiscoveryResponse.parseFrom(payload).servicesList
        state = SessionState.ACTIVE
        return emptyList()
    }

    /**
     * Merges one hot-added (or replaced) service into [services].
     *
     * Same-id entries are replaced, not duplicated: an update may restate a
     * service with new payloads (e.g. input source gains a touchscreen), and
     * the driver's open loop keys on id membership. A payload without a
     * service is ignored -- an absent field is "no update", never a removal.
     */
    private fun onServiceDiscoveryUpdate(payload: ByteArray): List<OutboundMessage> {
        val update = ServiceDiscoveryUpdate.parseFrom(payload)
        if (!update.hasService()) return emptyList()
        val incoming = update.service
        services = services.filter { it.id != incoming.id } + incoming
        return emptyList()
    }

    /**
     * Records the head unit's answer to [openChannel].
     *
     * The channel is NOT open yet when this returns: like gearhead's `iza.a()`,
     * the driver must wait for STATUS_SUCCESS here before sending anything on
     * the new channel. Sending service messages (e.g. media setup) against a
     * channel the head unit has not opened earns a bare MessageError (0xff).
     *
     * A refusal is recorded, not fatal: the HU answers some opens with an empty
     * MessageError and never sends 0x8 at all (DHU 2.0 vs video service 2), and
     * the bring-up must continue with the remaining services. The driver moves
     * on when the refusal is visible via [refusedChannels] or a bare 0xff
     * (see [onMessageError], which drains the same queue).
     */
    private fun onChannelOpen(payload: ByteArray): List<OutboundMessage> {
        val status = ChannelOpenResponse.parseFrom(payload).status
        if (status != MessageStatus.STATUS_SUCCESS.number) {
            drainPendedToRefused()
            return emptyList()
        }
        val id = pendingChannels.firstOrNull() ?: return emptyList()
        pendingChannels -= id
        openChannels += id
        return emptyList()
    }

    /**
     * Records a bare MessageError as the refusal of the in-flight open.
     *
     * DHU 2.0 rejects some channel opens with an empty 0xff that names no
     * channel, instead of a 0x8 refusal. Without this the queue stalls: the
     * open stays "pending" forever and no further 0x7 ever goes out (Run 4).
     * The head of the queue is what the error must belong to -- nothing else
     * is in flight, since the driver sends one open at a time.
     */
    fun onMessageError(): List<OutboundMessage> {
        drainPendedToRefused()
        return emptyList()
    }

    /**
     * Folds a control-24 call-availability status into [callAvailable].
     * Observed, never answered. Never throws: malformed bytes or a payload
     * missing its field keep last-known rather than failing the session --
     * the same never-throw rule as the service-channel codecs.
     */
    private fun onCallAvailability(payload: ByteArray): List<OutboundMessage> {
        runCatching { CallAvailabilityStatus.parseFrom(payload) }.getOrNull()
            ?.takeIf { it.isInitialized && it.hasCallAvailable() }
            ?.let { callAvailable = it.callAvailable }
        return emptyList()
    }

    /**
     * The head unit says we framed badly: answer with the 2-byte 0xFFFF
     * control frame and tear down, like gearhead's `rtr` (FINDINGS.md
     * section 2). A framing error is fatal where a MessageError (0xff) is
     * not -- the stream position itself is untrustworthy.
     */
    private fun onFramingError(): List<OutboundMessage> {
        // The reply rides the normal control send path, so it matches the
        // session phase: encrypted once the handshake is behind us (every
        // post-auth send is), clear while version or TLS records are still
        // in flight.
        val encrypted = state != SessionState.AWAITING_VERSION &&
            state != SessionState.HANDSHAKING
        state = SessionState.CLOSED
        failure = "framing error"
        return listOf(
            OutboundMessage(
                type = GalMessage.Control.FRAMING_ERROR,
                payload = ByteArray(0),
                encrypted = encrypted,
            ),
        )
    }

    private fun drainPendedToRefused() {
        val id = pendingChannels.firstOrNull() ?: return
        pendingChannels -= id
        refusedChannels += id
    }

    /**
     * Folds a control 0x13 audio focus notification into [focus]. Observed, never
     * answered: like gearhead's `izu`, the session carries on regardless.
     */
    private fun onAudioFocusNotification(payload: ByteArray): List<OutboundMessage> {
        notifyFocus(focus.onAudioFocus(AudioFocusNotification.parseFrom(payload).state))
        return emptyList()
    }

    /**
     * Folds a control 0x0E navigation focus notification into [focus]. Observed,
     * never answered.
     */
    private fun onNavigationFocusNotification(payload: ByteArray): List<OutboundMessage> {
        notifyFocus(focus.onNavigationFocus(NavigationFocusNotification.parseFrom(payload).focus))
        return emptyList()
    }

    private fun notifyFocus(change: FocusChange) {
        if (change.any) onFocusChange(change)
    }

    private fun onPing(payload: ByteArray): List<OutboundMessage> = listOf(
        OutboundMessage(
            type = GalMessage.Control.PING_RESPONSE,
            payload = PingResponse.newBuilder()
                .setTimestamp(PingRequest.parseFrom(payload).timestamp)
                .build()
                .toByteArray(),
            encrypted = true,
        ),
    )

    private fun onByeBye(): List<OutboundMessage> {
        state = SessionState.CLOSED
        return listOf(
            OutboundMessage(
                type = GalMessage.Control.BYEBYE_RESPONSE,
                payload = ByteArray(0),
                encrypted = true,
            ),
        )
    }

    private fun fail(reason: String): List<OutboundMessage> {
        failure = reason
        state = SessionState.CLOSED
        return emptyList()
    }

    companion object {
        /** Radix for rendering unexpected wire types in failure messages. */
        private const val HEX_RADIX = 16
    }
}

/**
 * Server-side TLS handshake prober for [GalControlSession].
 *
 * Owns the buffered message-3 inbound and runs the engine until it needs
 * more from the head unit, emitting one clear message 3 per wrapped record.
 * [onFinished] fires when the engine settles (the head unit confirms with
 * AuthComplete); [onFailed] records the session failure and discards any
 * partial flight, matching the old inline pump's contract.
 */
private class HandshakePump(
    private val engine: SSLEngine,
    private val onFinished: () -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private var tlsInbound: ByteBuffer = ByteBuffer.allocate(0)

    fun appendInbound(payload: ByteArray) {
        val combined = ByteBuffer.allocate(tlsInbound.remaining() + payload.size)
        combined.put(tlsInbound)
        combined.put(payload)
        combined.flip()
        tlsInbound = combined
    }

    /**
     * Runs the engine until it needs more from the head unit, emitting a message 3 for each
     * record it produces.
     */
    fun pump(): List<OutboundMessage> {
        val outgoing = mutableListOf<OutboundMessage>()
        val empty = ByteBuffer.allocate(0)

        while (true) {
            when (pumpStep(empty, outgoing)) {
                null -> return outgoing
                true -> Unit // The engine advanced; keep pumping.
                // The step already recorded the failure via [onFailed], whose
                // contract is an empty reply list: never send a partial flight.
                false -> return emptyList()
            }
        }
    }

    /**
     * Runs one engine step: delegated tasks inline, unwraps against buffered
     * inbound, wraps produce message-3 replies, FINISHED advances the state.
     * Null stops the pump (needs more inbound or finished), true keeps
     * pumping, false means the step failed the session.
     */
    private fun pumpStep(
        empty: ByteBuffer,
        outgoing: MutableList<OutboundMessage>,
    ): Boolean? {
        when (engine.handshakeStatus) {
            HandshakeStatus.NEED_TASK -> {
                // A single getter call: run the task the engine handed us, if
                // any. A ran task may have advanced the engine, so keep
                // pumping. Fetching twice (check-then-run) drops handshake
                // work when the getter hands out a fresh task per call and
                // wedges the engine at NEED_TASK with no error.
                val task = engine.delegatedTask ?: return null
                task.run()
                return true
            }
            HandshakeStatus.NEED_UNWRAP -> return pumpUnwrap()
            HandshakeStatus.NEED_WRAP -> return pumpWrap(empty, outgoing)
            HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> {
                // The head unit confirms with AuthComplete; we do not send it.
                onFinished()
                return null
            }
            else -> return null
        }
    }

    /**
     * Unwraps one buffered inbound record. Null (stop pumping) when no bytes
     * are buffered yet or the record is partial; true (keep pumping) on a
     * clean unwrap; false when the engine reports failure.
     */
    private fun pumpUnwrap(): Boolean? {
        if (!tlsInbound.hasRemaining()) return null
        val plain = ByteBuffer.allocate(engine.session.applicationBufferSize)
        val status = engine.unwrap(tlsInbound, plain).status
        return when (status) {
            // Partial record: wait for the next message 3.
            Status.BUFFER_UNDERFLOW -> null
            Status.OK -> true
            else -> {
                onFailed("TLS unwrap failed: $status")
                false
            }
        }
    }

    /**
     * Wraps one outbound handshake record into a clear message 3.
     * True (keep pumping) on success, false when the engine reports failure.
     */
    private fun pumpWrap(
        empty: ByteBuffer,
        outgoing: MutableList<OutboundMessage>,
    ): Boolean {
        val record = ByteBuffer.allocate(engine.session.packetBufferSize)
        val result = engine.wrap(empty, record)
        if (result.status != Status.OK) {
            onFailed("TLS wrap failed: ${result.status}")
            return false
        }
        record.flip()
        val bytes = ByteArray(record.remaining())
        record.get(bytes)
        // Handshake records ride in the clear: they ARE the encryption being
        // set up, so the ENCRYPTED frame flag must stay off.
        outgoing += OutboundMessage(
            GalMessage.Control.SSL_HANDSHAKE,
            bytes,
            encrypted = false,
        )
        return true
    }
}

/**
 * Composes the `ServiceDiscoveryRequest` device label exactly as gearhead does.
 *
 * `jog.run()` reads `Build.MANUFACTURER` and `Build.MODEL` and, unless MODEL
 * already starts with MANUFACTURER, concatenates `MANUFACTURER + " " + MODEL`
 * (`a.bJ(model, manufacturer, " ")` returns `str2 + str3 + str`).
 */
internal fun composeDiscoveryLabel(model: String, manufacturer: String): String =
    if (model.startsWith(manufacturer)) model else "$manufacturer $model"
