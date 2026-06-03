package com.vayunmathur.messages.meta

import android.util.Log
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
import java.util.concurrent.TimeUnit

/**
 * MQTT over WebSocket client for Meta platforms.
 * Handles connection to Messenger/Instagram MQTT brokers.
 */
class MetaMqttClient(
    private val authData: MetaAuthData,
) {
    private companion object {
        const val TAG = "MetaMqttClient"
        const val PING_INTERVAL_MS = 30000L
        const val RECONNECT_DELAY_MS = 5000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null

    private val _messages = MutableSharedFlow<MetaProtocol.MqttMessage>(extraBufferCapacity = 256)
    val messages: SharedFlow<MetaProtocol.MqttMessage> = _messages.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    sealed interface ConnectionState {
        data object Connecting : ConnectionState
        data object Connected : ConnectionState
        data class Disconnected(val reason: String) : ConnectionState
    }

    fun connect() {
        scope.launch {
            _connectionState.emit(ConnectionState.Connecting)
        }

        val mqttUrl = when (authData.platform) {
            MetaAuthData.Platform.MESSENGER -> MetaProtocol.MESSENGER_MQTT_URL
            MetaAuthData.Platform.INSTAGRAM -> MetaProtocol.INSTAGRAM_MQTT_URL
        }

        val request = Request.Builder()
            .url(mqttUrl)
            .header("Cookie", authData.toCookieHeader())
            .header("Origin", when (authData.platform) {
                MetaAuthData.Platform.MESSENGER -> MetaProtocol.MESSENGER_BASE_URL
                MetaAuthData.Platform.INSTAGRAM -> MetaProtocol.INSTAGRAM_BASE_URL
            })
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "MQTT WebSocket connected for ${authData.platform}")
                scope.launch {
                    _connectionState.emit(ConnectionState.Connected)
                }
                startPing()
                subscribeToTopics()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch {
                    // Parse MQTT frame and extract topic/payload
                    // Simplified: assume payload contains topic info
                    val message = MetaProtocol.MqttMessage(
                        topic = MetaProtocol.TOPIC_LS_RESP,
                        payload = bytes.toByteArray()
                    )
                    _messages.emit(message)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received text message: $text")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected("Closed: $reason"))
                }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected("Failure: ${t.message}"))
                }
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        pingJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    fun publish(topic: String, payload: ByteArray): Boolean {
        // MQTT publish frame format
        // Simplified: send as binary with topic prefix
        val frame = buildMqttPublishFrame(topic, payload)
        return webSocket?.send(ByteString.of(*frame)) ?: false
    }

    fun subscribe(topic: String): Boolean {
        // MQTT subscribe frame
        val frame = buildMqttSubscribeFrame(topic)
        return webSocket?.send(ByteString.of(*frame)) ?: false
    }

    private fun subscribeToTopics() {
        subscribe(MetaProtocol.TOPIC_LS_RESP)
        subscribe(MetaProtocol.TOPIC_TMS)
        subscribe(MetaProtocol.TOPIC_LS_APP_SETTINGS)
    }

    private fun buildMqttPublishFrame(topic: String, payload: ByteArray): ByteArray {
        // Simplified MQTT PUBLISH frame
        // Real implementation requires proper MQTT framing
        val topicBytes = topic.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(2 + topicBytes.size + payload.size)
        frame[0] = 0x30.toByte() // PUBLISH, QoS 0
        frame[1] = (topicBytes.size + payload.size).toByte()
        System.arraycopy(topicBytes, 0, frame, 2, topicBytes.size)
        System.arraycopy(payload, 0, frame, 2 + topicBytes.size, payload.size)
        return frame
    }

    private fun buildMqttSubscribeFrame(topic: String): ByteArray {
        // Simplified MQTT SUBSCRIBE frame
        val topicBytes = topic.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(5 + topicBytes.size)
        frame[0] = 0x82.toByte() // SUBSCRIBE
        frame[1] = (3 + topicBytes.size).toByte()
        frame[2] = 0x00 // Message ID MSB
        frame[3] = 0x01 // Message ID LSB
        frame[4] = topicBytes.size.toByte()
        System.arraycopy(topicBytes, 0, frame, 5, topicBytes.size)
        return frame
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                // MQTT PINGREQ
                webSocket?.send(ByteString.of(0xC0.toByte(), 0x00.toByte()))
            }
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
}
