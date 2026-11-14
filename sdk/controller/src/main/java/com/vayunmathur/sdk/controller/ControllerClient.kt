package com.vayunmathur.sdk.controller

import com.vayunmathur.library.log.Log
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "ControllerClient"

/** JVM Base64, so the pairing path round-trips in a plain JVM test. */
private object ClientBase64 {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
}

/** Where a phone stands in the join flow. */
enum class ClientState {
    IDLE,
    CONNECTING,
    /** Waiting for the user to type the six digits the TV is showing. */
    AWAITING_CODE,
    PAIRED,
    JOINED,
    FAILED,
}

/**
 * The phone side of the controller protocol: connect, pair, join, and emit input at
 * [ControllerContract.INPUT_HZ].
 *
 * The secret is generated here and sealed to the TV's bundle with the caller's [sealToBundle];
 * the SDK never touches PQC itself, so the native library stays in `:library:e2ee-p2p`. Device
 * keys persist through [loadDeviceKey]/[saveDeviceKey] keyed by host id.
 */
class ControllerClient(
    private val senderId: String,
    private val senderName: String,
    private val sealToBundle: (bundle: ByteArray, secret: ByteArray) -> ByteArray?,
    private val loadDeviceKey: (hostId: String) -> ByteArray?,
    private val saveDeviceKey: (hostId: String, key: ByteArray) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ClientState.IDLE)
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private val _lobby = MutableStateFlow(LobbyState())
    /** The latest lobby snapshot the TV broadcast. Replaces wholesale. */
    val lobby: StateFlow<LobbyState> = _lobby.asStateFlow()

    private var socket: ControllerSocket? = null
    private var seq = 0L

    /**
     * Connect to [host] and run the handshake. Returns the state the attempt ended in: PAIRED
     * when the code path is needed (the caller then calls [proveCode]), JOINED for a remembered
     * device, FAILED otherwise.
     */
    fun connect(host: ControllerHostInfo) {
        scope.launch { runHandshake(host) }
    }

    /** Prove the six digits on the TV screen. Only valid in [ClientState.AWAITING_CODE]. */
    fun proveCode(code: String) {
        scope.launch { runCodeProof(code) }
    }

    /** Take a seat. Only valid once paired. */
    fun join(displayName: String) {
        scope.launch {
            val socket = socket ?: return@launch
            socket.send(ControllerJoin(displayName))
            val joined = socket.receive()
            // The TV answers a JOIN by broadcasting the lobby; anything else is a refusal.
            if (joined?.message is ControllerLobby) {
                _state.value = ClientState.JOINED
                runLoop(socket)
            } else {
                _state.value = ClientState.FAILED
                close()
            }
        }
    }

    /** One input sample, with the next sequence number. Drops when not joined. */
    fun sendInput(
        buttons: Long = 0L,
        axes: List<Float> = List(AXIS_COUNT) { 0f },
        hatX: Int = 0,
        hatY: Int = 0,
        touchX: Float? = null,
        touchY: Float? = null,
    ) {
        if (_state.value != ClientState.JOINED) return
        val socket = socket ?: return
        val input = ControllerInput(
            playerId = senderId,
            seq = ++seq,
            buttons = buttons,
            axes = axes,
            hatX = hatX,
            hatY = hatY,
            touchX = touchX,
            touchY = touchY,
            clientTimestampMs = android.os.SystemClock.uptimeMillis(),
        )
        scope.launch { socket.send(ControllerInputFrame(input)) }
    }

    fun leave() {
        scope.launch {
            socket?.send(ControllerLeave())
            close()
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        _state.value = ClientState.IDLE
        scope.cancel()
    }

    private var pendingKeys: ControllerKeys? = null
    private var pendingTranscript: ControllerTranscript? = null
    private var pendingHostId: String? = null
    private var pendingWantsCode = false

    private fun runHandshake(host: ControllerHostInfo) {
        _state.value = ClientState.CONNECTING
        val socket = ControllerSocket()
        this.socket = socket
        try {
            socket.connect(host.host, host.port)
        } catch (e: java.io.IOException) {
            Log.status(TAG, "connect to ${host.friendlyName} failed", e)
            _state.value = ClientState.FAILED
            return
        }
        val transcript = ControllerTranscript()
        pendingTranscript = transcript
        pendingHostId = host.id
        val remembered = loadDeviceKey(host.id)
        val keys = negotiateSecret(socket, transcript, remembered) ?: return
        pendingKeys = keys
        if (!pendingWantsCode && remembered != null) {
            proveRemembered(socket, keys, transcript, remembered)
        } else {
            _state.value = ClientState.AWAITING_CODE
        }
    }

    /**
     * HELLO → HOST_HELLO → SEALED_SECRET → PAIR_REQUIRED. Returns the session keys
     * after recording [pendingWantsCode], or null after failing the attempt.
     */
    private fun negotiateSecret(
        socket: ControllerSocket,
        transcript: ControllerTranscript,
        remembered: ByteArray?,
    ): ControllerKeys? {
        val hostHello = exchangeHello(socket, transcript, remembered) ?: return null
        return sealSecret(socket, transcript, hostHello)
    }

    /** HELLO → HOST_HELLO with the protocol-version check. */
    private fun exchangeHello(
        socket: ControllerSocket,
        transcript: ControllerTranscript,
        remembered: ByteArray?,
    ): ControllerHostHello? {
        val helloBody = socket.send(
            ControllerHello(
                senderName = senderName,
                senderId = senderId,
                paired = remembered != null,
            ),
        ) ?: run { failed(socket); return null }
        transcript.add(helloBody)
        val hostHello = socket.receive() ?: run { failed(socket); return null }
        transcript.add(hostHello.body)
        val hostHelloMsg = hostHello.message as? ControllerHostHello
            ?: run { failed(socket); return null }
        if (hostHelloMsg.version != ControllerContract.CONTROLLER_PROTOCOL_VERSION) {
            Log.status(TAG, "refusing version ${hostHelloMsg.version} from ${hostHelloMsg.hostName}")
            failed(socket)
            return null
        }
        return hostHelloMsg
    }

    /** Seals a fresh secret to the host bundle and reads PAIR_REQUIRED. */
    private fun sealSecret(
        socket: ControllerSocket,
        transcript: ControllerTranscript,
        hostHelloMsg: ControllerHostHello,
    ): ControllerKeys? {
        val bundle = ClientBase64.decode(hostHelloMsg.publicBundle)
            ?: run { failed(socket); return null }
        val secret = ControllerKeys.newSecret()
        val sealed = sealToBundle(bundle, secret) ?: run { failed(socket); return null }
        val keys = ControllerKeys.of(secret)
        val sealedBody = socket.send(ControllerSealedSecret(ClientBase64.encode(sealed)))
            ?: run { failed(socket); return null }
        transcript.add(sealedBody)
        socket.codec.useSessionKey(keys.control)
        val required = socket.receive() ?: run { failed(socket); return null }
        val requiredMsg = required.message as? ControllerPairRequired
            ?: run { failed(socket); return null }
        pendingWantsCode = requiredMsg.code
        return keys
    }

    /** Proves possession of the remembered device key; no code is shown on the TV. */
    private fun proveRemembered(
        socket: ControllerSocket,
        keys: ControllerKeys,
        transcript: ControllerTranscript,
        remembered: ByteArray,
    ) {
        val proof = keys.deviceProof(remembered, transcript.value())
        socket.send(ControllerPairProof(ClientBase64.encode(proof))) ?: return failed(socket)
        val ok = socket.receive() ?: return failed(socket)
        if (ok.message !is ControllerPairOk) return failed(socket)
        _state.value = ClientState.PAIRED
    }

    private fun runCodeProof(code: String) {
        if (_state.value != ClientState.AWAITING_CODE) return
        if (!ControllerPairCode.isWellFormed(code)) return
        val (socket, keys, transcript) = pendingSession() ?: return failed()
        val proof = keys.pairProof(code, transcript.value())
        socket.send(ControllerPairProof(ClientBase64.encode(proof))) ?: return failed()
        val ok = socket.receive() ?: return failed()
        val okMsg = ok.message as? ControllerPairOk ?: run {
            _state.value = ClientState.AWAITING_CODE
            return
        }
        saveProvedDeviceKey(okMsg)
        _state.value = ClientState.PAIRED
    }

    /** The in-flight pairing pieces, or null when any of them is gone. */
    private fun pendingSession(): Triple<ControllerSocket, ControllerKeys, ControllerTranscript>? {
        val s = socket ?: return null
        val k = pendingKeys ?: return null
        val tr = pendingTranscript ?: return null
        return Triple(s, k, tr)
    }

    /** Persists the fresh device key from a successful code pairing, when one was issued. */
    private fun saveProvedDeviceKey(okMsg: ControllerPairOk) {
        okMsg.deviceKey?.let { encoded ->
            ClientBase64.decode(encoded)?.let { key ->
                pendingHostId?.let { saveDeviceKey(it, key) }
            }
        }
    }

    private fun runLoop(socket: ControllerSocket) {
        scope.launch {
            launch {
                // The heartbeat mirror: PING while joined, so a quiet lobby still produces traffic
                // inside the host's 60 s read bound. State-guarded, so it ends with the session
                // rather than pinging a dead socket.
                while (_state.value == ClientState.JOINED) {
                    kotlinx.coroutines.delay(ControllerContract.PING_INTERVAL_MS)
                    if (_state.value == ClientState.JOINED) socket.send(ControllerPing)
                }
            }
            var open = true
            while (open) {
                val received = socket.receive() ?: break
                when (val msg = received.message) {
                    is ControllerLobby -> _lobby.value = msg.lobby
                    is ControllerRumble -> onRumble(msg.rumble)
                    is ControllerPing -> socket.send(ControllerPing)
                    is ControllerBye -> open = false
                    else -> Log.status(TAG, "unexpected ${msg::class.simpleName} from host")
                }
            }
            _state.value = ClientState.IDLE
        }
    }

    /** Renders [RumbleFeedback] on the vibrator. Set by the hosting app; the default logs. */
    var onRumble: (RumbleFeedback) -> Unit = { rumble ->
        Log.status(TAG, "rumble ${rumble.durationMs}ms")
    }

    private fun failed(socket: ControllerSocket? = this.socket) {
        runCatching { socket?.close() }
        _state.value = ClientState.FAILED
    }
}
