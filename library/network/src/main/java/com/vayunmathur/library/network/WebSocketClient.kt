package com.vayunmathur.library.network

import android.util.Base64
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Thrown when the HTTP upgrade is answered with something other than 101.
 * [statusCode] is 0 when the status line could not be parsed. Callers branch on
 * it the way okhttp's `onFailure(.., response)` used to (403 = logged out, other
 * 4xx = fatal, 5xx = retryable).
 */
class WebSocketHandshakeException(
    val statusCode: Int,
    message: String,
) : IOException(message)

/**
 * Android-only pure Socket/SSLSocket WebSocket (RFC6455).
 *
 * No OkHttp / Ktor dependency.
 * - Handshake: Sec-WebSocket-Key = base64(random 16 bytes), verify SHA1 accept (lenient log)
 * - Frame codec: TEXT(0x1), BINARY(0x2), CLOSE(0x8), PING(0x9), PONG(0xA), masking on send
 * - Exposes WsSession { send(String/ByteArray), incoming Flow<WsFrame>, close() }
 * - Top-level webSocket(url, headers, block) for OfficeSync WS & CableTunnel WS
 * - TLS: uses explicit factory, else NetworkClient.defaultSslSocketFactory, else system default.
 */
class WebSocketClient private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    val responseHeaders: Map<String, List<String>>,
    val capturedHeaders: Map<String, String> = emptyMap(),
) {
    @Volatile private var closed = false

    /** One WebSocket message or control frame, as decoded by the frame codec. */
    sealed class WsFrame {
        /** A UTF-8 text message. */
        data class Text(val text: String) : WsFrame()

        /** A binary message, payload uninterpreted. */
        data class Binary(val bytes: ByteArray) : WsFrame()

        /** A close frame: RFC 6455 status [code] plus an optional [reason]. */
        data class Close(val code: Int = CLOSE_NORMAL, val reason: String = "") : WsFrame()

        /** A ping control frame; the client auto-replies with [Pong]. */
        object Ping : WsFrame()

        /** A pong control frame, reply to a peer's ping. */
        object Pong : WsFrame()
    }

    /** Blocking read loop exposed as Flow. Cancel coroutine to stop. Auto-replies PING with PONG. */
    fun incomingFlow(): Flow<WsFrame> = flow {
        var open = true
        while (open) {
            open = emitNextFrame()
        }
    }

    /**
     * Reads one frame and emits it. Returns false when the stream is over
     * (close frame seen or read error), true to keep going.
     */
    private suspend fun FlowCollector<WsFrame>.emitNextFrame(): Boolean {
        val frame = try {
            readFrame()
        } catch (e: IOException) {
            if (!closed) Log.dev(TAG, "ws read error ${e.message}")
            return false
        }
        when (frame) {
            is WsFrame.Ping -> {
                try {
                    sendPong()
                } catch (_: Exception) { }
            }
            is WsFrame.Close -> {
                closed = true
                emit(frame)
                return false
            }
            else -> emit(frame)
        }
        return true
    }

    suspend fun send(text: String) = withContext(Dispatchers.IO) {
        if (closed) throw IOException("WebSocket closed")
        val payload = text.toByteArray(Charsets.UTF_8)
        writeFrame(opcode = OPCODE_TEXT, payload = payload, mask = true)
    }

    suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (closed) throw IOException("WebSocket closed")
        writeFrame(opcode = OPCODE_BINARY, payload = bytes, mask = true)
    }

    /**
     * Sends a PING frame. okhttp did this on a timer via `pingInterval`; callers
     * that need keepalive (Signal, Meta MQTT) run their own loop over this.
     */
    suspend fun ping() = withContext(Dispatchers.IO) {
        if (closed) throw IOException("WebSocket closed")
        writeFrame(opcode = OPCODE_PING, payload = ByteArray(0), mask = true)
    }

    /** True once a CLOSE frame was seen or [close] was called. */
    val isClosed: Boolean get() = closed

    suspend fun close(code: Int = CLOSE_NORMAL, reason: String = "") {
        if (closed) return
        closed = true
        try {
            withContext(Dispatchers.IO) {
                val buf = ByteArrayOutputStream()
                buf.write((code shr BYTE_SHIFT) and BYTE_MASK)
                buf.write(code and BYTE_MASK)
                if (reason.isNotEmpty()) buf.write(reason.toByteArray(Charsets.UTF_8))
                runCatching { writeFrame(opcode = OPCODE_CLOSE, payload = buf.toByteArray(), mask = true) }
                runCatching { output.flush() }
                runCatching { socket.close() }
            }
        } catch (_: Exception) { }
    }

    /**
     * Hard, non-blocking teardown: closes the underlying socket immediately WITHOUT
     * the graceful close-frame write. Unlike [close], this can never hang on a
     * half-open socket (a blocking close-frame write into a full send buffer would).
     * Closing the socket unblocks any read or write currently blocked on it, so a
     * keepalive watchdog can use this to break a wedged connection and force a
     * reconnect. Safe to call from any thread; idempotent.
     */
    fun abort() {
        closed = true
        runCatching { socket.close() }
    }

    private fun sendPong() {
        writeFrame(opcode = OPCODE_PONG, payload = ByteArray(0), mask = true)
        output.flush()
    }

    private fun writeFrame(opcode: Int, payload: ByteArray, mask: Boolean) {
        val maskKey = if (mask) {
            val k = ByteArray(MASK_KEY_BYTES)
            SecureRandom().nextBytes(k)
            k
        } else null

        val out = ByteArrayOutputStream()
        out.write((FIN_BIT or opcode) and BYTE_MASK)

        val len = payload.size
        var second = if (mask) MASK_BIT else 0x00
        when {
            len < PAYLOAD_LEN_16 -> {
                second = second or len
                out.write(second)
            }
            len <= EXTENDED_PAYLOAD_16_MAX -> {
                second = second or PAYLOAD_LEN_16
                out.write(second)
                out.write((len shr BYTE_SHIFT) and BYTE_MASK)
                out.write(len and BYTE_MASK)
            }
            else -> {
                second = second or PAYLOAD_LEN_64
                out.write(second)
                val bb = ByteBuffer.allocate(LENGTH_64_BYTES).putLong(len.toLong())
                out.write(bb.array())
            }
        }

        if (maskKey != null) out.write(maskKey)

        if (maskKey != null) {
            for (i in payload.indices) {
                out.write((payload[i].toInt() xor maskKey[i % MASK_KEY_BYTES].toInt()) and BYTE_MASK)
            }
        } else {
            out.write(payload)
        }

        synchronized(output) {
            output.write(out.toByteArray())
            output.flush()
        }
    }

    /** Header fields parsed off the wire before the payload is read. */
    private data class FrameHeader(
        val opcode: Int,
        val payloadLen: Long,
        val maskKey: ByteArray?,
    )

    private fun readFrame(): WsFrame {
        val header = readFrameHeader()
        val payload = readFramePayload(header)
        return decodeFrame(header.opcode, payload)
    }

    private fun readFrameHeader(): FrameHeader {
        val b1 = input.read()
        if (b1 == -1) throw EOFException("ws closed")
        val b2 = input.read()
        if (b2 == -1) throw EOFException("ws closed")

        val opcode = b1 and OPCODE_MASK
        val masked = (b2 and MASK_BIT) != 0
        val payloadLen = readPayloadLength(b2 and PAYLOAD_LEN_MASK)
        val maskKey = if (masked) readExactBytes(MASK_KEY_BYTES, "ws mask EOF") else null
        return FrameHeader(opcode, payloadLen, maskKey)
    }

    private fun readPayloadLength(marker: Int): Long {
        if (marker < PAYLOAD_LEN_16) return marker.toLong()
        if (marker == PAYLOAD_LEN_16) {
            val hi = input.read()
            val lo = input.read()
            if (hi == -1 || lo == -1) throw EOFException("ws len EOF")
            return (hi.toLong() shl BYTE_SHIFT) or lo.toLong()
        }
        val buf = readExactBytes(LENGTH_64_BYTES, "ws len64 EOF")
        return ByteBuffer.wrap(buf).long
    }

    private fun readExactBytes(count: Int, eofMessage: String): ByteArray {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val r = input.read(buf, off, count - off)
            if (r == -1) throw EOFException(eofMessage)
            off += r
        }
        return buf
    }

    private fun readFramePayload(header: FrameHeader): ByteArray {
        if (header.payloadLen > Int.MAX_VALUE) throw IOException("ws frame too large ${header.payloadLen}")
        val payload = readExactBytes(header.payloadLen.toInt(), "ws truncated frame")
        val maskKey = header.maskKey ?: return payload
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor maskKey[i % MASK_KEY_BYTES].toInt()).toByte()
        }
        return payload
    }

    private fun decodeFrame(opcode: Int, payload: ByteArray): WsFrame = when (opcode) {
        OPCODE_TEXT -> WsFrame.Text(payload.toString(Charsets.UTF_8))
        OPCODE_BINARY -> WsFrame.Binary(payload)
        OPCODE_CLOSE -> decodeClose(payload)
        OPCODE_PING -> WsFrame.Ping
        OPCODE_PONG -> WsFrame.Pong
        OPCODE_CONTINUATION -> WsFrame.Text(payload.toString(Charsets.UTF_8))
        else -> WsFrame.Ping
    }

    private fun decodeClose(payload: ByteArray): WsFrame.Close {
        val code = if (payload.size >= CLOSE_CODE_BYTES) {
            ((payload[0].toInt() and BYTE_MASK) shl BYTE_SHIFT) or (payload[1].toInt() and BYTE_MASK)
        } else {
            CLOSE_NORMAL
        }
        val reason = if (payload.size > CLOSE_CODE_BYTES) {
            payload.copyOfRange(CLOSE_CODE_BYTES, payload.size).toString(Charsets.UTF_8)
        } else {
            ""
        }
        return WsFrame.Close(code, reason)
    }

    /** Frame-codec constants and the [webSocket] handshake factory. */
    companion object {
        private const val TAG = "WebSocketClient"
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** RFC 6455 frame codec constants. */
        private const val FIN_BIT = 0x80
        private const val MASK_BIT = 0x80
        private const val OPCODE_MASK = 0x0F
        private const val PAYLOAD_LEN_MASK = 0x7F
        private const val PAYLOAD_LEN_16 = 126
        private const val PAYLOAD_LEN_64 = 127
        private const val EXTENDED_PAYLOAD_16_MAX = 0xFFFF
        private const val BYTE_MASK = 0xFF
        private const val BYTE_SHIFT = 8
        private const val MASK_KEY_BYTES = 4
        private const val LENGTH_64_BYTES = 8
        private const val CLOSE_CODE_BYTES = 2
        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_BINARY = 0x2
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA
        private const val CLOSE_NORMAL = 1000

        /** Handshake constants. */
        private const val WS_KEY_BYTES = 16
        private const val DEFAULT_WSS_PORT = 443
        private const val DEFAULT_WS_PORT = 80
        private const val MAX_HANDSHAKE_HEADER_BYTES = 8192
        private const val HANDSHAKE_ERROR_LINES = 10

        /** Parsed endpoint pieces for one connection attempt. */
        private data class WsEndpoint(
            val uri: URI,
            val scheme: String,
            val host: String,
            val port: Int,
            val path: String,
        )

        /**
         * Opens a WebSocket to [urlStr] with optional [headers] (e.g. Sec-WebSocket-Protocol).
         * Validates Sec-WebSocket-Accept leniently (logs mismatch) per spec.
         * TLS trust: explicit factory wins, else app-wide default from NetworkClient, else system.
         */
        suspend fun connect(
            urlStr: String,
            headers: Map<String, String> = emptyMap(),
            captureResponseHeaders: List<String> = emptyList(),
            sslSocketFactory: SSLSocketFactory? = null,
            useSystemTrust: Boolean = false,
        ): WebSocketClient = withContext(Dispatchers.IO) {
            val endpoint = parseEndpoint(urlStr)
            val sock = openSocket(endpoint, sslSocketFactory, useSystemTrust)
            try {
                val expectedAccept = sendHandshake(sock, endpoint, headers)
                val responseLines = readHandshakeResponse(sock)
                val respHeaders = parseHandshakeHeaders(responseLines)
                checkAccept(respHeaders, expectedAccept)
                val captured = captureHeaders(respHeaders, captureResponseHeaders)
                WebSocketClient(sock, sock.getInputStream(), sock.getOutputStream(), respHeaders.multi, captured)
            } catch (e: IOException) {
                runCatching { sock.close() }
                throw e
            } catch (e: IllegalArgumentException) {
                runCatching { sock.close() }
                throw IOException("ws invalid handshake data: ${e.message}", e)
            }
        }

        /** Response headers in both multi-value and lowercase-single forms. */
        private data class HandshakeHeaders(
            val multi: Map<String, List<String>>,
            val lower: Map<String, String>,
        )

        private fun parseEndpoint(urlStr: String): WsEndpoint {
            val uri = try { URI(urlStr) } catch (_: Exception) { URI(URL(urlStr).toString()) }
            val scheme = uri.scheme?.lowercase() ?: if (urlStr.startsWith("wss")) "wss" else "ws"
            val host = uri.host ?: URL(urlStr).host
            val port = resolvePort(uri, scheme)
            val path = buildString {
                val rp = uri.rawPath
                append(if (rp.isNullOrBlank()) "/" else rp)
                uri.rawQuery?.let { if (it.isNotBlank()) append("?").append(it) }
            }
            return WsEndpoint(uri, scheme, host, port, path)
        }

        private fun resolvePort(uri: URI, scheme: String): Int {
            if (uri.port != -1) return uri.port
            return when (scheme) {
                "wss", "https" -> DEFAULT_WSS_PORT
                else -> DEFAULT_WS_PORT
            }
        }

        private fun openSocket(
            endpoint: WsEndpoint,
            sslSocketFactory: SSLSocketFactory?,
            useSystemTrust: Boolean,
        ): Socket {
            val secure = endpoint.scheme == "wss" || endpoint.scheme == "https"
            val sock: Socket = if (secure) {
                openTlsSocket(endpoint, sslSocketFactory, useSystemTrust)
            } else {
                Socket(endpoint.host, endpoint.port)
            }
            sock.soTimeout = 0
            sock.tcpNoDelay = true
            return sock
        }

        private fun openTlsSocket(
            endpoint: WsEndpoint,
            sslSocketFactory: SSLSocketFactory?,
            useSystemTrust: Boolean,
        ): Socket {
            val defaultFactory = NetworkClient.defaultSslSocketFactory ?: SSLSocketFactory.getDefault()
            val factory: javax.net.SocketFactory =
                if (useSystemTrust) SSLSocketFactory.getDefault() else sslSocketFactory ?: defaultFactory
            val sock = factory.createSocket(endpoint.host, endpoint.port) as Socket
            if (sock is SSLSocket) {
                try { sock.startHandshake() } catch (_: Exception) { }
            }
            return sock
        }

        private fun sendHandshake(sock: Socket, endpoint: WsEndpoint, headers: Map<String, String>): String {
            val keyBytes = ByteArray(WS_KEY_BYTES).also { SecureRandom().nextBytes(it) }
            val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            val expectedAccept = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)),
                Base64.NO_WRAP,
            )

            val out = sock.getOutputStream()
            val writer = out.bufferedWriter(Charsets.US_ASCII)

            val nonDefaultPort = (endpoint.scheme == "wss" && endpoint.port != DEFAULT_WSS_PORT) ||
                (endpoint.scheme == "ws" && endpoint.port != DEFAULT_WS_PORT)
            val hostHeader = if (nonDefaultPort) "${endpoint.host}:${endpoint.port}" else endpoint.host

            writer.write("GET ${endpoint.path} HTTP/1.1\r\n")
            writer.write("Host: $hostHeader\r\n")
            writer.write("Upgrade: websocket\r\n")
            writer.write("Connection: Upgrade\r\n")
            writer.write("Sec-WebSocket-Key: $key\r\n")
            writer.write("Sec-WebSocket-Version: 13\r\n")
            headers.forEach { (k, v) -> writer.write("$k: $v\r\n") }
            writer.write("\r\n")
            writer.flush()
            return expectedAccept
        }

        private fun readHandshakeResponse(sock: Socket): List<String> {
            val rawIn = sock.getInputStream()
            val responseLines = readHeaderLines(rawIn)

            if (responseLines.isEmpty()) throw IOException("ws empty handshake response")
            checkHandshakeStatus(responseLines)
            return responseLines
        }

        private fun readHeaderLines(rawIn: InputStream): List<String> {
            val responseLines = mutableListOf<String>()
            val lineBuf = ByteArrayOutputStream()
            while (true) {
                val r = rawIn.read()
                if (r == -1) throw IOException("ws handshake EOF")
                lineBuf.write(r)
                if (r == '\n'.code) {
                    val line = lineBuf.toString(Charsets.US_ASCII.name()).trim()
                    lineBuf.reset()
                    if (line.isEmpty()) break
                    responseLines.add(line)
                }
                if (lineBuf.size() > MAX_HANDSHAKE_HEADER_BYTES) throw IOException("ws handshake header too long")
            }
            return responseLines
        }

        private fun checkHandshakeStatus(responseLines: List<String>) {
            val statusLine = responseLines.first()
            if (!statusLine.contains("101")) {
                throw WebSocketHandshakeException(
                    statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0,
                    message = buildHandshakeError(statusLine, responseLines),
                )
            }
        }

        private fun buildHandshakeError(statusLine: String, responseLines: List<String>): String {
            return "ws handshake failed: $statusLine; ${responseLines.take(HANDSHAKE_ERROR_LINES)}"
        }

        private fun parseHandshakeHeaders(responseLines: List<String>): HandshakeHeaders {
            val respHeaders = mutableMapOf<String, MutableList<String>>()
            val respHeadersLower = mutableMapOf<String, String>()
            for (i in 1 until responseLines.size) {
                val line = responseLines[i]
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    respHeaders.getOrPut(k) { mutableListOf() }.add(v)
                    respHeadersLower[k.lowercase()] = v
                }
            }
            return HandshakeHeaders(respHeaders, respHeadersLower)
        }

        private fun checkAccept(respHeaders: HandshakeHeaders, expectedAccept: String) {
            val accept = respHeaders.lower["sec-websocket-accept"]
            if (accept == null || accept != expectedAccept) {
                Log.status(TAG, "ws accept mismatch expected=$expectedAccept got=$accept (continuing)")
            }
        }

        private fun captureHeaders(
            respHeaders: HandshakeHeaders,
            captureResponseHeaders: List<String>,
        ): Map<String, String> {
            val captured = mutableMapOf<String, String>()
            for (h in captureResponseHeaders) {
                respHeaders.lower[h.lowercase()]?.let { captured[h] = it }
                respHeaders.multi[h]?.firstOrNull()?.let { captured[h] = it }
            }
            return captured
        }
    }
}

