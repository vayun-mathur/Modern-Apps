package com.vayunmathur.library.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vayunmathur.library.log.Log
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Android-only HTTP engine backed by HttpURLConnection.
 *
 * - withContext(IO) opens (URL(url).openConnection() as HttpURLConnection)
 * - connectTimeout 30000, readTimeout 60000 – both overridable per request
 * - custom verbs via reflection getDeclaredField("method")
 * - headers Map<String, *> where Iterable expands to multiple header lines
 * - body String/ByteArray with setFixedLengthStreamingMode, chunked fallback
 * - when body == null -> doOutput=false, no Content-Type forced (critical for SABR)
 * - manual redirect 301/302/303/307/308 up to 5 hops
 * - Content-Encoding br via org.brotli.dec.BrotliInputStream, gzip via GZIPInputStream,
 *   deflate via InflaterInputStream
 * - TLS: respects per-call sslSocketFactory, else falls back to NetworkClient.defaultSslSocketFactory.
 */
internal object HttpUrlEngine {

    private const val TAG = "HttpUrlEngine"

    const val CONNECT_TIMEOUT = 30_000
    const val READ_TIMEOUT = 60_000
    const val MAX_REDIRECTS = 5
    const val REDIRECT_STATUS_MIN = 301
    const val REDIRECT_STATUS_MAX = 308
    const val STATUS_NOT_MODIFIED = 304
    const val STATUS_SEE_OTHER = 303
    const val STATUS_BAD_REQUEST = 400

    /** Segment size used when draining a body of unknown length. */
    private const val SEGMENT_SIZE = 64 * 1024

    /**
     * Content-Length is attacker-controlled, so it is only trusted as an allocation size up to this
     * much; larger bodies still read in full, just via segments instead of one up-front array.
     */
    private const val MAX_PRESIZE = 1L * 1024 * 1024

    /**
     * Hard cap on bodies decoded to [String] (see [readBodyText]). 16 MB of UTF-8 decodes to at
     * most ~32 MB of chars — comfortably inside the heap — while a runaway 200 MB+ response like
     * the one behind #657 fails fast instead of OOMing mid-read. Normal API bodies are kilobytes.
     */
    const val MAX_TEXT_BODY_BYTES = 16L * 1024 * 1024

    /**
     * Hard cap on bodies returned as [ByteArray] (see [readBody]). Byte bodies
     * (protobuf, images, archives) legitimately run larger than JSON text, so
     * the cap sits above [MAX_TEXT_BODY_BYTES]; anything bigger belongs on the
     * streaming path, not materialised whole in the heap.
     */
    const val MAX_BODY_BYTES = 64L * 1024 * 1024

    private val EMPTY_BYTES = ByteArray(0)

    data class InternalResult(
        val status: Int,
        val statusMessage: String,
        val headers: Map<String, List<String>>,
        val bodyBytes: ByteArray,
        val finalUrl: String,
    )

    /**
     * A response whose body has *not* been read. [stream] is live and already decompressed;
     * closing it (or this) also disconnects the underlying connection.
     */
    class OpenResponse(
        val status: Int,
        val statusMessage: String,
        val headers: Map<String, List<String>>,
        val finalUrl: String,
        val stream: InputStream,
        /** False when the connection exposed no body stream at all. */
        val hasStream: Boolean,
        /** Raw Content-Length header, which describes the *encoded* length. */
        val contentLength: Long?,
        val isIdentityEncoding: Boolean,
    ) : Closeable {
        val isSuccess: Boolean get() = status in 200..299

        override fun close() {
            try { stream.close() } catch (_: Exception) {}
        }
    }

    /** Read failure carrying how far the body got, so the caller can name the endpoint. */
    class BodyReadException(val bytesRead: Long, cause: Throwable) : IOException(cause)

    /** Limit breach carrying how far the body got and the cap it hit. */
    class BodyTooLargeException(val bytesRead: Long, val maxBytes: Long) :
        IOException("Response body of at least $bytesRead bytes exceeds limit of $maxBytes bytes")

