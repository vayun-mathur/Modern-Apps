package com.vayunmathur.messages.whatsapp

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebView-based WebSocket client for WhatsApp Web.
 * 
 * Uses Android WebView (Chromium) to establish WebSocket connections, which provides:
 * - Browser-identical TLS fingerprint (bypasses JA3 fingerprinting)
 * - Automatic handling of cookies, headers, and browser characteristics
 * - Same network stack as Chrome browser
 * 
 * WhatsApp blocks non-browser TLS fingerprints. WebView solves this by using
 * the system's Chromium-based WebView which is indistinguishable from Chrome.
 */
class WebViewWebSocket(
    private val context: Context,
    private val authData: WhatsAppAuthData?,
) {
    private companion object {
        const val TAG = "WebViewWebSocket"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var webView: WebView? = null
    private var isConnected = false

    private val _messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    sealed interface ConnectionState {
        data object Connecting : ConnectionState
        data object Connected : ConnectionState
        data class Disconnected(val reason: String) : ConnectionState
    }

    // Noise protocol state
    private var noiseHandshake: WhatsAppProtocol.NoiseHandshake? = null
    private var isHandshakeComplete = false
    private var handshakeJob: Job? = null
    private var ephemeralPrivateKey: ByteArray? = null
    private var ephemeralPublicKey: ByteArray? = null
    private var scriptInjected = false

    /**
     * JavaScript interface for communicating with WebView
     */
    private inner class WebSocketBridge {
        @JavascriptInterface
        fun onOpen() {
            Log.i(TAG, "WebSocket opened via WebView, starting Noise handshake")
            // Don't emit Connected yet - need to complete Noise handshake first
            startNoiseHandshake()
        }

        @JavascriptInterface
        fun onMessage(data: String) {
            // Data is base64-encoded binary
            try {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                scope.launch {
                    if (!isHandshakeComplete) {
                        handleHandshakeMessage(bytes)
                    } else {
                        _messages.emit(bytes)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode message", e)
            }
        }

        @JavascriptInterface
        fun onClose(code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            isConnected = false
            scope.launch {
                _connectionState.emit(ConnectionState.Disconnected("Closed: $reason"))
            }
        }

        @JavascriptInterface
        fun onError(error: String) {
            Log.e(TAG, "WebSocket error: $error")
            scope.launch {
                _connectionState.emit(ConnectionState.Disconnected("Error: $error"))
            }
        }

        @JavascriptInterface
        fun onLog(message: String) {
            Log.d(TAG, "JS: $message")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun connect() {
        Log.i(TAG, "=== Starting WebView WebSocket connection ===")
        Log.i(TAG, "Target URL: ${WhatsAppProtocol.WS_URL}")
        scope.launch {
            _connectionState.emit(ConnectionState.Connecting)
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Creating WebView on main thread")
                webView = WebView(context).apply {
                    Log.d(TAG, "WebView created, configuring settings")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Use a modern Chrome User-Agent to match browser TLS fingerprint
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    Log.d(TAG, "User-Agent set to: ${settings.userAgentString}")
                    
                    addJavascriptInterface(WebSocketBridge(), "Android")
                    Log.d(TAG, "JavaScript interface added")
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            Log.d(TAG, "WebView page started loading: $url")
                        }
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.d(TAG, "WebView page finished loading: $url")
                            if (!scriptInjected) {
                                scriptInjected = true
                                injectWebSocketScript()
                            }
                        }
                        
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            Log.e(TAG, "WebView error: $errorCode $description at $failingUrl")
                            scope.launch {
                                _connectionState.emit(ConnectionState.Disconnected("WebView error: $description"))
                            }
                        }
                    }
                    
                    // Load blank page with WhatsApp origin to ensure correct Origin header
                    // The WebSocket will be created from this origin, so the browser will set
                    // Origin: https://web.whatsapp.com automatically
                    // Using loadDataWithBaseURL sets the origin without loading actual WhatsApp page
                    // (which would interfere with our WebSocket via CSP)
                    loadDataWithBaseURL(
                        "https://web.whatsapp.com",
                        "<html><body></body></html>",
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
                Log.d(TAG, "WebView setup complete")
            }
        }
    }

    private fun injectWebSocketScript() {
        val js = """
            (function() {
                Android.onLog('Injecting WebSocket script');
                Android.onLog('Target URL: ${WhatsAppProtocol.WS_URL}');
                
                try {
                    // Create WebSocket connection
                    // WebView will use browser's TLS stack with proper fingerprint
                    const ws = new WebSocket('${WhatsAppProtocol.WS_URL}');
                    ws.binaryType = 'arraybuffer';
                    
                    ws.onopen = function() {
                        Android.onLog('WebSocket opened successfully');
                        Android.onOpen();
                    };
                    
                    ws.onmessage = function(event) {
                        Android.onLog('WebSocket message received, type: ' + (event.data instanceof ArrayBuffer ? 'ArrayBuffer' : typeof event.data));
                        if (event.data instanceof ArrayBuffer) {
                            const bytes = new Uint8Array(event.data);
                            // Convert to base64 for transfer to Kotlin
                            let binary = '';
                            for (let i = 0; i < bytes.length; i++) {
                                binary += String.fromCharCode(bytes[i]);
                            }
                            const base64 = btoa(binary);
                            Android.onMessage(base64);
                        } else {
                            Android.onLog('Received non-binary message: ' + event.data);
                        }
                    };
                    
                    ws.onclose = function(event) {
                        Android.onLog('WebSocket closed: code=' + event.code + ', reason=' + event.reason + ', wasClean=' + event.wasClean);
                        Android.onClose(event.code, event.reason);
                    };
                    
                    ws.onerror = function(error) {
                        Android.onLog('WebSocket error occurred');
                        Android.onError('WebSocket error');
                    };
                    
                    // Store WebSocket for sending messages
                    window.whatsappWS = ws;
                    Android.onLog('WebSocket script injected successfully, connecting...');
                } catch (e) {
                    Android.onLog('Exception during WebSocket creation: ' + e.message);
                    Android.onError('Exception: ' + e.message);
                }
            })();
        """.trimIndent()
        
        webView?.evaluateJavascript(js, null)
    }

    fun disconnect() {
        scope.launch {
            withContext(Dispatchers.Main) {
                webView?.evaluateJavascript("if (window.whatsappWS) window.whatsappWS.close();", null)
                webView?.destroy()
                webView = null
            }
            isConnected = false
            isHandshakeComplete = false
            scriptInjected = false
            noiseHandshake = null
            handshakeJob?.cancel()
            _connectionState.emit(ConnectionState.Disconnected("Client disconnect"))
        }
    }

    private fun startNoiseHandshake() {
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            try {
                // Initialize Noise handshake with WA header
                // From whatsmeow/socket/constants.go: WAConnHeader = {'W', 'A', 6, 3}
                // CRITICAL: Version must be 3 (DictVersion), not 2!
                val waHeader = byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 6, 3)
                noiseHandshake = WhatsAppProtocol.NoiseHandshake().apply {
                    start("Noise_XX_25519_AESGCM_SHA256", waHeader)
                }

                // Generate ephemeral key pair
                val (ephemeralPriv, ephemeralPub) = WhatsAppProtocol.generateX25519KeyPair()
                ephemeralPrivateKey = ephemeralPriv
                ephemeralPublicKey = ephemeralPub
                
                // CRITICAL: Authenticate ephemeral public key into handshake hash
                // From whatsmeow/handshake.go line 33: nh.Authenticate(ephemeralKP.Pub[:])
                noiseHandshake?.authenticate(ephemeralPub)
                
                // Build ClientHello using protobuf
                // From whatsmeow/handshake.go:
                //   HandshakeMessage{ ClientHello: { Ephemeral: ephemeralPub } }
                val handshakeMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.newBuilder()
                    .setClientHello(
                        com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.ClientHello.newBuilder()
                            .setEphemeral(com.google.protobuf.ByteString.copyFrom(ephemeralPub))
                            .build()
                    )
                    .build()
                val clientHelloBytes = handshakeMessage.toByteArray()
                
                // Send with WA header and frame length prefix
                // Send with WA header and frame length prefix
                val framedMessage = buildFramedMessage(clientHelloBytes, waHeader)
                
                // Log the exact bytes being sent for debugging
                val framedHex = framedMessage.joinToString(" ") { "%02X".format(it) }
                Log.d(TAG, "Sending framed message (${framedMessage.size} bytes): $framedHex")
                Log.d(TAG, "  - WA Header: ${waHeader.joinToString(" ") { "%02X".format(it) }}")
                Log.d(TAG, "  - Length: ${clientHelloBytes.size} (0x${clientHelloBytes.size.toString(16).uppercase()})")
                Log.d(TAG, "  - Protobuf: ${clientHelloBytes.joinToString(" ") { "%02X".format(it) }}")
                
                sendRaw(framedMessage)
                
                Log.i(TAG, "Sent ClientHello protobuf (${clientHelloBytes.size} bytes) with WA header v3, waiting for ServerHello")
                
                // Store ephemeral private key for later use in handshake
                // (Need to complete the handshake when ServerHello arrives)
                
            } catch (e: Exception) {
                Log.e(TAG, "Handshake failed", e)
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected("Handshake failed: ${e.message}"))
                }
                disconnect()
            }
        }
    }

    private fun handleHandshakeMessage(data: ByteArray) {
        Log.i(TAG, "Received handshake response (${data.size} bytes)")
        // Log hex dump to see what the server actually sent
        val hex = data.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "Response hex: $hex")
        
        try {
            // Parse ServerHello protobuf
            // From whatsmeow/handshake.go lines 52-56
            val handshakeMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.parseFrom(data)
            val serverHello = handshakeMessage.serverHello
            
            if (serverHello == null) {
                Log.e(TAG, "ServerHello is null in handshake response")
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected("Invalid ServerHello"))
                }
                disconnect()
                return
            }
            
            val serverEphemeral = serverHello.ephemeral.toByteArray()
            val serverStaticCiphertext = serverHello.getStatic().toByteArray()
            val certificateCiphertext = serverHello.payload.toByteArray()
            
            Log.d(TAG, "ServerHello parsed: ephemeral=${serverEphemeral.size}b, static=${serverStaticCiphertext.size}b, cert=${certificateCiphertext.size}b")
            
            if (serverEphemeral.size != 32 || serverStaticCiphertext.isEmpty() || certificateCiphertext.isEmpty()) {
                Log.e(TAG, "Invalid ServerHello: missing required fields")
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected("Invalid ServerHello fields"))
                }
                disconnect()
                return
            }
            
            val handshake = noiseHandshake ?: run {
                Log.e(TAG, "Noise handshake not initialized")
                return
            }
            
            val ephPriv = ephemeralPrivateKey ?: run {
                Log.e(TAG, "Ephemeral private key not available")
                return
            }
            
            // From whatsmeow/handshake.go lines 65-69:
            // Authenticate server ephemeral, then mix shared secret
            handshake.authenticate(serverEphemeral)
            handshake.mixSharedSecretIntoKey(ephPriv, serverEphemeral)
            
            // Decrypt server static key (lines 71-76)
            val staticDecrypted = try {
                handshake.decrypt(serverStaticCiphertext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt server static key", e)
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected("Failed to decrypt static key"))
                }
                disconnect()
                return
            }
            
            if (staticDecrypted.size != 32) {
                Log.e(TAG, "Invalid static key length: ${staticDecrypted.size}, expected 32")
                scope.launch {
                    _connectionState.emit(ConnectionState.Disconnected("Invalid static key length"))
                }
                disconnect()
                return
            }
            
            // Mix shared secret with static key (lines 77-80)
            handshake.mixSharedSecretIntoKey(ephPriv, staticDecrypted)
            
            // Decrypt certificate (lines 82-87)
            // Note: Skipping certificate verification for now (complex, requires cert chain validation)
            // In production, should verify using WhatsApp CA public key
            val certDecrypted = try {
                handshake.decrypt(certificateCiphertext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt certificate", e)
                // Continue anyway for now - cert verification is complex
                ByteArray(0)
            }
            
            Log.d(TAG, "Certificate decrypted (${certDecrypted.size} bytes), skipping verification for now")
            
            // Send ClientFinish (from whatsmeow/handshake.go lines 89-119)
            // 1. Encrypt client's noise static public key
            val noiseKeyPair = authData?.let {
                WhatsAppProtocol.generateX25519KeyPair()
            } ?: WhatsAppProtocol.generateX25519KeyPair()
            val (noisePriv, noisePub) = noiseKeyPair
            
            val encryptedPubkey = handshake.encrypt(noisePub)
            
            // 2. Mix shared secret: noise private key × server ephemeral
            handshake.mixSharedSecretIntoKey(noisePriv, serverEphemeral)
            
            // 3. Build and encrypt ClientPayload
            // For a new device pairing, we send a minimal companion payload
            val clientPayloadBytes = buildClientPayload()
            val encryptedPayload = handshake.encrypt(clientPayloadBytes)
            
            // 4. Build and send ClientFinish HandshakeMessage
            val clientFinishMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.newBuilder()
                .setClientFinish(
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.ClientFinish.newBuilder()
                        .setStatic(com.google.protobuf.ByteString.copyFrom(encryptedPubkey))
                        .setPayload(com.google.protobuf.ByteString.copyFrom(encryptedPayload))
                        .build()
                )
                .build()
            val clientFinishBytes = clientFinishMessage.toByteArray()
            val framedFinish = buildFramedMessage(clientFinishBytes, null)
            sendRaw(framedFinish)
            
            Log.i(TAG, "Sent ClientFinish (${clientFinishBytes.size} bytes)")
            
            // 5. Derive final encryption keys
            val (writeKey, readKey) = handshake.finish()
            
            Log.i(TAG, "Noise handshake complete, derived read/write keys")
            isHandshakeComplete = true
            isConnected = true
            scope.launch {
                _connectionState.emit(ConnectionState.Connected)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process handshake response", e)
            scope.launch {
                _connectionState.emit(ConnectionState.Disconnected("Handshake processing failed: ${e.message}"))
            }
            disconnect()
        }
    }

    private fun buildClientPayload(): ByteArray {
        // Minimal companion device registration payload
        // In production, this should be a proper ClientPayload protobuf
        // For now, return an empty payload which the server will process for pairing
        return ByteArray(0)
    }

    private fun buildFramedMessage(data: ByteArray, header: ByteArray?): ByteArray {
        val headerLength = header?.size ?: 0
        val dataLength = data.size
        val frame = ByteArray(headerLength + 3 + dataLength)
        
        var offset = 0
        if (header != null) {
            System.arraycopy(header, 0, frame, offset, headerLength)
            offset += headerLength
        }
        
        // 3-byte big-endian length
        frame[offset] = (dataLength shr 16).toByte()
        frame[offset + 1] = (dataLength shr 8).toByte()
        frame[offset + 2] = dataLength.toByte()
        offset += 3
        
        System.arraycopy(data, 0, frame, offset, dataLength)
        return frame
    }

    private fun sendRaw(data: ByteArray): Boolean {
        return try {
            val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
            val js = """
                (function() {
                    if (window.whatsappWS && window.whatsappWS.readyState === WebSocket.OPEN) {
                        const binary = atob('$base64');
                        const bytes = new Uint8Array(binary.length);
                        for (let i = 0; i < binary.length; i++) {
                            bytes[i] = binary.charCodeAt(i);
                        }
                        window.whatsappWS.send(bytes.buffer);
                        return true;
                    }
                    return false;
                })();
            """.trimIndent()
            
            var result = false
            webView?.evaluateJavascript(js) { value ->
                result = value == "true"
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send raw message", e)
            false
        }
    }

    fun send(data: ByteArray): Boolean {
        if (!isConnected) return false
        
        return try {
            // Convert to base64 for JavaScript
            val base64 = Base64.encodeToString(data, Base64.NO_WRAP)
            val js = """
                (function() {
                    if (window.whatsappWS && window.whatsappWS.readyState === WebSocket.OPEN) {
                        const binary = atob('$base64');
                        const bytes = new Uint8Array(binary.length);
                        for (let i = 0; i < binary.length; i++) {
                            bytes[i] = binary.charCodeAt(i);
                        }
                        window.whatsappWS.send(bytes.buffer);
                        return true;
                    }
                    return false;
                })();
            """.trimIndent()
            
            var result = false
            webView?.evaluateJavascript(js) { value ->
                result = value == "true"
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            false
        }
    }
}
