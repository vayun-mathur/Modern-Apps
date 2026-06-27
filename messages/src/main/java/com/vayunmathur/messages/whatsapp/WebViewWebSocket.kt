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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebView-based WebSocket client for WhatsApp Web.
 *
 * Uses Android WebView (Chromium) for browser-identical TLS fingerprint,
 * bypassing JA3 fingerprinting that blocks OkHttp connections.
 *
 * Implements full Noise_XX_25519_AESGCM_SHA256 handshake including ClientFinish
 * with proper ClientPayload protobuf.
 *
 * Reference: whatsmeow/handshake.go, socket/framesocket.go
 */
class WebViewWebSocket(
    private val context: Context,
    private val authData: WhatsAppAuthData?,
    // Registration key material for the QR-pairing handshake (used when [authData] is null).
    // Carries the noise key pair whose public half is embedded in the QR, plus the companion
    // registration data that must be sent in the ClientPayload's DevicePairingData.
    private val registration: RegistrationData? = null,
) {
    /**
     * Companion-registration key material for the first-pair ClientPayload + handshake.
     * Ref whatsmeow store/clientpayload.go getRegistrationPayload + the QR noise key.
     */
    class RegistrationData(
        val noisePrivateKey: ByteArray,
        val noisePublicKey: ByteArray,
        val registrationId: Int,
        val identityPublicKey: ByteArray,
        val signedPreKeyId: Int,
        val signedPreKeyPublic: ByteArray,
        val signedPreKeySignature: ByteArray,
    )
    private companion object {
        const val TAG = "WebViewWebSocket"
        const val KEEPALIVE_INTERVAL_MIN_MS = 20_000L
        const val KEEPALIVE_INTERVAL_MAX_MS = 30_000L
        const val KEEPALIVE_RESPONSE_DEADLINE_MS = 10_000L
        const val KEEPALIVE_MAX_FAIL_MS = 180_000L
        const val RECONNECT_DELAY_MS = 5_000L
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
    private var noiseSocket: NoiseSocket? = null
    private var isHandshakeComplete = false
    private var handshakeJob: Job? = null
    private var keepaliveJob: Job? = null
    private var ephemeralPrivateKey: ByteArray? = null
    private var ephemeralPublicKey: ByteArray? = null
    private var scriptInjected = false
    private var serverHeaderReceived = false
    // Reassembly buffer for the incoming frame stream. A single Noise frame can be split
    // across multiple WebSocket messages (the ServerHello carries the full cert chain) and
    // several frames can coalesce into one, so we cannot assume 1 message == 1 frame.
    private var recvBuffer = ByteArray(0)
    private val iqCounter = AtomicInteger(0)
    private var reconnectJob: Job? = null
    private var lastKeepaliveSuccess = System.currentTimeMillis()

    private inner class WebSocketBridge {
        @JavascriptInterface
        fun onOpen() {
            WhatsAppDiag.log(TAG, "WS opened → starting Noise handshake")
            startNoiseHandshake()
        }

        @JavascriptInterface
        fun onMessage(data: String) {
            try {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                scope.launch { ingestBytes(bytes) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode message", e)
            }
        }

        @JavascriptInterface
        fun onClose(code: Int, reason: String) {
            WhatsAppDiag.log(TAG, "WS CLOSED code=$code reason='${reason}' (handshakeComplete=$isHandshakeComplete)")
            isConnected = false
            // Reconnect is owned by WhatsAppClient (it observes Disconnected and
            // rebuilds the socket with backoff); don't self-reconnect here too.
            scope.launch { _connectionState.emit(ConnectionState.Disconnected("Closed: $reason")) }
        }

        @JavascriptInterface
        fun onError(error: String) {
            WhatsAppDiag.log(TAG, "WS ERROR: $error")
            scope.launch { _connectionState.emit(ConnectionState.Disconnected("Error: $error")) }
        }

        @JavascriptInterface
        fun onLog(message: String) {
            Log.d(TAG, "JS: $message")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun connect() {
        Log.i(TAG, "Starting WebView WebSocket connection to ${WhatsAppProtocol.WS_URL}")
        scope.launch {
            _connectionState.emit(ConnectionState.Connecting)
            withContext(Dispatchers.Main) {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                    addJavascriptInterface(WebSocketBridge(), "Android")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (!scriptInjected) {
                                scriptInjected = true
                                injectWebSocketScript()
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?, errorCode: Int, description: String?, failingUrl: String?
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            Log.e(TAG, "WebView error: $errorCode $description")
                            scope.launch {
                                _connectionState.emit(ConnectionState.Disconnected("WebView error: $description"))
                            }
                        }
                    }

                    loadDataWithBaseURL(
                        "https://web.whatsapp.com",
                        "<html><body></body></html>",
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            }
        }
    }

    private fun injectWebSocketScript() {
        val js = """
            (function() {
                Android.onLog('Injecting WebSocket script');
                try {
                    const ws = new WebSocket('${WhatsAppProtocol.WS_URL}');
                    ws.binaryType = 'arraybuffer';
                    ws.onopen = function() {
                        Android.onLog('WebSocket opened');
                        Android.onOpen();
                    };
                    ws.onmessage = function(event) {
                        if (event.data instanceof ArrayBuffer) {
                            const bytes = new Uint8Array(event.data);
                            let binary = '';
                            for (let i = 0; i < bytes.length; i++) {
                                binary += String.fromCharCode(bytes[i]);
                            }
                            Android.onMessage(btoa(binary));
                        }
                    };
                    ws.onclose = function(event) {
                        Android.onClose(event.code, event.reason || '');
                    };
                    ws.onerror = function() {
                        Android.onError('WebSocket error');
                    };
                    window.whatsappWS = ws;
                } catch (e) {
                    Android.onError('Exception: ' + e.message);
                }
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js, null)
    }

    fun disconnect() {
        reconnectJob?.cancel()
        scope.launch {
            withContext(Dispatchers.Main) {
                webView?.evaluateJavascript("if (window.whatsappWS) window.whatsappWS.close();", null)
                webView?.destroy()
                webView = null
            }
            isConnected = false
            isHandshakeComplete = false
            scriptInjected = false
            serverHeaderReceived = false
            recvBuffer = ByteArray(0)
            noiseHandshake = null
            noiseSocket = null
            handshakeJob?.cancel()
            keepaliveJob?.cancel()
            _connectionState.emit(ConnectionState.Disconnected("Client disconnect"))
        }
    }

    private fun startNoiseHandshake() {
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            try {
                noiseHandshake = WhatsAppProtocol.NoiseHandshake().apply {
                    start(WhatsAppProtocol.NOISE_START_PATTERN, WhatsAppProtocol.WA_CONN_HEADER)
                }

                val (ephPriv, ephPub) = WhatsAppProtocol.generateX25519KeyPair()
                ephemeralPrivateKey = ephPriv
                ephemeralPublicKey = ephPub

                noiseHandshake?.authenticate(ephPub)

                val handshakeMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.newBuilder()
                    .setClientHello(
                        com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.ClientHello.newBuilder()
                            .setEphemeral(com.google.protobuf.ByteString.copyFrom(ephPub))
                            .build()
                    )
                    .build()
                val clientHelloBytes = handshakeMessage.toByteArray()

                val framedMessage = WhatsAppProtocol.buildFramedMessage(clientHelloBytes, WhatsAppProtocol.WA_CONN_HEADER)
                sendRaw(framedMessage)

                WhatsAppDiag.log(TAG, "→ ClientHello sent (${clientHelloBytes.size}B payload, ${framedMessage.size}B framed)")
            } catch (e: Exception) {
                Log.e(TAG, "Handshake failed", e)
                scope.launch { _connectionState.emit(ConnectionState.Disconnected("Handshake failed: ${e.message}")) }
                disconnect()
            }
        }
    }

    private fun handleHandshakeMessage(data: ByteArray) {
        try {
            val handshakeMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.parseFrom(data)
            val serverHello = handshakeMessage.serverHello
            if (serverHello == null) {
                Log.e(TAG, "ServerHello is null")
                scope.launch { _connectionState.emit(ConnectionState.Disconnected("Invalid ServerHello")) }
                disconnect()
                return
            }

            val handshake = noiseHandshake ?: return
            val ephPriv = ephemeralPrivateKey ?: return

            val serverEphemeral = serverHello.ephemeral.toByteArray()
            val serverStaticCiphertext = serverHello.getStatic().toByteArray()
            val certificateCiphertext = serverHello.payload.toByteArray()
            WhatsAppDiag.log(TAG, "← ServerHello eph=${serverEphemeral.size}B static=${serverStaticCiphertext.size}B cert=${certificateCiphertext.size}B")

            if (serverEphemeral.size != 32 || serverStaticCiphertext.isEmpty() || certificateCiphertext.isEmpty()) {
                Log.e(TAG, "Invalid ServerHello: missing fields")
                disconnect()
                return
            }

            // Process ServerHello (whatsmeow/handshake.go lines 65-87)
            handshake.authenticate(serverEphemeral)
            handshake.mixSharedSecretIntoKey(ephPriv, serverEphemeral)

            val staticDecrypted = handshake.decrypt(serverStaticCiphertext)
            if (staticDecrypted.size != 32) {
                Log.e(TAG, "Invalid static key length: ${staticDecrypted.size}")
                disconnect()
                return
            }

            handshake.mixSharedSecretIntoKey(ephPriv, staticDecrypted)

            val certDecrypted = handshake.decrypt(certificateCiphertext)
            Log.d(TAG, "Server cert decrypted (${certDecrypted.size} bytes)")
            if (!WhatsAppProtocol.verifyServerCert(certDecrypted, staticDecrypted)) {
                WhatsAppDiag.log(TAG, "Server cert verification FAILED — aborting")
                disconnect()
                return
            }
            WhatsAppDiag.log(TAG, "Server cert verified OK")

            // Send ClientFinish (whatsmeow/handshake.go lines 89-119). The static (noise) key must
            // match the one advertised: the saved key when logging in, or the provisioning key whose
            // public half is in the QR when first pairing. Generating a fresh key here would make the
            // server/phone reject the companion.
            val noiseKeyPair = when {
                authData != null -> Pair(
                    Base64.decode(authData.noisePrivateKey, Base64.NO_WRAP),
                    Base64.decode(authData.noisePublicKey, Base64.NO_WRAP)
                )
                registration != null -> Pair(registration.noisePrivateKey, registration.noisePublicKey)
                else -> WhatsAppProtocol.generateX25519KeyPair()
            }
            val (noisePriv, noisePub) = noiseKeyPair

            val encryptedPubkey = handshake.encrypt(noisePub)
            handshake.mixSharedSecretIntoKey(noisePriv, serverEphemeral)

            val clientPayloadBytes = buildClientPayload()
            val encryptedPayload = handshake.encrypt(clientPayloadBytes)

            val clientFinishMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.newBuilder()
                .setClientFinish(
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppHandshakeProto.HandshakeMessage.ClientFinish.newBuilder()
                        .setStatic(com.google.protobuf.ByteString.copyFrom(encryptedPubkey))
                        .setPayload(com.google.protobuf.ByteString.copyFrom(encryptedPayload))
                        .build()
                )
                .build()
            val clientFinishBytes = clientFinishMessage.toByteArray()
            val framedFinish = WhatsAppProtocol.buildFramedMessage(clientFinishBytes, null)
            sendRaw(framedFinish)

            val mode = if (authData != null) "login" else if (registration != null) "register/pair" else "anon"
            WhatsAppDiag.log(TAG, "→ ClientFinish sent ($mode, payload=${clientPayloadBytes.size}B, framed=${framedFinish.size}B)")

            // Derive final encryption keys
            val (writeKey, readKey) = handshake.finish()
            noiseSocket = NoiseSocket(writeKey, readKey)
            isHandshakeComplete = true
            isConnected = true

            WhatsAppDiag.log(TAG, "Noise handshake COMPLETE — awaiting server stanzas")
            scope.launch { _connectionState.emit(ConnectionState.Connected) }
            startKeepalive()

        } catch (e: Exception) {
            WhatsAppDiag.log(TAG, "Handshake processing FAILED: ${e.javaClass.simpleName}: ${e.message}")
            scope.launch { _connectionState.emit(ConnectionState.Disconnected("Handshake failed: ${e.message}")) }
            disconnect()
        }
    }

    private fun buildClientPayload(): ByteArray {
        val versionProto = com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.UserAgent.AppVersion.newBuilder()
            .setPrimary(WhatsAppProtocol.WA_VERSION[0])
            .setSecondary(WhatsAppProtocol.WA_VERSION[1])
            .setTertiary(WhatsAppProtocol.WA_VERSION[2])
            .build()

        val userAgent = com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.UserAgent.newBuilder()
            .setPlatform(com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.UserAgent.Platform.WEB)
            .setReleaseChannel(com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.UserAgent.ReleaseChannel.RELEASE)
            .setAppVersion(versionProto)
            .setMcc("000")
            .setMnc("000")
            .setOsVersion("0.1")
            .setManufacturer("")
            .setDevice("Desktop")
            .setOsBuildNumber("0.1")
            .setLocaleLanguageIso6391("en")
            .setLocaleCountryIso31661Alpha2("US")
            .build()

        val webInfo = com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.WebInfo.newBuilder()
            .setWebSubPlatform(com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.WebInfo.WebSubPlatform.WEB_BROWSER)
            .build()

        val payloadBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.newBuilder()
            .setUserAgent(userAgent)
            .setWebInfo(webInfo)
            .setConnectType(com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.ConnectType.WIFI_UNKNOWN)
            .setConnectReason(com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.ConnectReason.USER_ACTIVATED)

        if (authData != null) {
            // JID is <user>.<agent>:<device>@server (e.g. 16503988058.0:3). The username is the
            // numeric phone only — strip both the :device and the .agent suffixes, otherwise
            // toLongOrNull() fails on the "." and we send username=0, which the server rejects.
            val widUser = authData.wid.substringBefore("@").substringBefore(":").substringBefore(".")
            payloadBuilder.username = widUser.toLongOrNull() ?: 0L
            payloadBuilder.device = authData.deviceId
            payloadBuilder.passive = true
            payloadBuilder.pull = true
            payloadBuilder.lidDbMigrated = true
            payloadBuilder.lc = 1
        } else if (registration != null) {
            // First-pair registration payload (whatsmeow store/clientpayload.go getRegistrationPayload).
            val regId = ByteArray(4).also { java.nio.ByteBuffer.wrap(it).putInt(registration.registrationId) }
            val skeyId4 = ByteArray(4).also { java.nio.ByteBuffer.wrap(it).putInt(registration.signedPreKeyId) }
            val buildHash = java.security.MessageDigest.getInstance("MD5")
                .digest(WhatsAppProtocol.WA_VERSION.joinToString(".").toByteArray(Charsets.UTF_8))
            val deviceProps = com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.DeviceProps.newBuilder()
                .setOs("whatsmeow")
                .setVersion(
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.DeviceProps.AppVersion.newBuilder()
                        .setPrimary(0).setSecondary(1).setTertiary(0)
                )
                .setPlatformType(com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.DeviceProps.PlatformType.UNKNOWN)
                .setRequireFullSync(false)
                .build()
                .toByteArray()
            val pairing = com.vayunmathur.messages.whatsapp.proto.WhatsAppPayloadProto.ClientPayload.DevicePairingRegistrationData.newBuilder()
                .setERegid(com.google.protobuf.ByteString.copyFrom(regId))
                .setEKeytype(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x05)))
                .setEIdent(com.google.protobuf.ByteString.copyFrom(registration.identityPublicKey))
                .setESkeyId(com.google.protobuf.ByteString.copyFrom(skeyId4.copyOfRange(1, 4)))
                .setESkeyVal(com.google.protobuf.ByteString.copyFrom(registration.signedPreKeyPublic))
                .setESkeySig(com.google.protobuf.ByteString.copyFrom(registration.signedPreKeySignature))
                .setBuildHash(com.google.protobuf.ByteString.copyFrom(buildHash))
                .setDeviceProps(com.google.protobuf.ByteString.copyFrom(deviceProps))
                .build()
            payloadBuilder.devicePairingData = pairing
            payloadBuilder.passive = false
            payloadBuilder.pull = false
        } else {
            payloadBuilder.passive = false
            payloadBuilder.pull = false
        }

        return payloadBuilder.build().toByteArray()
    }

    /**
     * Accumulate incoming WebSocket bytes and dispatch every complete length-prefixed frame.
     * WhatsApp can split a single Noise frame across multiple WebSocket messages (the
     * ServerHello carries the full cert chain) or coalesce several frames into one, so the
     * stream must be reassembled like whatsmeow/socket/framesocket.go processData().
     */
    private suspend fun ingestBytes(chunk: ByteArray) {
        var data = chunk
        WhatsAppDiag.log(TAG, "← WS msg ${WhatsAppDiag.preview(chunk)} (buffered=${recvBuffer.size}, hsDone=$isHandshakeComplete)")
        // The connection header is only ever prepended once, at the very start of the stream.
        if (!serverHeaderReceived) {
            serverHeaderReceived = true
            if (data.size >= WhatsAppProtocol.WA_CONN_HEADER.size &&
                data[0] == 'W'.code.toByte() && data[1] == 'A'.code.toByte()) {
                data = data.copyOfRange(WhatsAppProtocol.WA_CONN_HEADER.size, data.size)
            }
        }
        recvBuffer = if (recvBuffer.isEmpty()) data else recvBuffer + data

        while (recvBuffer.size >= WhatsAppProtocol.FRAME_LENGTH_SIZE) {
            val length = ((recvBuffer[0].toInt() and 0xFF) shl 16) or
                    ((recvBuffer[1].toInt() and 0xFF) shl 8) or
                    (recvBuffer[2].toInt() and 0xFF)
            if (recvBuffer.size < WhatsAppProtocol.FRAME_LENGTH_SIZE + length) {
                WhatsAppDiag.log(TAG, "  … partial frame: need ${length}B, have ${recvBuffer.size - WhatsAppProtocol.FRAME_LENGTH_SIZE}B, waiting")
                break
            }

            val frame = recvBuffer.copyOfRange(
                WhatsAppProtocol.FRAME_LENGTH_SIZE,
                WhatsAppProtocol.FRAME_LENGTH_SIZE + length,
            )
            recvBuffer = recvBuffer.copyOfRange(
                WhatsAppProtocol.FRAME_LENGTH_SIZE + length,
                recvBuffer.size,
            )

            if (!isHandshakeComplete) {
                handleHandshakeMessage(frame)
            } else {
                noiseSocket?.let { socket ->
                    try {
                        _messages.emit(socket.decrypt(frame))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decrypt frame", e)
                    }
                }
            }
        }
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

            val wv = webView ?: return false
            // Marshal onto the main thread. evaluateJavascript must run on the WebView's
            // thread, but this WebView is headless (never attached to a window), so View.post
            // would queue forever. scope uses Dispatchers.Main (the main Looper), which always
            // runs regardless of view attachment.
            scope.launch { wv.evaluateJavascript(js, null) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send raw", e)
            false
        }
    }

    fun send(data: ByteArray): Boolean {
        if (!isConnected || !isHandshakeComplete) return false
        val socket = noiseSocket ?: return false

        return try {
            val encrypted = socket.encrypt(data)
            val framed = WhatsAppProtocol.buildFramedMessage(encrypted, null)
            sendRaw(framed)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send encrypted", e)
            false
        }
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        lastKeepaliveSuccess = System.currentTimeMillis()
        keepaliveJob = scope.launch {
            while (true) {
                delay(KEEPALIVE_INTERVAL_MIN_MS + (Math.random() * (KEEPALIVE_INTERVAL_MAX_MS - KEEPALIVE_INTERVAL_MIN_MS)).toLong())
                val id = "keepalive-${iqCounter.incrementAndGet()}"
                val keepaliveNode = WhatsAppProtocol.buildKeepalive(id)
                val encoded = WhatsAppProtocol.encodeNode(keepaliveNode)
                val sent = send(encoded)
                if (!sent) {
                    WhatsAppDiag.log(TAG, "keepalive send FAILED ($id)")
                    if (System.currentTimeMillis() - lastKeepaliveSuccess > KEEPALIVE_MAX_FAIL_MS) {
                        WhatsAppDiag.log(TAG, "keepalive failed >${KEEPALIVE_MAX_FAIL_MS}ms, forcing reconnect")
                        disconnect()
                        return@launch
                    }
                } else {
                    WhatsAppDiag.log(TAG, "keepalive sent ($id)")
                    lastKeepaliveSuccess = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * Post-handshake encrypted socket. No AAD post-handshake.
     * IV: 12 bytes with counter in last 4 bytes (big-endian).
     * From whatsmeow/socket/noisesocket.go
     */
    private inner class NoiseSocket(
        private val writeKey: javax.crypto.spec.SecretKeySpec,
        private val readKey: javax.crypto.spec.SecretKeySpec,
    ) {
        private var writeCounter: UInt = 0u
        private var readCounter: UInt = 0u

        private fun generateIV(counter: UInt): ByteArray {
            val iv = ByteArray(12)
            java.nio.ByteBuffer.wrap(iv, 8, 4)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .putInt(counter.toInt())
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
