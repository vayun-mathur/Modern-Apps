@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.communicate.data.whatsapp.transport

import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.buildFramedMessage
import com.vayunmathur.communicate.data.whatsapp.buildKeepalive
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.verifyServerCert
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppHandshakeProto
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
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
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Raw-TCP Noise transport to WhatsApp's mobile endpoint (`g.whatsapp.net`), for the **primary
 * client**. Replaces the companion's WebView+WebSocket (`WebViewWebSocket`, which targets
 * `web.whatsapp.com` and is NOT reusable): the mobile protocol runs the Noise_XX handshake directly
 * over a plain TCP socket (Noise provides confidentiality; there is no TLS/WebSocket wrapper).
 *
 * Reuses the proven pieces verbatim: [WhatsAppProtocol.NoiseHandshake], the framing
 * ([WhatsAppProtocol.buildFramedMessage]), frame reassembly ([ingestBytes]), server-cert
 * verification, and the post-handshake [NoiseSocket] (AES-GCM with monotonic counters + a send
 * lock). Only the transport (socket vs WebView) and the [PrimaryClientPayload] differ.
 *
 * ⚠️ Endpoint/port and whether the mobile edge expects raw Noise-over-TCP vs a TLS wrap are the key
 * live-validation unknowns (see plan Phase 4 risks). Host/port are constructor-configurable so the
 * test device can try `:443` then `:5222`.
 */
