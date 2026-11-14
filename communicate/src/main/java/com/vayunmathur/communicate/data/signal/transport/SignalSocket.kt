package com.vayunmathur.communicate.data.signal.transport

import android.content.Context
import android.util.Base64
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.network.webSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketResponseMessage
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent WebSocket to Signal's chat server for the primary client.
 *
 * Real Signal transport (grounded in C:\Users\Vayun\signal-ref):
 *  - Host/path: wss://grpc.chat.signal.org:443/v1/websocket/  (libsignal/rust/net/src/env.rs:52,924)
 *  - Auth: header `Authorization: Basic base64("{aci}.{deviceId}:{password}")`
 *    (libsignal/rust/net/src/auth.rs:25, chat.rs:206) — NOT ?login= query.
 *  - Framing: binary protobuf WebSocketMessage (rust/net/src/proto/chat_websocket.proto)
 *    with uint64 id, verb/path/body/headers.
 *  - Keepalive: PUT /v1/keepalive WebSocketRequestMessage (SignalWebSocket.sendKeepAlive)
 *  - Close codes: 4401 invalid auth, 4409 connected elsewhere (env.rs:39-40)
 *  - Validate x-signal-timestamp to defeat captive portals (env.rs:57,947)
 *
 * Signal keeps **two** of these. The authenticated one carries the inbound message queue and
 * identified sends; the [authenticated]`= false` one carries sealed-sender sends, which must not go
 * over the authenticated socket or the server learns the sender identity that sealed sender exists to
 * hide. The unauthenticated socket sends no `Authorization` header.
 *
 * Uses :library:network WebSocketClient (binary frames).
 */
