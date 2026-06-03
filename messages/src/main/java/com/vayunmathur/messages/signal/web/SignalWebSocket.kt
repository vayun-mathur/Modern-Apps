package com.vayunmathur.messages.signal.web

import android.content.Context
import android.util.Log
import com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketMessage
import com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketRequestMessage
import com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketResponseMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class SignalWebSocket(
    private val context: Context,
    private val basicAuth: String? = null,
) {
    sealed class ConnectionEvent {
        object Connected : ConnectionEvent()
        data class Disconnected(val reason: String) : ConnectionEvent()
        object LoggedOut : ConnectionEvent()
    }

    private companion object {
        const val TAG = "SignalWebSocket"
        const val PING_INTERVAL_MS = 30_000L
        const val INITIAL_BACKOFF_MS = 10_000L
        const val BACKOFF_INCREMENT_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<WebSocketResponseMessage>>()

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var currentUrl: String? = null
    private var currentBackoff = INITIAL_BACKOFF_MS
    private var shouldReconnect = false

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(replay = 1)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents.asSharedFlow()

    var isConnected: Boolean = false
        private set

    var incomingRequestHandler: ((WebSocketRequestMessage) -> Unit)? = null

    private val client: OkHttpClient by lazy {
        val (sslSocketFactory, trustManager) = CertPinning.createSslSocketFactory(context)
        OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Connected")
            isConnected = true
            currentBackoff = INITIAL_BACKOFF_MS
            startPingLoop()
            scope.launch { _connectionEvents.emit(ConnectionEvent.Connected) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val message = WebSocketMessage.parseFrom(bytes.toByteArray())
                when (message.type) {
                    WebSocketMessage.Type.RESPONSE -> handleResponse(message.response)
                    WebSocketMessage.Type.REQUEST -> handleRequest(message.request)
                    else -> Log.w(TAG, "Unknown message type: ${message.type}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Closed: $code $reason")
            onDisconnected(reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Failure: ${t.message}")
            if (response?.code == 403) {
                shouldReconnect = false
                onDisconnected("Logged out")
                scope.launch { _connectionEvents.emit(ConnectionEvent.LoggedOut) }
                return
            }
            onDisconnected(t.message ?: "Unknown error")
        }
    }

    fun connect(url: String) {
        currentUrl = url
        shouldReconnect = true
        openSocket(url)
    }

    fun disconnect() {
        shouldReconnect = false
        pingJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected = false
        failAllPending("Disconnected")
    }

    suspend fun sendRequest(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): WebSocketResponseMessage {
        val id = requestId.getAndIncrement()
        val deferred = CompletableDeferred<WebSocketResponseMessage>()
        pendingRequests[id] = deferred

        val request = WebSocketRequestMessage.newBuilder()
            .setId(id)
            .setVerb(method)
            .setPath(path)
            .apply {
                if (body != null) setBody(com.google.protobuf.ByteString.copyFrom(body))
                headers.forEach { (k, v) -> addHeaders("$k:$v") }
            }
            .build()

        val message = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.REQUEST)
            .setRequest(request)
            .build()

        val sent = webSocket?.send(message.toByteArray().toByteString()) ?: false
        if (!sent) {
            pendingRequests.remove(id)
            throw IOException("WebSocket send failed")
        }

        return deferred.await()
    }

    fun sendResponse(requestId: Long, status: Int) {
        val response = WebSocketResponseMessage.newBuilder()
            .setId(requestId)
            .setStatus(status)
            .build()

        val message = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.RESPONSE)
            .setResponse(response)
            .build()

        webSocket?.send(message.toByteArray().toByteString())
    }

    private fun openSocket(url: String) {
        val request = Request.Builder()
            .url(url)
            .apply {
                if (basicAuth != null) {
                    header("Authorization", "Basic $basicAuth")
                }
            }
            .build()

        webSocket = client.newWebSocket(request, listener)
    }

    private fun handleResponse(response: WebSocketResponseMessage) {
        val deferred = pendingRequests.remove(response.id)
        if (deferred != null) {
            deferred.complete(response)
        } else {
            Log.w(TAG, "No pending request for response id=${response.id}")
        }
    }

    private fun handleRequest(request: WebSocketRequestMessage) {
        val handler = incomingRequestHandler
        if (handler != null) {
            handler(request)
        } else {
            Log.w(TAG, "No handler for incoming request: ${request.verb} ${request.path}")
            sendResponse(request.id, 400)
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                if (isConnected) {
                    val keepAlive = WebSocketMessage.newBuilder()
                        .setType(WebSocketMessage.Type.REQUEST)
                        .setRequest(
                            WebSocketRequestMessage.newBuilder()
                                .setId(requestId.getAndIncrement())
                                .setVerb("GET")
                                .setPath("/v1/keepalive")
                                .build()
                        ).build()
                    webSocket?.send(keepAlive.toByteArray().toByteString())
                }
            }
        }
    }

    private fun onDisconnected(reason: String) {
        isConnected = false
        pingJob?.cancel()
        failAllPending(reason)
        scope.launch { _connectionEvents.emit(ConnectionEvent.Disconnected(reason)) }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val url = currentUrl ?: return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.d(TAG, "Reconnecting in ${currentBackoff}ms")
            delay(currentBackoff)
            currentBackoff = (currentBackoff + BACKOFF_INCREMENT_MS).coerceAtMost(MAX_BACKOFF_MS)
            openSocket(url)
        }
    }

    private fun failAllPending(reason: String) {
        val error = IOException("WebSocket closed: $reason")
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()
    }
}