class WhatsAppSocket(
    private val authData: WhatsAppAuthData,
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
) {
    companion object {
        private const val TAG = "WhatsAppSocket"
        const val DEFAULT_HOST = "g.whatsapp.net"
        const val DEFAULT_PORT = 443
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val KEEPALIVE_INTERVAL_MIN_MS = 20_000L
        private const val KEEPALIVE_INTERVAL_MAX_MS = 30_000L
        private const val KEEPALIVE_MAX_FAIL_MS = 180_000L
        private const val READ_BUFFER = 64 * 1024
        private const val EPHEMERAL_KEY_SIZE = 32
        private const val STATIC_KEY_SIZE = 32
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val GCM_IV_OFFSET = 8
        private const val GCM_IV_LENGTH = 4
        private const val BYTE_MASK = 0xFF
        private const val SHIFT_BYTE2 = 16
        private const val SHIFT_BYTE1 = 8
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile private var isConnected = false
    @Volatile private var isHandshakeComplete = false

    private val _messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val messages: SharedFlow<ByteArray> = _messages.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    sealed interface ConnectionState {
        data object Connecting : ConnectionState
        data object Connected : ConnectionState
        data class Disconnected(val reason: String) : ConnectionState
    }

    private var noiseHandshake: WhatsAppProtocol.NoiseHandshake? = null
    private var noiseSocket: NoiseSocket? = null
    private var ephemeralPrivateKey: ByteArray? = null
    private var readJob: Job? = null
    private var keepaliveJob: Job? = null
    private var serverHeaderReceived = false
    private var recvBuffer = ByteArray(0)
    private val iqCounter = AtomicInt(0)
    @Volatile private var lastKeepaliveSuccess = System.currentTimeMillis()

    // ---------------------------------------------------------------------------- lifecycle

    fun connect() {
        scope.launch {
            _connectionState.emit(ConnectionState.Connecting)
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket = s
                input = s.getInputStream()
                output = s.getOutputStream()
                WhatsAppDiag.log(TAG, "TCP connected $host:$port → starting Noise handshake")
                startNoiseHandshake()
                startReadLoop()
            } catch (expected: Throwable) {
                WhatsAppDiag.log(TAG, "connect failed: ${expected.message}")
                _connectionState.emit(ConnectionState.Disconnected("connect: ${expected.message}"))
            }
        }
    }

    fun disconnect() {
        scope.launch { closeInternal("client disconnect") }
    }

    private suspend fun closeInternal(reason: String) {
        isConnected = false
        isHandshakeComplete = false
        readJob?.cancel()
        keepaliveJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
        serverHeaderReceived = false
        recvBuffer = ByteArray(0)
        noiseHandshake = null
        noiseSocket = null
        _connectionState.emit(ConnectionState.Disconnected(reason))
    }

    // ---------------------------------------------------------------------------- handshake

    private fun startNoiseHandshake() {
        try {
            noiseHandshake = WhatsAppProtocol.NoiseHandshake().apply {
                start(WhatsAppProtocol.NOISE_START_PATTERN, WhatsAppProtocol.WA_CONN_HEADER)
            }
            val (ephPriv, ephPub) = WhatsAppProtocol.generateX25519KeyPair()
            ephemeralPrivateKey = ephPriv
            noiseHandshake?.authenticate(ephPub)

            val clientHello = WhatsAppHandshakeProto.HandshakeMessage.newBuilder()
                .setClientHello(
                    WhatsAppHandshakeProto.HandshakeMessage.ClientHello.newBuilder()
                        .setEphemeral(com.google.protobuf.ByteString.copyFrom(ephPub))
                        .build(),
                )
                .build()
                .toByteArray()

            val framed = WhatsAppProtocol.buildFramedMessage(clientHello, WhatsAppProtocol.WA_CONN_HEADER)
            writeRaw(framed)
            WhatsAppDiag.log(TAG, "→ ClientHello (${clientHello.size}B payload, ${framed.size}B framed)")
        } catch (expected: Exception) {
            WhatsAppDiag.log(TAG, "handshake start failed: ${expected.message}")
            scope.launch { closeInternal("handshake start: ${expected.message}") }
        }
    }

    private fun handleHandshakeMessage(data: ByteArray) {
        try {
            val hm = WhatsAppHandshakeProto.HandshakeMessage.parseFrom(data)
            val serverHello = hm.serverHello
            val handshake = noiseHandshake ?: return
            val ephPriv = ephemeralPrivateKey ?: return

            val serverEphemeral = serverHello.ephemeral.toByteArray()
            val serverStaticCiphertext = serverHello.getStatic().toByteArray()
            val certificateCiphertext = serverHello.payload.toByteArray()
            WhatsAppDiag.log(TAG, "← ServerHello eph=${serverEphemeral.size}" +
                "static=${serverStaticCiphertext.size} cert=${certificateCiphertext.size}")

            if (serverEphemeral.size != EPHEMERAL_KEY_SIZE || serverStaticCiphertext.isEmpty() ||
                certificateCiphertext.isEmpty()
            ) {
                scope.launch { closeInternal("invalid ServerHello") }
                return
            }

            handshake.authenticate(serverEphemeral)
            handshake.mixSharedSecretIntoKey(ephPriv, serverEphemeral)

            val staticDecrypted = handshake.decrypt(serverStaticCiphertext)
            if (staticDecrypted.size != STATIC_KEY_SIZE) {
                scope.launch { closeInternal("bad static len ${staticDecrypted.size}") }
                return
            }
            handshake.mixSharedSecretIntoKey(ephPriv, staticDecrypted)

            val certDecrypted = handshake.decrypt(certificateCiphertext)
            if (!WhatsAppProtocol.verifyServerCert(certDecrypted, staticDecrypted)) {
                WhatsAppDiag.log(TAG, "server cert verification FAILED — aborting")
                scope.launch { closeInternal("cert verify failed") }
                return
            }
            WhatsAppDiag.log(TAG, "server cert verified OK")
            sendClientFinish(handshake, serverEphemeral)
        } catch (expected: Exception) {
            WhatsAppDiag.log(TAG, "handshake processing failed: ${expected.javaClass.simpleName}: ${expected.message}")
            scope.launch { closeInternal("handshake: ${expected.message}") }
        }
    }

    /** ClientFinish with the persisted primary noise static key + primary ClientPayload. */
    private fun sendClientFinish(
        handshake: WhatsAppProtocol.NoiseHandshake,
        serverEphemeral: ByteArray,
    ) {
        // ClientFinish with the persisted primary noise static key + the primary ClientPayload.
        val noisePriv = android.util.Base64.decode(authData.noisePrivateKey, android.util.Base64.NO_WRAP)
        val noisePub = android.util.Base64.decode(authData.noisePublicKey, android.util.Base64.NO_WRAP)

        val encryptedPubkey = handshake.encrypt(noisePub)
        handshake.mixSharedSecretIntoKey(noisePriv, serverEphemeral)

        val payload = PrimaryClientPayload.build(authData)
        val encryptedPayload = handshake.encrypt(payload)

        val clientFinish = WhatsAppHandshakeProto.HandshakeMessage.newBuilder()
            .setClientFinish(
                WhatsAppHandshakeProto.HandshakeMessage.ClientFinish.newBuilder()
                    .setStatic(com.google.protobuf.ByteString.copyFrom(encryptedPubkey))
                    .setPayload(com.google.protobuf.ByteString.copyFrom(encryptedPayload))
                    .build(),
            )
            .build()
            .toByteArray()

        writeRaw(WhatsAppProtocol.buildFramedMessage(clientFinish, null))
        WhatsAppDiag.log(TAG, "→ ClientFinish (payload=${payload.size}B)")

        val (writeKey, readKey) = handshake.finish()
        noiseSocket = NoiseSocket(writeKey, readKey)
        isHandshakeComplete = true
        isConnected = true
        WhatsAppDiag.log(TAG, "Noise handshake COMPLETE")
        scope.launch { _connectionState.emit(ConnectionState.Connected) }
        startKeepalive()
    }

    // ---------------------------------------------------------------------------- read/write

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = scope.launch {
            val buf = ByteArray(READ_BUFFER)
            val ins = input ?: return@launch
            try {
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (n > 0) ingestBytes(buf.copyOfRange(0, n))
                }
            } catch (expected: Exception) {
                if (isConnected || !isHandshakeComplete) {
                    WhatsAppDiag.log(TAG, "read loop ended: ${expected.message}")
                }
            } finally {
                closeInternal("socket closed")
            }
        }
    }

    /**
     * Accumulate incoming bytes and dispatch every complete 3-byte-length-prefixed frame. A single
     * Noise frame can be split across reads (ServerHello carries the full cert chain) and several
     * frames can coalesce, so the stream is reassembled like whatsmeow framesocket.go.
     */
    private suspend fun ingestBytes(chunk: ByteArray) {
        var data = chunk
        if (!serverHeaderReceived) {
            serverHeaderReceived = true
            if (data.size >= WhatsAppProtocol.WA_CONN_HEADER.size &&
                data[0] == 'W'.code.toByte() && data[1] == 'A'.code.toByte()
            ) {
                data = data.copyOfRange(WhatsAppProtocol.WA_CONN_HEADER.size, data.size)
            }
        }
        recvBuffer = if (recvBuffer.isEmpty()) data else recvBuffer + data

        while (recvBuffer.size >= WhatsAppProtocol.FRAME_LENGTH_SIZE) {
            val length = ((recvBuffer[0].toInt() and BYTE_MASK) shl SHIFT_BYTE2) or
                ((recvBuffer[1].toInt() and BYTE_MASK) shl SHIFT_BYTE1) or
                (recvBuffer[2].toInt() and BYTE_MASK)
            if (recvBuffer.size < WhatsAppProtocol.FRAME_LENGTH_SIZE + length) break

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
                noiseSocket?.let { s ->
                    try {
                        _messages.emit(s.decrypt(frame))
                    } catch (expected: Exception) {
                        Log.error(TAG, "frame decrypt failed", expected)
                    }
                }
            }
        }
    }

    private val writeLock = Any()

    private fun writeRaw(data: ByteArray): Boolean {
        return try {
            val out = output ?: return false
            synchronized(writeLock) {
                out.write(data)
                out.flush()
            }
            true
        } catch (expected: Exception) {
            Log.error(TAG, "writeRaw failed", expected)
            false
        }
    }

    // Serializes Noise encrypt + transmit so concurrent senders can't race the write-nonce counter
    // or reorder frames (server rejects with <stream:error><bad-mac>).
    private val sendLock = Any()

    fun send(data: ByteArray): Boolean {
        if (!isConnected || !isHandshakeComplete) return false
        val socket = noiseSocket ?: return false
        return try {
            synchronized(sendLock) {
                val encrypted = socket.encrypt(data)
                writeRaw(WhatsAppProtocol.buildFramedMessage(encrypted, null))
            }
        } catch (expected: Exception) {
            Log.error(TAG, "send failed", expected)
            false
        }
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        lastKeepaliveSuccess = System.currentTimeMillis()
        keepaliveJob = scope.launch {
            while (true) {
                val jitter = Math.random() * (KEEPALIVE_INTERVAL_MAX_MS - KEEPALIVE_INTERVAL_MIN_MS)
                delay(KEEPALIVE_INTERVAL_MIN_MS + jitter.toLong())
                val id = "keepalive-${iqCounter.incrementAndFetch()}"
                val encoded = WhatsAppProtocol.encodeNode(WhatsAppProtocol.buildKeepalive(id))
                if (send(encoded)) {
                    lastKeepaliveSuccess = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - lastKeepaliveSuccess > KEEPALIVE_MAX_FAIL_MS) {
                    WhatsAppDiag.log(TAG, "keepalive failed >${KEEPALIVE_MAX_FAIL_MS}ms — reconnecting")
                    closeInternal("keepalive timeout")
                    return@launch
                }
            }
        }
    }

    /**
     * Post-handshake encrypted socket. No AAD post-handshake; IV = 12 bytes with the counter in the
     * last 4 (big-endian). From whatsmeow/socket/noisesocket.go.
     */
    private class NoiseSocket(
        private val writeKey: javax.crypto.spec.SecretKeySpec,
        private val readKey: javax.crypto.spec.SecretKeySpec,
    ) {
        private var writeCounter: UInt = 0u
        private var readCounter: UInt = 0u

        private fun iv(counter: UInt): ByteArray {
            val iv = ByteArray(GCM_IV_BYTES)
            java.nio.ByteBuffer.wrap(iv, GCM_IV_OFFSET, GCM_IV_LENGTH)
                .order(java.nio.ByteOrder.BIG_ENDIAN).putInt(counter.toInt())
            return iv
        }

        fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                writeKey,
                javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv(writeCounter)))
            val ct = cipher.doFinal(plaintext)
            writeCounter++
            return ct
        }

        fun decrypt(ciphertext: ByteArray): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                readKey,
                javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv(readCounter)))
            val pt = cipher.doFinal(ciphertext)
            readCounter++
            return pt
        }
    }
}