class SignalSocket(
    private val context: Context,
    private val authData: SignalAuthData,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val useTls: Boolean = true,
    private val passwordOverride: String? = null,
    private val authenticated: Boolean = true,
) {
    /** Server endpoint and logging constants for [SignalSocket]. */
    companion object {
        private const val TAG = "SignalSocket"
        private const val ACI_PREFIX_LENGTH = 8

        /**
         * The HTTP/1.1 chat host. Note libsignal's `env.rs` names `grpc.chat.signal.org`, but that host is
         * configured `http_version: Http2` and serves websockets over HTTP/2 extended CONNECT — an
         * HTTP/1.1 `Upgrade` there is closed without a response ("ws handshake EOF"). This client speaks
         * HTTP/1.1, so it must use the same host as the REST API.
         */
        const val DEFAULT_HOST = "chat.signal.org"
        const val DEFAULT_PORT = 443
        private const val KEEPALIVE_INTERVAL_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val RECONNECT_BASE_MS = 2_000L
        private const val RECONNECT_MAX_MS = 60_000L
        const val CLOSE_INVALID_AUTH = 4401
        const val CLOSE_CONNECTED_ELSEWHERE = 4409
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var isConnected = false
    private var session: WsSession? = null
    private var keepaliveJob: Job? = null
    private var reconnectJob: Job? = null

    private val _messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    /** WebSocket connection lifecycle state. */
    sealed interface ConnectionState {
        /** The socket is connecting. */
        data object Connecting : ConnectionState
        /** The socket is connected. */
        data object Connected : ConnectionState
        /** The socket disconnected, with the reason. */
        data class Disconnected(val reason: String) : ConnectionState
    }

    /** A server response to one of our requests. */
    data class RequestResult(val status: Int, val message: String, val body: ByteArray) {
        val isSuccess: Boolean get() = status in 200..299
    }

    // Requests awaiting their response, keyed by request id.
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<WebSocketResponseMessage?>>()

    private fun wsUrl(): String {
        val scheme = if (useTls) "wss" else "ws"
        return "$scheme://$host:$port/v1/websocket/"
    }

    private fun authHeaders(): Map<String, String> {
        val userAgent = mapOf("User-Agent" to SignalPayload.userAgent())
        // The unauthenticated socket deliberately carries no credentials, and receives no inbound
        // queue, so the stories header would be meaningless on it.
        if (!authenticated) return userAgent
        val login = if (authData.aci.isNotEmpty()) "${authData.aci}.${authData.deviceId}" else authData.phoneNumber
        val password = passwordOverride ?: resolvePassword() ?: ""
        val credentials = "$login:$password"
        val basic = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return userAgent + mapOf(
            "Authorization" to "Basic $basic",
            "X-Signal-Receive-Stories" to "true",
        )
    }

    private fun resolvePassword(): String? {
        return try {
            val fromAuth = authData.password.takeIf { it.isNotEmpty() }
            fromAuth ?: passwordOverride
        } catch (_: Exception) { passwordOverride }
    }

    fun connect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (true) {
                try {
                    _connectionState.emit(ConnectionState.Connecting)
                    val url = wsUrl()
                    Log.status(TAG, "connecting $url as" +
                        buildString {
                            append(if (authenticated) {
                                "${authData.aci.take(ACI_PREFIX_LENGTH)}.${authData.deviceId}"
                            } else {
                                "unauthenticated"
                            })
                        } + " host=$host")
                    doConnectOnce()
                    attempt = 0
                } catch (expected: Exception) {
                    val reason = expected.message ?: expected.javaClass.simpleName
                    Log.status(TAG, "connect failed: $reason")
                    try { _connectionState.emit(ConnectionState.Disconnected(reason)) } catch (_: Exception) {}
                    if (reason.contains("4401")) {
                        Log.error(
                            TAG,
                            "4401 invalid auth — stopping reconnect until credentials refreshed (needs live server)")
                        break
                    }
                }
                attempt++
                val backoff = (RECONNECT_BASE_MS * (1 shl minOf(attempt, 6))).coerceAtMost(RECONNECT_MAX_MS)
                delay(backoff)
                Log.status(TAG, "reconnect attempt $attempt in ${backoff}ms")
            }
        }
    }

    /** Map a WS close code to its error. */
    private fun closeError(code: Int): IllegalStateException = when (code) {
        CLOSE_INVALID_AUTH -> IllegalStateException("ws close 4401 invalid auth")
        CLOSE_CONNECTED_ELSEWHERE -> IllegalStateException("ws close 4409 connected elsewhere")
        else -> IllegalStateException("ws close $code")
    }

    private suspend fun doConnectOnce() {
        val url = wsUrl()
        val headers = authHeaders()
        // Signal's chat host chains to Signal's private service CA — trust it explicitly
        // (system trust alone throws "trust anchor for certification path not found").
        val sslFactory = SignalTrust.sslSocketFactory(context)
        webSocket(url, headers, captureResponseHeaders = listOf("x-signal-timestamp"), sslSocketFactory = sslFactory) {
            session = this
            isConnected = true
            val tsHeader = capturedHeader["x-signal-timestamp"] ?: responseHeaders.entries
                .firstOrNull { it.key.equals("x-signal-timestamp", ignoreCase = true) }?.value?.firstOrNull()
            if (tsHeader == null) {
                Log.status(TAG, "missing x-signal-timestamp — possible captive portal or proxy (needs live server)")
            } else {
                Log.status(TAG, "x-signal-timestamp=$tsHeader")
            }
            _connectionState.emit(ConnectionState.Connected)
            Log.status(TAG, "WebSocket connected to $host")
            startKeepalive()
            try {
                incoming.collect { frame ->
                    when (frame) {
                        is WebSocketClient.WsFrame.Binary -> {
                            completePending(frame.bytes)
                            _messages.emit(frame.bytes)
                        }
                        is WebSocketClient.WsFrame.Text -> {
                            Log.status(TAG, "unexpected text frame len=${frame.text.length}")
                            _messages.emit(frame.text.toByteArray(Charsets.UTF_8))
                        }
                        is WebSocketClient.WsFrame.Close -> {
                            Log.status(TAG, "ws close ${frame.code} ${frame.reason}")
                            throw closeError(frame.code)
                        }
                        else -> {}
                    }
                }
            } finally {
                isConnected = false
                stopKeepalive()
                session = null
                // Release anyone blocked on a response rather than making them wait for the timeout.
                failAllPending()
                Log.status(TAG, "WebSocket session ended")
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        scope.launch {
            stopKeepalive()
            try { session?.close() } catch (_: Exception) {}
            session = null
            isConnected = false
            _connectionState.emit(ConnectionState.Disconnected("client disconnect"))
        }
    }

    suspend fun send(data: ByteArray): Boolean {
        val s = session ?: return false
        if (!isConnected) return false
        return try {
            s.send(data)
            true
        } catch (expected: Exception) {
            Log.error(TAG, "send failed", expected)
            false
        }
    }

    /**
     * Send a request and wait for the server's response, so callers can see the status code. Returns
     * null when the frame could not be written, the socket closed, or no response arrived in time —
     * all of which are failures, but none of which carry a status.
     */
    suspend fun sendRequestAwaitingResponse(
        request: WebSocketRequestMessage,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): RequestResult? {
        val withId = if (request.hasId() && request.id != 0L) {
            request
        } else {
            request.toBuilder().setId(nextRequestId()).build()
        }
        val id = withId.id
        val deferred = CompletableDeferred<WebSocketResponseMessage?>()
        pending[id] = deferred
        try {
            val msg = WebSocketMessage.newBuilder()
                .setType(WebSocketMessage.Type.REQUEST)
                .setRequest(withId)
                .build()
            if (!send(msg.toByteArray())) return null
            val response = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: return null
            return RequestResult(
                status = if (response.hasStatus()) response.status else 0,
                message = if (response.hasMessage()) response.message else "",
                body = if (response.hasBody()) response.body.toByteArray() else ByteArray(0),
            )
        } finally {
            pending.remove(id)
        }
    }

    private fun completePending(bytes: ByteArray) {
        if (pending.isEmpty()) return
        val ws = try { WebSocketMessage.parseFrom(bytes) } catch (_: Exception) { return }
        if (ws.type != WebSocketMessage.Type.RESPONSE || !ws.hasResponse()) return
        val response = ws.response
        if (!response.hasId()) return
        pending.remove(response.id)?.complete(response)
    }

    private fun failAllPending() {
        val entries = pending.entries.toList()
        pending.clear()
        entries.forEach { it.value.complete(null) }
    }

    suspend fun sendResponse(response: WebSocketResponseMessage): Boolean {
        val msg = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.RESPONSE)
            .setResponse(response)
            .build()
        return send(msg.toByteArray())
    }

    suspend fun sendText(text: String): Boolean {
        val s = session ?: return false
        if (!isConnected) return false
        return try {
            s.send(text.toByteArray(Charsets.UTF_8))
            true
        } catch (expected: Exception) {
            Log.error(TAG, "sendText failed", expected)
            false
        }
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (sendKeepalive()) {
                delay(KEEPALIVE_INTERVAL_MS)
            }
        }
    }

    /** One keepalive round; false stops the loop. */
    private suspend fun sendKeepalive(): Boolean {
        return try {
            val keepaliveRequest = WebSocketRequestMessage.newBuilder()
                .setVerb("PUT")
                .setPath("/v1/keepalive")
                .setId(nextRequestId())
                .build()
            val msg = WebSocketMessage.newBuilder()
                .setType(WebSocketMessage.Type.REQUEST)
                .setRequest(keepaliveRequest)
                .build()
            val ok = session?.let {
                try { it.send(msg.toByteArray()); true } catch (_: Exception) { false }
            } ?: false
            if (!ok) Log.status(TAG, "keepalive send failed")
            ok
        } catch (_: Exception) {
            Log.status(TAG, "keepalive failed")
            false
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private fun nextRequestId(): Long = (SecureRandom().nextLong() and Long.MAX_VALUE).let { if (it == 0L) 1L else it }
}
