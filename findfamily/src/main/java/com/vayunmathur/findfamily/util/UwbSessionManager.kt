@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package com.vayunmathur.findfamily.util

import kotlin.uuid.Uuid
import kotlin.concurrent.atomics.AtomicBoolean
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.vayunmathur.library.log.Log
import androidx.annotation.RequiresApi
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.tracker.TrackerStore
import com.vayunmathur.findfamily.tracker.TrackerUwbGatt
import com.vayunmathur.findfamily.uwb.RangingSample
import com.vayunmathur.findfamily.uwb.UwbAccessoryProtocol
import com.vayunmathur.findfamily.uwb.UwbBytes
import com.vayunmathur.findfamily.uwb.UwbCancelReason
import com.vayunmathur.findfamily.uwb.UwbController
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.findfamily.uwb.UwbEnvelopeKind
import com.vayunmathur.findfamily.uwb.UwbHandshake
import com.vayunmathur.findfamily.uwb.UwbInbox
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-global UWB ranging session owner.
 *
 * Built on the public `android.ranging.RangingManager` API (Android 16+).
 * This is the only on-device UWB API that works on GrapheneOS — the legacy
 * `androidx.core.uwb` only ships a GMS-mediated backend that sandboxed Play
 * Services can't fulfil. Accordingly the entire UWB feature requires API 36.
 *
 * Hoisting the session out of the (activity-scoped) ViewModel into a
 * service-scoped singleton lets the existing foreground `LocationTrackingService`
 * auto-accept incoming Find Nearby (UWB) requests from peers — i.e. range in
 * the background without requiring the user to tap a notification first.
 * This works because the session is owned by the foreground service rather
 * than an Activity, so Android keeps the process eligible for ranging
 * callbacks.
 */
object UwbSessionManager {

    /** UI-facing state machine for the Find Nearby (UWB) screen. */
    sealed interface UwbSessionState {
        data object Idle : UwbSessionState
        data object Starting : UwbSessionState
        data object WaitingForPeer : UwbSessionState
        data class Ranging(val sample: RangingSample) : UwbSessionState
        data object PeerDisconnected : UwbSessionState
        data class Unsupported(val reason: String? = null) : UwbSessionState
        /**
         * The peer's phone declined the REQUEST because it can't do Find Nearby at all
         * (CANCEL with [UwbCancelReason.UNSUPPORTED], #869). Terminal until the screen closes.
         */
        data object PeerUnsupported : UwbSessionState
        data class Failed(val reason: String) : UwbSessionState
    }

    private val _state: MutableStateFlow<UwbSessionState> = MutableStateFlow(UwbSessionState.Idle)
    val state: StateFlow<UwbSessionState> = _state.asStateFlow()

    /** The peer userid currently being ranged with (or attempted), if any. */
    private val _peerUserId: MutableStateFlow<Long?> = MutableStateFlow(null)
    val peerUserId: StateFlow<Long?> = _peerUserId.asStateFlow()

