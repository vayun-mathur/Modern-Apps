package com.vayunmathur.messages.meta

import android.util.Log
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MetaMqttClient(
    private val authData: MetaAuthData,
) {
    private companion object {
        const val TAG = "MetaMqttClient"
        const val PING_INTERVAL_MS = 10000L
        const val PONG_TIMEOUT_MS = 30000L
        const val RECONNECT_DELAY_MS = 5000L
        const val ACK_TIMEOUT_MS = 30000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val writeMutex = Mutex()
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var pongTimeoutJob: Job? = null

    private val packetsSent = AtomicInteger(0)
    private val sessionId = MetaProtocol.generateSessionId()

    // Pending ACK channels
    private val pubAckChannels = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val subAckChannels = ConcurrentHashMap<Int, CompletableDeferred<Int>>()
    private val requestChannels = ConcurrentHashMap<Int, CompletableDeferred<MetaProtocol.MqttMessage>>()

    private var previouslyConnected = false
    var versionId: Long = 0L
    var appId: String = when (authData.platform) {
        MetaAuthData.Platform.INSTAGRAM -> "936619743392459"
        MetaAuthData.Platform.MESSENGER -> "219994525426954"
    }

    private val _messages = MutableSharedFlow<MetaProtocol.MqttMessage>(extraBufferCapacity = 256)
    val messages: SharedFlow<MetaProtocol.MqttMessage> = _messages.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16, replay = 1)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    sealed interface ConnectionState {
        data object Connecting : ConnectionState
        data object Connected : ConnectionState
        data class Disconnected(val reason: String) : ConnectionState
    }

    fun safePacketId(): Int {
        while (true) {
            val id = packetsSent.incrementAndGet() and 0xFFFF
            if (id != 0) return id
        }
    }

    fun connect() {
        scope.launch {
            _connectionState.emit(ConnectionState.Connecting)
        }

        val mqttUrl = buildBrokerUrl()

        val request = Request.Builder()
            .url(mqttUrl)
            .header("Cookie", authData.toCookieHeader())
            .header("Origin", when (authData.platform) {
                MetaAuthData.Platform.MESSENGER -> MetaProtocol.MESSENGER_BASE_URL
                MetaAuthData.Platform.INSTAGRAM -> MetaProtocol.INSTAGRAM_BASE_URL
            })
            .header("User-Agent", MetaProtocol.USER_AGENT)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected for ${authData.platform}")
                scope.launch {
                    try {
                        sendConnectPacket()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send CONNECT packet", e)
                        _connectionState.emit(ConnectionState.Disconnected("Connect failed: ${e.message}"))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.w(TAG, "Unexpected text message in websocket")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                cancelAllPending()
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected("Closed: $reason"))
                }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                cancelAllPending()
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected("Failure: ${t.message}"))
                }
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        pingJob?.cancel()
        pongTimeoutJob?.cancel()
        reconnectJob?.cancel()
        cancelAllPending()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    private fun buildBrokerUrl(): String {
        val baseUrl = when (authData.platform) {
            MetaAuthData.Platform.MESSENGER -> MetaProtocol.MESSENGER_MQTT_URL
            MetaAuthData.Platform.INSTAGRAM -> MetaProtocol.INSTAGRAM_MQTT_URL
        }
        val cid = authData.cookies["cid"] ?: java.util.UUID.randomUUID().toString()
        return "${baseUrl}sid=$sessionId&cid=$cid"
    }

    private suspend fun sendData(data: ByteArray): Boolean {
        writeMutex.withLock {
            val ws = webSocket ?: return false
            return ws.send(ByteString.of(*data))
        }
    }

    private suspend fun sendConnectPacket() {
        val connectJsonStr = MetaProtocol.buildConnectJson(
            accountId = authData.userId,
            sessionId = sessionId,
            appId = authData.cookies["appId"]?.toLongOrNull() ?: when (authData.platform) {
                MetaAuthData.Platform.INSTAGRAM -> 936619743392459L
                MetaAuthData.Platform.MESSENGER -> 219994525426954L
            },
            cid = authData.cookies["cid"] ?: java.util.UUID.randomUUID().toString(),
            platform = authData.platform,
            previouslyConnected = previouslyConnected,
            versionId = versionId,
        )

        val connectPacket = MqttFraming.buildConnectPacket(connectJsonStr)
        sendData(connectPacket)
    }

    private fun handleBinaryMessage(data: ByteArray) {
        val response = MqttFraming.parseResponse(data) ?: return

        // Any inbound traffic resets pong timeout
        resetPongTimeout()

        when (response) {
            is MqttFraming.MqttResponse.ConnAck -> handleConnAck(response)
            is MqttFraming.MqttResponse.PubAck -> handlePubAck(response)
            is MqttFraming.MqttResponse.SubAck -> handleSubAck(response)
            is MqttFraming.MqttResponse.PublishMessage -> handlePublishMessage(response)
            is MqttFraming.MqttResponse.PingResp -> {
                Log.d(TAG, "Got ping response")
            }
        }
    }

    private fun handleConnAck(connAck: MqttFraming.MqttResponse.ConnAck) {
        if (connAck.connectionCode != MetaProtocol.CONNECTION_ACCEPTED) {
            Log.e(TAG, "Connection refused: code=${connAck.connectionCode}")
            scope.launch {
                _connectionState.emit(ConnectionState.Disconnected("Connection refused: ${connAck.connectionCode}"))
            }
            return
        }

        Log.i(TAG, "CONNACK received, connection accepted")
        scope.launch {
            try {
                handleReady()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle ready event", e)
                _connectionState.emit(ConnectionState.Disconnected("Ready failed: ${e.message}"))
            }
        }
    }

    private suspend fun handleReady() {
        if (previouslyConnected) {
            _connectionState.emit(ConnectionState.Connected)
            startPing()
            return
        }

        // Send app settings (from messagix/events.go handleReadyEvent)
        val appSettingsJson = MetaProtocol.buildAppSettingsJson(versionId)
        val packetId = safePacketId()
        sendPublishPacket(MetaProtocol.TOPIC_LS_APP_SETTINGS, appSettingsJson, packetId)

        // Subscribe to required topics (from messagix/events.go handleReadyEvent)
        sendSubscribePacket(MetaProtocol.TOPIC_LS_FOREGROUND_STATE, MqttPackets.QOS_LEVEL_0)
        sendSubscribePacket(MetaProtocol.TOPIC_LS_RESP, MqttPackets.QOS_LEVEL_0)

        _connectionState.emit(ConnectionState.Connected)
        startPing()

        // Fetch threads for SyncGroup 1 and SyncGroup 95 (from messagix/events.go)
        val fetchPayloadSG1 = MetaProtocol.buildFetchThreadsPayload(versionId)
        makeLSRequest(fetchPayloadSG1, MetaProtocol.LS_REQUEST_TYPE_TASK)

        val fetchPayloadSG95 = MetaProtocol.buildFetchThreadsPayload(versionId, syncGroup = 95)
        makeLSRequest(fetchPayloadSG95, MetaProtocol.LS_REQUEST_TYPE_TASK)

        // Report app state as FOREGROUND (from messagix/events.go)
        val reportPayload = MetaProtocol.buildReportAppStatePayload(versionId)
        makeLSRequest(reportPayload, MetaProtocol.LS_REQUEST_TYPE_TASK)

        previouslyConnected = true
    }

    private fun handlePubAck(pubAck: MqttFraming.MqttResponse.PubAck) {
        pubAckChannels.remove(pubAck.packetId)?.complete(Unit)
    }

    private fun handleSubAck(subAck: MqttFraming.MqttResponse.SubAck) {
        subAckChannels.remove(subAck.packetId)?.complete(subAck.qosLevel)
    }

    private fun handlePublishMessage(publish: MqttFraming.MqttResponse.PublishMessage) {
        if (publish.qos == MqttPackets.QOS_LEVEL_1.toInt() && publish.packetId > 0) {
            scope.launch {
                sendData(MqttFraming.buildPubAckPacket(publish.packetId))
            }
        } else if (publish.qos == MqttPackets.QOS_LEVEL_2.toInt()) {
            Log.e(TAG, "Got packet with QoS level 2")
        }

        val mqttMessage = MetaProtocol.MqttMessage(
            topic = publish.topic,
            payload = publish.payload,
            packetId = publish.packetId,
            qos = publish.qos,
        )

        // Check if this is a response to a pending request
        val responseData = MetaProtocol.parsePublishResponse(publish.payload)
        if (responseData != null && responseData.requestId > 0) {
            val requestIdInt = responseData.requestId.toInt()
            requestChannels.remove(requestIdInt)?.complete(mqttMessage)
        }

        // Always emit for downstream processing
        scope.launch {
            _messages.emit(mqttMessage)
        }
    }

    suspend fun sendPublishPacket(
        topic: String,
        jsonData: String,
        packetId: Int = safePacketId(),
        qos: Byte = MqttPackets.QOS_LEVEL_1,
    ): Int {
        val packet = MqttFraming.buildPublishPacket(topic, jsonData, qos, packetId)

        if (qos > 0) {
            val ackDeferred = CompletableDeferred<Unit>()
            pubAckChannels[packetId] = ackDeferred
        }

        if (!sendData(packet)) {
            pubAckChannels.remove(packetId)
            return -1
        }

        if (qos > 0) {
            try {
                withTimeout(ACK_TIMEOUT_MS) {
                    pubAckChannels[packetId]?.await()
                }
            } catch (e: Exception) {
                pubAckChannels.remove(packetId)
                Log.w(TAG, "Timeout waiting for PUBACK for packet $packetId")
            }
        }

        return packetId
    }

    private suspend fun sendSubscribePacket(topic: String, qos: Byte): Boolean {
        val packetId = safePacketId()
        val packet = MqttFraming.buildSubscribePacket(topic, qos, packetId)
        val ackDeferred = CompletableDeferred<Int>()
        subAckChannels[packetId] = ackDeferred
        if (!sendData(packet)) {
            subAckChannels.remove(packetId)
            return false
        }
        try {
            withTimeout(ACK_TIMEOUT_MS) {
                ackDeferred.await()
            }
        } catch (e: Exception) {
            subAckChannels.remove(packetId)
            Log.w(TAG, "Timeout waiting for SUBACK for topic $topic")
        }
        return true
    }

    suspend fun makeLSRequest(payload: String, type: Int): MetaProtocol.MqttMessage? {
        val packetId = safePacketId()
        val lsRequestJson = MetaProtocol.buildLSRequestJson(
            appId = appId,
            payload = payload,
            requestId = packetId,
            type = type,
        )

        val responseDeferred = CompletableDeferred<MetaProtocol.MqttMessage>()
        requestChannels[packetId] = responseDeferred

        val sentId = sendPublishPacket(MetaProtocol.TOPIC_LS_REQ, lsRequestJson, packetId)
        if (sentId < 0) {
            requestChannels.remove(packetId)
            return null
        }

        // PUBLISH type doesn't expect a response
        if (type == MetaProtocol.LS_REQUEST_TYPE_STATELESS) {
            requestChannels.remove(packetId)
            return null
        }

        return try {
            withTimeout(ACK_TIMEOUT_MS) {
                responseDeferred.await()
            }
        } catch (e: Exception) {
            requestChannels.remove(packetId)
            Log.w(TAG, "Timeout waiting for LS response for request $packetId")
            null
        }
    }

    suspend fun publish(topic: String, payload: ByteArray): Boolean {
        val jsonData = String(payload, Charsets.UTF_8)
        return sendPublishPacket(topic, jsonData) >= 0
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                val sent = sendData(MqttFraming.buildPingReqPacket())
                if (!sent) {
                    Log.e(TAG, "Failed to send ping")
                    break
                }
            }
        }
        resetPongTimeout()
    }

    private fun resetPongTimeout() {
        pongTimeoutJob?.cancel()
        pongTimeoutJob = scope.launch {
            delay(PONG_TIMEOUT_MS)
            Log.e(TAG, "Pong timeout")
            disconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            Log.i(TAG, "Attempting reconnect for ${authData.platform}")
            connect()
        }
    }

    private fun cancelAllPending() {
        val cancelError = Exception("Connection closed")
        pubAckChannels.values.forEach { it.completeExceptionally(cancelError) }
        pubAckChannels.clear()
        subAckChannels.values.forEach { it.completeExceptionally(cancelError) }
        subAckChannels.clear()
        requestChannels.values.forEach { it.completeExceptionally(cancelError) }
        requestChannels.clear()
    }
}
