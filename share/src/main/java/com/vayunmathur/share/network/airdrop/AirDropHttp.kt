package com.vayunmathur.share.network.airdrop

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Just enough HTTP/1.1 for AirDrop: `POST` with a `Content-Length` or chunked body, and
 * keep-alive between `/Discover`, `/Ask` and `/Upload` on one connection.
 *
 * Hand-rolled rather than OkHttp because the server half is needed too, and both halves have
 * to run over sockets bound to a specific network (`mosey0` or Wi-Fi).
 */
object AirDropHttp {

    private const val MAX_LINE = 8192
    private const val MAX_HEADERS = 100

    /** Largest plist body read into memory; `/Upload` is the only big body and is streamed. */
    const val MAX_PLIST_BODY = 4 * 1024 * 1024

    class ProtocolException(message: String) : IOException(message)

    /** Header names are stored lowercased; HTTP header names are case-insensitive. */
    class Headers(private val map: Map<String, String>) {
        operator fun get(name: String): String? = map[name.lowercase()]
        val contentLength: Long? get() = this["content-length"]?.trim()?.toLongOrNull()
        val isChunked: Boolean get() = this["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val keepAlive: Boolean get() = this["connection"]?.equals("close", ignoreCase = true) != true
        /** Snapshot of the parsed headers; keys are lowercased, like [get] expects. */
        fun toMap(): Map<String, String> = LinkedHashMap(map)
        override fun toString(): String = map.toString()
    }

    class Request(val method: String, val path: String, val headers: Headers, val body: InputStream) {
        fun readBody(limit: Int = MAX_PLIST_BODY): ByteArray = readLimited(body, limit)
    }

    class Response(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    ) {
        val isSuccessful: Boolean get() = status in 200..299
    }

    fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 3) throw ProtocolException("bad request line: $line")
        val headers = readHeaders(input)
        return Request(parts[0], parts[1].substringBefore('?'), headers, bodyStream(input, headers, requestBody = true))
    }

    fun readResponse(input: InputStream, limit: Int = MAX_PLIST_BODY): Response {
        val line = readLine(input) ?: throw EOFException("connection closed before response")
        val status = line.split(' ').getOrNull(1)?.toIntOrNull() ?: throw ProtocolException("bad status line: $line")
        val headers = readHeaders(input)
        val raw = bodyStream(input, headers, requestBody = false)
        val decoded = when (headers["content-encoding"]?.lowercase()?.trim()) {
            "gzip" -> GZIPInputStream(raw)
            "deflate" -> InflaterInputStream(raw)
            else -> raw
        }
        return Response(status, headers.toMap(), readLimited(decoded, limit))
    }

    fun writeRequestHead(out: OutputStream, method: String, path: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("$method $path HTTP/1.1\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    fun writeResponse(out: OutputStream, response: Response, keepAlive: Boolean) {
        val sb = StringBuilder("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
        response.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Content-Length: ").append(response.body.size).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(response.body)
        out.flush()
    }

    /** Wraps [out] so writes become HTTP chunks; [ChunkedOutputStream.finish] writes the terminator. */
    class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
        private var finished = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            out.write("${Integer.toHexString(len)}\r\n".toByteArray(Charsets.US_ASCII))
            out.write(b, off, len)
            out.write(CRLF)
        }

        override fun flush() = out.flush()

        fun finish() {
            if (finished) return
            finished = true
            out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        /** Finishes but leaves the socket open for the response. */
        override fun close() = finish()
    }

    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

    private fun bodyStream(input: InputStream, headers: Headers, requestBody: Boolean): InputStream = when {
        headers.isChunked -> ChunkedInputStream(input)
        headers.contentLength != null -> BoundedInputStream(input, headers.contentLength!!)
        // A request with neither has no body; a response with neither runs to close.
        requestBody -> BoundedInputStream(input, 0)
        else -> NonClosingInputStream(input)
    }

    private fun readHeaders(input: InputStream): Headers {
        val map = LinkedHashMap<String, String>()
        repeat(MAX_HEADERS) {
            val line = readLine(input) ?: throw EOFException("headers truncated")
            if (line.isEmpty()) return Headers(map)
            val colon = line.indexOf(':')
            if (colon > 0) map[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        throw ProtocolException("too many headers")
    }

    /** One CRLF- (or LF-) terminated line, or null at a clean EOF before any byte. */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (out.size() == 0) return null
                throw EOFException("line truncated")
            }
            if (b == '\n'.code) break
            if (out.size() >= MAX_LINE) throw ProtocolException("line too long")
            out.write(b)
        }
        val bytes = out.toByteArray()
        val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, len, Charsets.ISO_8859_1)
    }

    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > limit) throw ProtocolException("body exceeds $limit bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Reads and discards whatever is left, so the next request on the connection lines up. */
    fun drain(input: InputStream) {
        val buf = ByteArray(8192)
        while (input.read(buf) >= 0) Unit
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Status"
    }

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private class BoundedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = super.read()
            if (b < 0) throw EOFException("body truncated")
            remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("body truncated")
            remaining -= n
            return n
        }

        override fun close() = Unit
    }

    private class ChunkedInputStream(input: InputStream) : FilterInputStream(input) {
        private var chunkLeft = 0L
        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (chunkLeft == 0L) {
                val sizeLine = readLine(`in`) ?: throw EOFException("chunked body truncated")
                chunkLeft = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                    ?: throw ProtocolException("bad chunk size '$sizeLine'")
                if (chunkLeft == 0L) {
                    // Trailers, if any, end with an empty line.
                    while (!readLine(`in`).isNullOrEmpty()) Unit
                    done = true
                    return -1
                }
            }
            val n = `in`.read(b, off, minOf(len.toLong(), chunkLeft).toInt())
            if (n < 0) throw EOFException("chunk truncated")
            chunkLeft -= n
            if (chunkLeft == 0L) readLine(`in`)
            return n
        }

        override fun close() = Unit
    }
}