    fun openConnection(
        urlString: String,
        method: String,
        headers: Map<String, *>,
        bodyBytes: ByteArray?,
        connectTimeoutMs: Long?,
        readTimeoutMs: Long? = connectTimeoutMs,
        sslSocketFactory: SSLSocketFactory? = null,
    ): HttpURLConnection {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs?.toInt() ?: CONNECT_TIMEOUT
            readTimeout = readTimeoutMs?.toInt() ?: READ_TIMEOUT
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            doOutput = bodyBytes != null
        }

        // Certificate pinning / reduced trust: explicit factory wins, else app-wide default, else system.
        val effectiveFactory = sslSocketFactory ?: NetworkClient.defaultSslSocketFactory
        if (effectiveFactory != null && conn is HttpsURLConnection) {
            conn.sslSocketFactory = effectiveFactory
        }

        try {
            conn.requestMethod = method
        } catch (_: java.net.ProtocolException) {
            setMethodByReflection(conn, method)
        }

        applyHeaders(conn, headers)

        if (bodyBytes != null) {
            writeBody(conn, bodyBytes)
        }

        return conn
    }

    private fun applyHeaders(conn: HttpURLConnection, headers: Map<String, *>) {
        headers.forEach { (k, v) ->
            when (v) {
                is Iterable<*> -> v.forEach { elem ->
                    if (elem != null) conn.addRequestProperty(k, elem.toString())
                }
                else -> if (v != null) conn.setRequestProperty(k, v.toString())
            }
        }
    }

    private fun setMethodByReflection(conn: HttpURLConnection, method: String) {
        var clazz: Class<*>? = conn.javaClass
        var success = false
        while (clazz != null && !success) {
            success = trySetMethodField(clazz, conn, method)
            clazz = clazz.superclass
        }
        if (!success) {
            setDelegateMethod(conn, method)
        }
    }

    private fun trySetMethodField(clazz: Class<*>, conn: HttpURLConnection, method: String): Boolean {
        return try {
            val f = clazz.getDeclaredField("method")
            f.isAccessible = true
            f.set(conn, method)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun setDelegateMethod(conn: HttpURLConnection, method: String) {
        try {
            val delegateField = conn.javaClass.getDeclaredField("delegate")
            delegateField.isAccessible = true
            val delegate = delegateField.get(conn)
            val mf = delegate.javaClass.getDeclaredField("method")
            mf.isAccessible = true
            mf.set(delegate, method)
        } catch (e: NoSuchFieldException) {
            throw IOException("Cannot set custom method $method", e)
        } catch (e: IllegalAccessException) {
            throw IOException("Cannot set custom method $method", e)
        }
    }

    private fun writeBody(conn: HttpURLConnection, bodyBytes: ByteArray) {
        try {
            conn.setFixedLengthStreamingMode(bodyBytes.size)
        } catch (_: IllegalStateException) {
            // Already connected (e.g. a redirect reuses the connection): chunked is the fallback.
            try {
                conn.setChunkedStreamingMode(0)
            } catch (_: IllegalStateException) {
                Log.debug(TAG, "streaming mode already fixed; writing body as-is")
            }
        }
        conn.outputStream.use { it.write(bodyBytes) }
    }

    fun extractHeaders(conn: HttpURLConnection): Map<String, List<String>> {
        return conn.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
    }

    fun maybeDecompress(conn: HttpURLConnection, raw: InputStream?): InputStream? {
        if (raw == null) return null
        val encoding = conn.getHeaderField("Content-Encoding")
            ?: conn.getHeaderField("content-encoding")
            ?: return raw
        val lower = encoding.lowercase()
        return when {
            lower.contains("br") -> try { org.brotli.dec.BrotliInputStream(raw) } catch (_: Throwable) { raw }
            lower.contains("gzip") -> try { GZIPInputStream(raw) } catch (_: Exception) { raw }
            lower.contains("deflate") -> try {
                InflaterInputStream(raw, Inflater(true))
            } catch (_: Exception) { raw }
            else -> raw
        }
    }

    fun toBodyBytes(body: Any?): ByteArray? = when (body) {
        null -> null
        is ByteArray -> body
        is String -> body.toByteArray(Charsets.UTF_8)
        else -> body.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * The single connect -> follow-redirects -> decompress sequence every entry point is built on.
     * Hands back a live body instead of a buffered one so callers can consume incrementally.
     *
     * [wrapConnectErrors] reports a connect failure as `IOException("Failed to connect to ...")`;
     * with it off the original exception propagates unchanged (the streaming callers rely on that).
     */
    suspend fun openResponse(
        url: String,
        method: String,
        headers: Map<String, *>,
        bodyBytes: ByteArray?,
        followRedirects: Boolean,
        connectTimeoutMs: Long?,
        readTimeoutMs: Long? = connectTimeoutMs,
        sslSocketFactory: SSLSocketFactory? = null,
        wrapConnectErrors: Boolean = true,
    ): OpenResponse = withContext(Dispatchers.IO) {
        var currentUrl = url
        var currentMethod = method
        var currentBody = bodyBytes
        var redirects = 0
        var result: OpenResponse? = null

        while (result == null) {
            val conn = openConnection(
                currentUrl, currentMethod, headers, currentBody,
                connectTimeoutMs, readTimeoutMs, sslSocketFactory,
            )
            val status = readStatus(conn, currentUrl, wrapConnectErrors)
            val msg = conn.responseMessage ?: ""
            val respHeaders = extractHeaders(conn)
            val finalUrl = conn.url.toString()

            val redirect = nextRedirect(
                conn,
                currentUrl,
                currentMethod,
                currentBody,
                status,
                followRedirects,
                redirects,
            )
            if (redirect != null) {
                currentUrl = redirect.url
                currentMethod = redirect.method
                currentBody = redirect.body
                redirects = redirect.count
                continue
            }

            result = buildResponse(conn, status, msg, respHeaders, finalUrl)
        }

        result
    }

    private data class RedirectStep(val url: String, val method: String, val body: ByteArray?, val count: Int)

    private fun nextRedirect(
        conn: HttpURLConnection,
        currentUrl: String,
        currentMethod: String,
        currentBody: ByteArray?,
        status: Int,
        followRedirects: Boolean,
        redirects: Int,
    ): RedirectStep? {
        if (!followRedirects) return null
        if (status !in REDIRECT_STATUS_MIN..REDIRECT_STATUS_MAX) return null
        if (status == STATUS_NOT_MODIFIED) return null
        if (redirects >= MAX_REDIRECTS) return null
        val loc = conn.getHeaderField("Location") ?: conn.getHeaderField("location") ?: return null
        val nextUrl = URL(URL(currentUrl), loc).toString()
        try { conn.inputStream?.close() } catch (_: IOException) {}
        conn.disconnect()
        return if (status == STATUS_SEE_OTHER) {
            RedirectStep(nextUrl, "GET", null, redirects + 1)
        } else {
            RedirectStep(nextUrl, currentMethod, currentBody, redirects + 1)
        }
    }

    private fun readStatus(conn: HttpURLConnection, currentUrl: String, wrapConnectErrors: Boolean): Int {
        return try {
            conn.responseCode
        } catch (e: IOException) {
            conn.disconnect()
            if (wrapConnectErrors) {
                throw IOException("Failed to connect to $currentUrl: ${e.message}", e)
            }
            throw e
        }
    }

    private fun buildResponse(
        conn: HttpURLConnection,
        status: Int,
        msg: String,
        respHeaders: Map<String, List<String>>,
        finalUrl: String,
    ): OpenResponse {
        val raw: InputStream? = try {
            if (status >= STATUS_BAD_REQUEST) conn.errorStream ?: conn.inputStream else conn.inputStream
        } catch (_: IOException) { null }

        val encoding = (conn.getHeaderField("Content-Encoding")
            ?: conn.getHeaderField("content-encoding"))?.trim()?.lowercase()
        val identity = encoding.isNullOrEmpty() || encoding == "identity"
        val declaredLength = conn.getHeaderField("Content-Length")?.toLongOrNull()

        val decompressed = maybeDecompress(conn, raw)
        val body: InputStream = if (decompressed == null) {
            conn.disconnect()
            ByteArrayInputStream(EMPTY_BYTES)
        } else {
            DisconnectingStream(conn, decompressed)
        }

        return OpenResponse(
            status, msg, respHeaders, finalUrl, body,
            hasStream = decompressed != null,
            contentLength = declaredLength,
            isIdentityEncoding = identity,
        )
    }

    private class DisconnectingStream(
        private val conn: HttpURLConnection,
        private val wrapped: InputStream,
    ) : InputStream() {
        override fun read(): Int = wrapped.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = wrapped.read(b, off, len)
        override fun available(): Int = wrapped.available()
        override fun close() {
            try { wrapped.close() } catch (_: IOException) {}
            conn.disconnect()
        }
    }

    /**
     * Read a whole body without ever growing an array by doubling.
     *
     * With a usable Content-Length on an unencoded body the result is allocated once at its exact
     * size; otherwise fixed-size segments accumulate and a single exact-size array is assembled at
     * the end. A header that over- or under-states the real length is tolerated, not trusted.
     *
     * [maxBytes] caps the read, counted as bytes arrive so a lying or absent Content-Length
     * cannot defeat it; null (the default) preserves the historical unbounded byte path.
     *
     * Pure in its arguments so it can be unit-tested without a network or a Context.
     */
    fun drainFully(
        input: InputStream,
        contentLengthHint: Long?,
        isIdentityEncoding: Boolean,
        maxBytes: Long? = null,
    ): ByteArray {
        val reader = BodyReader(input, maxBytes)
        // Content-Length describes the encoded body, so it is only a size for identity encoding.
        val hint = if (isIdentityEncoding) contentLengthHint else null
        var presized: ByteArray? = null
        var presizedLen = 0
        if (hint != null && hint > 0 && hint <= MAX_PRESIZE) {
            val exact = ByteArray(hint.toInt())
            presizedLen = reader.fill(exact, 0)
            // Header promised more than the stream delivered.
            if (presizedLen < exact.size) return exact.copyOf(presizedLen)
            presized = exact
        }

        // Whatever remains (the whole body when there was no usable hint, or the excess when the
        // header understated it) accumulates in segments so nothing is ever reallocated. Each
        // segment is allocated only once a byte for it is in hand, so a body that ended exactly
        // where the header said costs nothing extra.
        val tailStart = reader.bytesRead
        val tail = reader.drainTail()
        val tailBytes = reader.bytesRead - tailStart

        if (presized != null && tail.isEmpty()) return presized
        if (presized == null) {
            if (tail.isEmpty()) return EMPTY_BYTES
            if (tail.size == 1) return tail[0]
        }

        return assemble(presized, presizedLen, tail, tailBytes)
    }

    private fun assemble(
        presized: ByteArray?,
        presizedLen: Int,
        segments: List<ByteArray>,
        tailBytes: Long,
    ): ByteArray {
        val total = presizedLen + tailBytes
        if (total > Int.MAX_VALUE) {
            throw IOException("Response body of $total bytes cannot be returned as a single array")
        }
        val out = ByteArray(total.toInt())
        var pos = 0
        if (presized != null) {
            System.arraycopy(presized, 0, out, 0, presizedLen)
            pos = presizedLen
        }
        for (segment in segments) {
            System.arraycopy(segment, 0, out, pos, segment.size)
            pos += segment.size
        }
        return out
    }

    /** Fills caller's buffer from [input], tracking bytes read and naming read failures. */
    private class BodyReader(private val input: InputStream, private val maxBytes: Long? = null) {
        var bytesRead = 0L
            private set

        /** Accounts [n] fresh bytes, failing fast once a set cap is exceeded. */
        private fun account(n: Int) {
            bytesRead += n
            val cap = maxBytes
            if (cap != null && bytesRead > cap) throw BodyTooLargeException(bytesRead, cap)
        }

        // Fills target from [from] until full or EOF; returns how much of it is populated.
        fun fill(target: ByteArray, from: Int): Int {
            var used = from
            while (used < target.size) {
                val n = try {
                    input.read(target, used, target.size - used)
                } catch (e: IOException) {
                    throw BodyReadException(bytesRead, e)
                }
                if (n < 0) break
                used += n
                account(n)
            }
            return used
        }

        fun drainTail(): List<ByteArray> {
            val segments = ArrayList<ByteArray>()
            var tailOpen = true
            while (tailOpen) {
                tailOpen = readTailSegment(segments)
            }
            return segments
        }

        /**
         * Reads one segment. Returns false when the tail is over
         * (EOF, short segment, or read error ends the body).
         */
        private fun readTailSegment(segments: MutableList<ByteArray>): Boolean {
            val lead = try {
                input.read()
            } catch (e: IOException) {
                throw BodyReadException(bytesRead, e)
            }
            if (lead < 0) return false
            account(1)
            val segment = ByteArray(SEGMENT_SIZE)
            segment[0] = lead.toByte()
            val used = fill(segment, 1)
            segments.add(if (used == SEGMENT_SIZE) segment else segment.copyOf(used))
            return used == SEGMENT_SIZE
        }
    }

    /**
     * [drainFully] over an [OpenResponse], naming the endpoint if the read fails.
     *
     * A truncated body is an error here, where it used to be silently reported as an empty one:
     * hiding it left no way to tell which endpoint had failed, which is what #582 asked for.
     *
     * [maxBytes] caps the read exactly like [readBodyText]'s: counted as bytes
     * arrive, never trusted from Content-Length. Null keeps the read uncapped
     * for a caller that has its own bound; the default caps at [MAX_BODY_BYTES].
     */
    fun readBody(response: OpenResponse, maxBytes: Long? = MAX_BODY_BYTES): ByteArray = try {
        drainFully(response.stream, response.contentLength, response.isIdentityEncoding, maxBytes)
    } catch (e: BodyTooLargeException) {
        throw IOException(
            "Response body from ${response.finalUrl} exceeds ${e.maxBytes} bytes after ${e.bytesRead} bytes",
            e,
        )
    } catch (e: BodyReadException) {
        throw IOException(
            "Failed to read response body from ${response.finalUrl} after ${e.bytesRead} bytes",
            e,
        )
    }

    /**
     * [drainFully] decoded as UTF-8, capped at [maxBytes] wire bytes.
     *
     * The bounded replacement for `reader.readText()` on the paths that still have to return a
     * whole body as [String]: the cap is counted during the read, never trusted from
     * Content-Length, and a breach names the endpoint with bytes read instead of OOMing — the
     * unbounded `readText()` behind #657 grew a StringWriter until the 256 MB heap gave out.
     */
    fun readBodyText(response: OpenResponse, maxBytes: Long = MAX_TEXT_BODY_BYTES): String = try {
        drainFully(response.stream, response.contentLength, response.isIdentityEncoding, maxBytes)
            .toString(Charsets.UTF_8)
    } catch (e: BodyTooLargeException) {
        throw IOException(
            "Response body from ${response.finalUrl} exceeds $maxBytes bytes after ${e.bytesRead} bytes",
            e,
        )
    } catch (e: BodyReadException) {
        throw IOException(
            "Failed to read response body from ${response.finalUrl} after ${e.bytesRead} bytes",
            e,
        )
    }

    suspend fun internalExecute(
        url: String,
        method: String,
        headers: Map<String, *>,
        bodyBytes: ByteArray?,
        followRedirects: Boolean,
        connectTimeoutMs: Long?,
        readTimeoutMs: Long? = connectTimeoutMs,
        sslSocketFactory: SSLSocketFactory? = null,
    ): InternalResult = withContext(Dispatchers.IO) {
        val response = openResponse(
            url, method, headers, bodyBytes, followRedirects,
            connectTimeoutMs, readTimeoutMs, sslSocketFactory,
        )
        val bytes = response.use { readBody(it) }
        InternalResult(response.status, response.statusMessage, response.headers, bytes, response.finalUrl)
    }
}