    /**
     * True iff this device's API level supports the AOSP ranging API. The
     * whole `android.ranging` package landed in Android 16 / API 36; on
     * earlier releases the classes simply aren't on the boot classpath.
     *
     * Kept as a bare `SDK_INT` comparison so lint recognises it as a version
     * check and can validate the `@RequiresApi(BAKLAVA)` internals it guards.
     * Use [isAvailable] for the UI-facing gate.
     */
    val isSupportedSdk: Boolean get() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    /**
     * The single availability gate for the Find Nearby (UWB) feature: the
     * ranging API must exist *and* the device must actually have a UWB radio.
     * Entry points in the UI should hide themselves when this is false.
     */
    fun isAvailable(context: Context): Boolean =
        isSupportedSdk &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)

    private const val TAG = "UwbSessionManager"
    private const val TIMEOUT_MS = 60_000L

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private lateinit var repository: FindFamilyRepository

    private var controller: UwbController? = null
    private var streamJob: Job? = null
    private var waitJob: Job? = null
    private var currentSessionId: String? = null

    /**
     * True while the current session is a find against a custom tracker. Trackers aren't
     * on the WebSocket, so the envelope handshake (REQUEST/ACK/CANCEL) is skipped entirely
     * for them; params go over GATT instead (see [TrackerUwbGatt]).
     */
    private var currentPeerIsTracker: Boolean = false

    /**
     * Wire up the manager. No-op on devices below API 36. Safe to call
     * multiple times — only the first call has effect.
     */
    fun init(context: Context, repository: FindFamilyRepository) {
        if (!isSupportedSdk) { Log.status(TAG, "init: SDK ${Build.VERSION.SDK_INT} < 36, UWB disabled"); return }
        if (!initialized.compareAndSet(false, true)) { Log.status(TAG, "init: already initialized"); return }
        Log.status(TAG, "init: hooking up UwbInbox subscriber")
        appContext = context.applicationContext
        UwbSessionManager.repository = repository

        // Subscribe to the global UWB envelope inbox for the whole process
        // lifetime. CANCELs from the peer tear down our local session
        // whether we're idle / waiting / ranging; REQUESTs trigger the
        // responder (auto-accept) path when we're idle.
        scope.launch {
            UwbInbox.flow.collect { env ->
                when (env.kind) {
                    UwbEnvelopeKind.REQUEST -> {
                        if (_state.value is UwbSessionState.Idle) {
                            acceptIncoming(env)
                        }
                        // If already busy, drop — UWB is one-session-at-a-time.
                    }
                    UwbEnvelopeKind.CANCEL -> endOnPeerCancel(env)
                    // ACK and CONFIG envelopes are consumed by the waiting
                    // initiator/responder coroutines via UwbInbox.flow.first {...}.
                    else -> {}
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Initiator path (user tapped "Find with Precision" on the screen)
    // -----------------------------------------------------------------

    fun startAsInitiator(peerUserId: Long) {
        Log.status(
            TAG,
            "startAsInitiator(peer=$peerUserId) entered. " +
                "isSupportedSdk=$isSupportedSdk initialized=${initialized.load()} state=${_state.value}"
        )
        if (!isSupportedSdk) {
            // UI renders R.string.uwb_status_unsupported for a null reason.
            _state.value = UwbSessionState.Unsupported()
            return
        }
        if (!initialized.load()) {
            Log.status(TAG, "startAsInitiator: NOT INITIALIZED — service hasn't called init() yet")
            return
        }
        if (_state.value !is UwbSessionState.Idle) {
            Log.status(TAG, "startAsInitiator: not idle, skipping")
            return
        }
        _state.value = UwbSessionState.Starting
        val sessionId = Uuid.random().toString()
        currentSessionId = sessionId
        _peerUserId.value = peerUserId

        scope.launch {
            runInitiatorSession(this, peerUserId, sessionId)
        }
    }

    /** Body of the initiator coroutine: resolve the peer, open the controller, publish REQUEST. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun runInitiatorSession(
        outerScope: CoroutineScope,
        peerUserId: Long,
        sessionId: String,
    ) {
        Log.status(TAG, "startAsInitiator: launched coroutine, loading peer from DB")
        val peerUser = repository.getUser(peerUserId)
        Log.status(TAG, "startAsInitiator: peerUser=${peerUser?.name} platform=${peerUser?.platform}")
        if (peerUser == null) {
            _state.value = UwbSessionState.Failed("Unknown peer")
            stopLocal()
            return
        }
        if (peerUser.kind == UserKind.TRACKER) {
            beginTrackerFind(peerUserId)
            return
        }
        if (peerUser.platform == "ios") {
            beginCrossPlatformInitiateToIos(peerUserId, sessionId)
            return
        }

        val ctrl = UwbController(appContext)
        controller = ctrl
        Log.status(TAG, "startAsInitiator: opening controller")
        val info = ctrl.openController().getOrElse {
            Log.error(TAG, "openController failed", it)
            _state.value = UwbSessionState.Unsupported(it.message ?: "UWB not available on this device")
            return
        }
        Log.status(
            TAG,
            "startAsInitiator: controller opened " +
                "(addr=${info.localAddress.joinToString(":") { "%02x".format(it) }} " +
                "ch=${info.channelNumber} pi=${info.preambleIndex}); publishing REQUEST"
        )

        val envelope = UwbEnvelope(
            sessionId = sessionId,
            sender = Networking.userid.toULong(),
            senderPlatform = "android",
            kind = UwbEnvelopeKind.REQUEST,
            payload = UwbHandshake(
                addressB64 = UwbBytes.b64(info.localAddress),
                channelNumber = info.channelNumber,
                preambleIndex = info.preambleIndex,
                sessionKeyB64 = UwbBytes.b64(info.sessionKey),
                sessionId = info.sessionId
            )
        )
        val ok = publish(envelope, peerUserId)
        Log.status(TAG, "startAsInitiator: publishUwbMessage returned $ok")
        if (!ok) {
            _state.value = UwbSessionState.Failed("Could not reach peer")
            stopLocal()
            return
        }
        _state.value = UwbSessionState.WaitingForPeer

        waitJob = outerScope.launch {
            awaitAckAndRange(ctrl, info, sessionId)
        }
    }

    /** Park waiting for the peer's ACK, then start the ranging stream. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun awaitAckAndRange(ctrl: UwbController, info: UwbController.ControllerInfo, sessionId: String) {
        Log.status(TAG, "startAsInitiator: parked waiting for ACK on sessionId=$sessionId")
        val ack = waitForEnvelope(sessionId, TIMEOUT_MS) { env ->
            env.kind == UwbEnvelopeKind.ACK || env.kind == UwbEnvelopeKind.CANCEL
        }
        if (ack == null) {
            _state.value = UwbSessionState.Failed("Peer did not respond")
            stopLocal()
            return
        }
        if (ack.kind == UwbEnvelopeKind.CANCEL) {
            endOnPeerCancel(ack)
            return
        }
        val peerAddress = ack.payload?.addressB64?.let(UwbBytes::from)
        if (peerAddress == null) {
            _state.value = UwbSessionState.Failed("Invalid peer ack")
            stopLocal()
            return
        }
        startRangingStream(
            ctrl,
            role = UwbController.Role.Initiator,
            localAddress = info.localAddress,
            peerAddress = peerAddress,
            sessionId = info.sessionId,
            sessionKey = info.sessionKey,
            channelNumber = info.channelNumber,
            preambleIndex = info.preambleIndex
        )
    }

    /**
     * Cross-platform initiator: Android user tapped on an iOS peer. We open
     * controlee, ship accessoryData in the REQUEST, and park waiting for
     * iOS's shareableConfigurationData reply.
     *
     * Broad Throwable catch below is deliberate: the shareable-config parse is
     * still a stub throwing NotImplementedError (an Error, not an Exception),
     * and the base64 decode can throw IllegalArgumentException; either must
     * fail the session, not the scope.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    @Suppress("TooGenericExceptionCaught")
    private suspend fun beginCrossPlatformInitiateToIos(peerUserId: Long, sessionId: String) {
        val ctrl = UwbController(appContext)
        controller = ctrl
        val localAddress = ctrl.openControlee().getOrElse {
            _state.value = UwbSessionState.Unsupported(it.message ?: "UWB not available on this device")
            return
        }
        val accessoryData = UwbAccessoryProtocol.encodeAccessoryConfigurationData(localAddress)
        val envelope = UwbEnvelope(
            sessionId = sessionId,
            sender = Networking.userid.toULong(),
            senderPlatform = "android",
            kind = UwbEnvelopeKind.REQUEST,
            payload = UwbHandshake(accessoryConfigDataB64 = UwbBytes.b64(accessoryData))
        )
        val ok = publish(envelope, peerUserId)
        if (!ok) {
            _state.value = UwbSessionState.Failed("Could not reach peer")
            stopLocal()
            return
        }
        _state.value = UwbSessionState.WaitingForPeer
        waitJob = scope.launch {
            val shareable = waitForEnvelope(sessionId, TIMEOUT_MS) { env ->
                env.kind == UwbEnvelopeKind.CONFIG && env.payload?.shareableConfigDataB64 != null
            }
            if (shareable == null) {
                _state.value = UwbSessionState.Failed("iOS peer did not return ranging config")
                stopLocal()
                return@launch
            }
            val parsed = try {
                UwbAccessoryProtocol.parseShareableConfigurationData(
                    UwbBytes.from(shareable.payload!!.shareableConfigDataB64!!)
                )
            } catch (e: Throwable) {
                _state.value = UwbSessionState.Failed(e.message ?: "Could not parse iOS config")
                stopLocal()
                return@launch
            }
            startRangingStream(
                ctrl,
                role = UwbController.Role.Responder,
                localAddress = localAddress,
                peerAddress = parsed.peerAddress,
                sessionId = parsed.sessionId,
                sessionKey = parsed.sessionKey,
                channelNumber = parsed.channelNumber,
                preambleIndex = parsed.preambleIndex
            )
        }
    }

    /**
     * Tracker find: the peer is a bound UWB tracker, not a phone. There is no envelope
     * handshake — the tracker isn't on the WebSocket at all — so we hand it the session
     * params over BLE GATT and range straight away. The STS key and the tracker's UWB
     * address come from the bind-time beacon secret on both ends.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun beginTrackerFind(trackerUserId: Long) {
        currentPeerIsTracker = true
        val store = TrackerStore(DataStoreUtils.getInstance(appContext))
        val secret = store.secret(trackerUserId)
        val bleAddress = store.bleAddress(trackerUserId)
        if (secret == null || bleAddress == null) {
            Log.status(TAG, "beginTrackerFind: tracker $trackerUserId has no stored secret/address")
            _state.value = UwbSessionState.Failed("This tracker isn't bound on this device")
            stopLocal()
            return
        }
        val ctrl = UwbController(appContext)
        controller = ctrl
        _state.value = UwbSessionState.WaitingForPeer
        val stream = TrackerUwbGatt.startRanging(appContext, ctrl, bleAddress, secret)
        if (stream == null) {
            _state.value = UwbSessionState.Failed("Could not reach tracker")
            stopLocal()
            return
        }
        collectRangingStream(stream)
    }

    // -----------------------------------------------------------------
    // Responder path (REQUEST envelope arrived — auto-accept in background)
    // -----------------------------------------------------------------

    private fun acceptIncoming(request: UwbEnvelope) {
        if (!isSupportedSdk) return
        currentSessionId = request.sessionId
        _peerUserId.value = request.sender.toLong()
        _state.value = UwbSessionState.Starting

        scope.launch {
            // Cross-platform: iOS sender (no FiRa payload, only accessoryData).
            if (request.senderPlatform == "ios" || request.payload?.addressB64 == null) {
                beginCrossPlatformAsAccessory(request)
                return@launch
            }
            // After the early return above, payload + addressB64 are both non-null.
            val payload = request.payload
            val peerAddress = UwbBytes.from(payload.addressB64)
            val sessionId = payload.sessionId ?: return@launch
            val sessionKey = payload.sessionKeyB64?.let(UwbBytes::from) ?: return@launch
            val channel = payload.channelNumber ?: return@launch
            val preamble = payload.preambleIndex ?: return@launch

            val ctrl = UwbController(appContext)
            controller = ctrl
            val localAddress = ctrl.openControlee().getOrElse {
                _state.value = UwbSessionState.Unsupported(it.message ?: "UWB not available on this device")
                stopLocal()
                return@launch
            }
            val ack = UwbEnvelope(
                sessionId = request.sessionId,
                sender = Networking.userid.toULong(),
                senderPlatform = "android",
                kind = UwbEnvelopeKind.ACK,
                payload = UwbHandshake(addressB64 = UwbBytes.b64(localAddress))
            )
            val ok = publish(ack, request.sender.toLong())
            if (!ok) {
                _state.value = UwbSessionState.Failed("Could not reach peer")
                stopLocal()
                return@launch
            }
            startRangingStream(
                ctrl,
                role = UwbController.Role.Responder,
                localAddress = localAddress,
                peerAddress = peerAddress,
                sessionId = sessionId,
                sessionKey = sessionKey,
                channelNumber = channel,
                preambleIndex = preamble
            )
        }
    }

    // Broad Throwable catch inside is deliberate, same as [beginCrossPlatformInitiateToIos]:
    // the shareable-config parse stub throws NotImplementedError (an Error).
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    @Suppress("TooGenericExceptionCaught")
    private suspend fun beginCrossPlatformAsAccessory(request: UwbEnvelope) {
        val ctrl = UwbController(appContext)
        controller = ctrl
        val localAddress = ctrl.openControlee().getOrElse {
            _state.value = UwbSessionState.Unsupported(it.message ?: "UWB not available on this device")
            stopLocal()
            return
        }
        val accessoryData = UwbAccessoryProtocol.encodeAccessoryConfigurationData(localAddress)
        val configEnvelope = UwbEnvelope(
            sessionId = request.sessionId,
            sender = Networking.userid.toULong(),
            senderPlatform = "android",
            kind = UwbEnvelopeKind.CONFIG,
            payload = UwbHandshake(accessoryConfigDataB64 = UwbBytes.b64(accessoryData))
        )
        val ok = publish(configEnvelope, request.sender.toLong())
        if (!ok) {
            _state.value = UwbSessionState.Failed("Could not reach peer")
            stopLocal()
            return
        }
        _state.value = UwbSessionState.WaitingForPeer
        val shareable = waitForEnvelope(request.sessionId, TIMEOUT_MS) { env ->
            env.kind == UwbEnvelopeKind.CONFIG && env.payload?.shareableConfigDataB64 != null
        }
        if (shareable == null) {
            _state.value = UwbSessionState.Failed("iOS peer did not return ranging config")
            stopLocal()
            return
        }
        val parsed = try {
            UwbAccessoryProtocol.parseShareableConfigurationData(
                UwbBytes.from(shareable.payload!!.shareableConfigDataB64!!)
            )
        } catch (e: Throwable) {
            Log.error(TAG, "Failed to parse shareable config data", e)
            _state.value = UwbSessionState.Failed(e.message ?: "Could not parse iOS config")
            stopLocal()
            return
        }
        startRangingStream(
            ctrl,
            role = UwbController.Role.Responder,
            localAddress = localAddress,
            peerAddress = parsed.peerAddress,
            sessionId = parsed.sessionId,
            sessionKey = parsed.sessionKey,
            channelNumber = parsed.channelNumber,
            preambleIndex = parsed.preambleIndex
        )
    }

    // -----------------------------------------------------------------
    // Common: drive the RangingResult flow into the state machine
    // -----------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun startRangingStream(
        ctrl: UwbController,
        role: UwbController.Role,
        localAddress: ByteArray,
        peerAddress: ByteArray,
        sessionId: Int,
        sessionKey: ByteArray,
        channelNumber: Int,
        preambleIndex: Int,
    ) {
        collectRangingStream(
            ctrl.stream(
                role = role,
                localAddress = localAddress,
                peerAddress = peerAddress,
                sessionId = sessionId,
                sessionKey = sessionKey,
                channelNumber = channelNumber,
                preambleIndex = preambleIndex,
            )
        )
    }

    /** Drive an already-opened ranging [stream] into the UI state machine. */
    // Broad catch is deliberate: the UWB radio stack surfaces undocumented
    // runtime exceptions; a dying stream must fail the session, not the scope.
    @Suppress("TooGenericExceptionCaught")
    private fun collectRangingStream(stream: Flow<RangingSample>) {
        streamJob?.cancel()
        streamJob = scope.launch {
            try {
                stream.collect { sample ->
                    _state.value = if (sample.peerDisconnected) {
                        UwbSessionState.PeerDisconnected
                    } else {
                        UwbSessionState.Ranging(sample)
                    }
                }
            } catch (e: Exception) {
                Log.error(TAG, "Exception in startRangingStream", e)
                _state.value = UwbSessionState.Failed("Ranging stream failed: ${e.message}")
                stopLocal()
            }
        }
    }

    /**
     * User-initiated stop. Sends a CANCEL envelope to the peer so its UI can
     * exit too, then tears down the local UWB scope. Trackers get no CANCEL —
     * they have no server-side identity to route one to.
     */
    fun stop() {
        val sessionId = currentSessionId
        val peerId = _peerUserId.value
        if (!currentPeerIsTracker && sessionId != null && peerId != null) {
            scope.launch {
                publish(
                    UwbEnvelope(
                        sessionId = sessionId,
                        sender = Networking.userid.toULong(),
                        senderPlatform = "android",
                        kind = UwbEnvelopeKind.CANCEL
                    ),
                    peerId
                )
            }
        }
        stopLocal()
    }

    /**
     * Look up the peer (best-effort) and publish a UWB envelope to it,
     * swallowing and logging any failure. Returns true iff the server
     * accepted the message.
     *
     * Broad catches are deliberate: the lookup crosses Room and the publish
     * crosses the relay socket; either may throw undocumented runtime
     * exceptions, and a failed handshake publish must return false, not crash.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun publish(envelope: UwbEnvelope, peerId: Long): Boolean {
        val peer = try {
            repository.getUser(peerId)
        } catch (e: Exception) {
            Log.error(TAG, "Failed to lookup peer user", e)
            null
        }
        return try {
            Networking.publishUwbMessage(envelope, peerId, peer)
        } catch (e: Exception) {
            Log.error(TAG, "publishUwbMessage threw exception", e)
            false
        }
    }

    /**
     * The state a requester lands in when the peer answers its REQUEST with [cancel].
     * Only [UwbCancelReason.UNSUPPORTED] gets its own state; a plain cancel, an unknown
     * reason from a newer peer, or no reason from an older one all mean "peer stopped".
     */
    internal fun stateAfterPeerCancel(cancel: UwbEnvelope): UwbSessionState =
        if (cancel.reason == UwbCancelReason.UNSUPPORTED) {
            UwbSessionState.PeerUnsupported
        } else {
            UwbSessionState.Idle
        }

    /**
     * Tear down the session [cancel] belongs to and publish [stateAfterPeerCancel].
     *
     * Both the inbox collector in [init] and the initiator's ACK wait see the same CANCEL
     * and race to handle it, so this is synchronized and keyed on [currentSessionId]: the
     * first caller wins, the second finds the session already gone. A terminal state keeps
     * the peer id so the screen's dispose still matches it and calls [stop], which resets
     * the manager to Idle for the next find or incoming request.
     */
    @Synchronized
    private fun endOnPeerCancel(cancel: UwbEnvelope) {
        if (cancel.sessionId != currentSessionId) return
        val peer = _peerUserId.value
        stopLocal()
        val next = stateAfterPeerCancel(cancel)
        if (next != UwbSessionState.Idle) {
            Log.status(TAG, "peer declined session ${cancel.sessionId}: reason=${cancel.reason}")
            _peerUserId.value = peer
            _state.value = next
        }
    }

    private fun stopLocal() {
        streamJob?.cancel(); streamJob = null
        waitJob?.cancel(); waitJob = null
        // `controller` is only ever assigned behind an isSupportedSdk gate.
        if (isSupportedSdk) controller?.stop()
        controller = null
        currentSessionId = null
        currentPeerIsTracker = false
        _peerUserId.value = null
        _state.value = UwbSessionState.Idle
    }

    private suspend fun waitForEnvelope(
        sessionId: String,
        timeoutMs: Long,
        predicate: (UwbEnvelope) -> Boolean,
    ): UwbEnvelope? = withTimeoutOrNull(timeoutMs) {
        UwbInbox.flow.first { it.sessionId == sessionId && predicate(it) }
    }
}