/**
 * Convenience session wrapper exposing Flow frames like Ktor's API did.
 * Used by office OfficeSync and passwords CableTunnel.
 */
class WsSession internal constructor(
    private val client: WebSocketClient,
) {
    val incoming: Flow<WebSocketClient.WsFrame> = client.incomingFlow()
    val responseHeaders: Map<String, List<String>> get() = client.responseHeaders
    val capturedHeader: Map<String, String> get() = client.capturedHeaders

    suspend fun send(text: String) = client.send(text)
    suspend fun send(bytes: ByteArray) = client.send(bytes)

    /** Sends a PING frame. Callers that need keepalive run their own timer loop over this. */
    suspend fun ping() = client.ping()
    suspend fun close() = client.close()

    /** Hard, non-blocking teardown (see [WebSocketClient.abort]); unblocks a wedged read/write. */
    fun abort() = client.abort()
}

suspend fun webSocket(
    url: String,
    headers: Map<String, String> = emptyMap(),
    captureResponseHeaders: List<String> = emptyList(),
    sslSocketFactory: SSLSocketFactory? = null,
    useSystemTrust: Boolean = false,
    block: suspend WsSession.() -> Unit,
) {
    val c = WebSocketClient.connect(url, headers, captureResponseHeaders, sslSocketFactory, useSystemTrust)
    try {
        WsSession(c).block()
    } finally {
        try { c.close() } catch (_: Exception) { }
    }
}
