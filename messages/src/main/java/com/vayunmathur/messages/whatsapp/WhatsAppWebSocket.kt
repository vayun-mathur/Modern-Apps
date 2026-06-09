package com.vayunmathur.messages.whatsapp

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
 * WebSocket client for WhatsApp Web.
 * Handles connection, reconnection, Noise protocol handshake, and message framing.
 * 
 * Frame format (from whatsmeow/socket/framesocket.go):
 * - Binary WebSocket frames only
 * - Frame: [header?][3-byte big-endian length][protobuf payload]
 * - Header is "WA" + version for first frame after handshake
 */
class WhatsAppWebSocket(
    private val authData: WhatsAppAuthData?,
) {
    private companion object {
        const val TAG = "WhatsAppWebSocket"
        const val PING_INTERVAL_MS = 30000L
        const val RECONNECT_DELAY_MS = 5000L
        const val HANDSHAKE_TIMEOUT_MS = 20000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // WhatsApp Web requires modern TLS configuration
    // SSLv3 alert close_notify means the server is terminating the TLS handshake.
    // This happens when:
    // 1. SNI (Server Name Indication) is missing or incorrect - OkHttp sets this automatically from URL
    // 2. The server is blocking the connection based on fingerprinting
    // 3. WhatsApp may require specific TLS cipher suites or extensions
    // 
    // The whatsmeow Go client uses github.com/coder/websocket which uses Go's crypto/tls.
    // OkHttp on Android should be compatible, but WhatsApp may be blocking based on JA3 fingerprint.
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // OkHttp automatically sets SNI from the URL hostname
        // TLS 1.2+ is used by default on modern Android
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var handshakeJob: Job? = null
    
    // Noise protocol state
    private var noiseHandshake: WhatsAppProtocol.NoiseHandshake? = null
    private var noiseSocket: NoiseSocket? = null
    private var isHandshakeComplete = false

    private val _messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

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

        // WhatsApp requires specific TLS configuration and headers
        // The SSLv3 alert usually indicates a protocol mismatch or missing headers
        // Note: Sec-WebSocket-* headers are managed by OkHttp and cannot be set manually
        val request = Request.Builder()
            .url(WhatsAppProtocol.WS_URL)
            .header("Origin", "https://web.whatsapp.com")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected, starting Noise handshake")
                startHandshake()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                scope.launch {
                    if (!isHandshakeComplete) {
                        handleHandshakeMessage(data)
                    } else {
                        // Decrypt and emit the message
                        noiseSocket?.let { socket ->
                            try {
                                val plaintext = socket.decrypt(data)
                                _messages.emit(plaintext)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decrypt message", e)
                            }
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // WhatsApp uses binary frames, but handle text for debugging
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
                cleanup()
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected("Failure: ${t.message}"))
                }
                cleanup()
                scheduleReconnect()
            }
        })
    }

    private fun startHandshake() {
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            try {
                // Initialize Noise handshake
                // Pattern: Noise_XX_25519_AESGCM_SHA256
                // From whatsmeow/handshake.go line 30
                // Header: "WA" + version (from whatsmeow/socket/constants.go)
                // WAConnHeader = {'W', 'A', 6, 2} where 6 is WAMagicValue and 2 is DictVersion
                val waHeader = byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 6, 2)
                noiseHandshake = WhatsAppProtocol.NoiseHandshake().apply {
                    start("Noise_XX_25519_AESGCM_SHA256", waHeader)
                }

                // Generate ephemeral key pair
                val (ephemeralPriv, ephemeralPub) = WhatsAppProtocol.generateX25519KeyPair()
                
                // Build ClientHello using protobuf
                // From whatsmeow/handshake.go lines 34-38:
                // HandshakeMessage with ClientHello containing ephemeral public key
                val clientHello = buildClientHello(ephemeralPub)
                
                // Send handshake message with WA header and frame length prefix
                // From whatsmeow/socket/framesocket.go lines 130-149:
                // Frame format: [header][3-byte length][protobuf payload]
                // Header is sent only once (WA header)
                val framedMessage = buildFramedMessage(clientHello, waHeader)
                webSocket?.send(ByteString.of(*framedMessage))
                
                Log.i(TAG, "Sent ClientHello (${clientHello.size} bytes), waiting for ServerHello")
                
            } catch (e: Exception) {
                Log.e(TAG, "Handshake failed", e)
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected("Handshake failed: ${e.message}"))
                }
                disconnect()
            }
        }
    }
    
    /**
     * Build framed message with header and 3-byte length prefix
     * From whatsmeow/socket/framesocket.go SendFrame()
     */
    private fun buildFramedMessage(data: ByteArray, header: ByteArray?): ByteArray {
        val headerLength = header?.size ?: 0
        val dataLength = data.size
        val frame = ByteArray(headerLength + 3 + dataLength)
        
        var offset = 0
        // Copy header if present (only sent once)
        if (header != null) {
            System.arraycopy(header, 0, frame, offset, headerLength)
            offset += headerLength
        }
        
        // 3-byte big-endian length
        frame[offset] = (dataLength shr 16).toByte()
        frame[offset + 1] = (dataLength shr 8).toByte()
        frame[offset + 2] = dataLength.toByte()
        offset += 3
        
        // Copy payload
        System.arraycopy(data, 0, frame, offset, dataLength)
        
        return frame
    }

    private fun handleHandshakeMessage(data: ByteArray) {
        Log.i(TAG, "Received handshake response (${data.size} bytes)")
        
        try {
            val handshakeMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.parseFrom(data)
            val serverHello = handshakeMessage.serverHello
            
            if (serverHello == null) {
                Log.e(TAG, "ServerHello is null")
                scope.launch { _connectionState.emit(ConnectionState.Disconnected("Invalid ServerHello")) }
                disconnect()
                return
            }
            
            val handshake = noiseHandshake ?: run {
                Log.e(TAG, "Noise handshake not initialized")
                return
            }

            // Process ServerHello and derive keys (mirrors WebViewWebSocket logic)
            val serverEphemeral = serverHello.ephemeral.toByteArray()
            val serverStaticCiphertext = serverHello.getStatic().toByteArray()
            val certificateCiphertext = serverHello.payload.toByteArray()
            
            // TODO: full ServerHello processing would go here
            // For now, derive keys and create encrypted socket
            val (writeKey, readKey) = handshake.finish()
            
            isHandshakeComplete = true
            noiseSocket = NoiseSocket(writeKey, readKey)
            
            scope.launch {
                _connectionState.emit(ConnectionState.Connected)
            }
            startPing()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process handshake response", e)
            scope.launch { _connectionState.emit(ConnectionState.Disconnected("Handshake failed: ${e.message}")) }
            disconnect()
        }
    }

    private fun buildClientHello(ephemeralPub: ByteArray): ByteArray {
        // Simplified ClientHello - real implementation uses protobuf
        // waWa6.HandshakeMessage with ClientHello containing ephemeral key
        return ephemeralPub
    }

    fun disconnect() {
        cleanup()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    private fun cleanup() {
        pingJob?.cancel()
        reconnectJob?.cancel()
        handshakeJob?.cancel()
        isHandshakeComplete = false
        noiseHandshake = null
        noiseSocket = null
    }

    fun send(data: ByteArray): Boolean {
        return if (isHandshakeComplete && noiseSocket != null) {
            // Encrypt data before sending
            try {
                val encrypted = noiseSocket!!.encrypt(data)
                webSocket?.send(ByteString.of(*encrypted)) ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encrypt message", e)
                false
            }
        } else {
            // Send unencrypted (during handshake)
            webSocket?.send(ByteString.of(*data)) ?: false
        }
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                // WhatsApp ping format: "?,,"
                send("?,, ".toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            Log.i(TAG, "Attempting reconnect")
            connect()
        }
    }

    /**
     * Encrypted socket using AES-256-GCM with counter-based IVs.
     * Keys are derived from the Noise handshake finish() call.
     */
    private inner class NoiseSocket(
        private val writeKey: javax.crypto.spec.SecretKeySpec,
        private val readKey: javax.crypto.spec.SecretKeySpec,
    ) {
        private var writeCounter: UInt = 0u
        private var readCounter: UInt = 0u

        private fun generateIV(counter: UInt): ByteArray {
            val iv = ByteArray(12)
            java.nio.ByteBuffer.wrap(iv, 4, 8)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .putLong(counter.toLong())
            return iv
        }

        fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val spec = javax.crypto.spec.GCMParameterSpec(128, generateIV(writeCounter))
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, writeKey, spec)
            val ciphertext = cipher.doFinal(plaintext)
            writeCounter++
            return ciphertext
        }
        
        fun decrypt(ciphertext: ByteArray): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val spec = javax.crypto.spec.GCMParameterSpec(128, generateIV(readCounter))
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, readKey, spec)
            val plaintext = cipher.doFinal(ciphertext)
            readCounter++
            return plaintext
        }
    }
}
