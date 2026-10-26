package com.vayunmathur.library.network

import android.content.Context
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.serializer
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.URL
import javax.net.ssl.SSLSocketFactory

/**
 * Android-only HTTP client – HttpURLConnection only, no Ktor/OkHttp.
 *
 * Hardened with reduced CA bundles via [TrustBundle] + [BundledTrust].
 * Call [init] early (Application.onCreate or MainActivity.onCreate) to pin to a minimal root set.
 * Email/web/vpn use SYSTEM (platform default) because they contact arbitrary user hosts / browser URLs.
 *
 * Public API binary-compatible:
 *  SimpleResponse(status,statusMessage,body,headers,url){ isSuccess, contentLength }
 *  RawResponse(status,statusMessage,bytes,headers,url){ isSuccess, text, header(), headerValues() }
 *  NetworkDataStream { suspend read(buffer[,offset,length]), isClosedForRead }
 *  performRequestBytes / Full / performRequest / execute / stream / getContentLength /
 *  performRequestInputStream / callJson / getJson via kotlinx-serialization-json
 */
data class SimpleResponse(
    val status: Int,
    val statusMessage: String,
    val body: String,
    val headers: Map<String, List<String>>,
    val url: String,
) {
    val isSuccess: Boolean get() = status in 200..299
    val contentLength: Long? get() = headers.entries.firstOrNull {
        it.key.equals("Content-Length", ignoreCase = true)
    }?.value?.firstOrNull()?.toLongOrNull()

    /** Case-insensitive single-value header lookup. */
    fun header(name: String): String? = headerValues(name).firstOrNull()

    /** Case-insensitive all-values header lookup (e.g. multiple Set-Cookie). */
    fun headerValues(name: String): List<String> =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()
}

/**
 * Byte-oriented response. Use instead of [SimpleResponse] when the body is
 * binary (protobuf, images, ciphertext) and a UTF-8 round-trip would corrupt it.
 */
class RawResponse(
    val status: Int,
    val statusMessage: String,
    val bytes: ByteArray,
    val headers: Map<String, List<String>>,
    val url: String,
) {
    val isSuccess: Boolean get() = status in 200..299

    /** The body decoded as UTF-8. Only meaningful for textual responses. */
    val text: String get() = bytes.toString(Charsets.UTF_8)

    /** Case-insensitive single-value header lookup. */
    fun header(name: String): String? = headerValues(name).firstOrNull()

    /** Case-insensitive all-values header lookup (e.g. multiple Set-Cookie). */
    fun headerValues(name: String): List<String> =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()
}

interface NetworkDataStream {
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)
    val isClosedForRead: Boolean
}

/** Adapts already-buffered bytes to [NetworkDataStream] so the same framing/parsing
 *  code can run over a live stream or a buffered body. */
fun ByteArray.asNetworkDataStream(): NetworkDataStream {
    val src = this
    return object : NetworkDataStream {
        private var pos = 0
        override val isClosedForRead: Boolean get() = pos >= src.size
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= src.size) return -1
            val n = minOf(length, src.size - pos)
            System.arraycopy(src, pos, buffer, offset, n)
            pos += n
            return n
        }
    }
}

object NetworkClient {

    private const val TAG = "NetworkClient"

    /**
     * How much of a non-success body [stream] keeps for the error message. Error bodies are
     * diagnostics, not payloads, so they are read as a bounded prefix rather than in full.
     */
    private const val ERROR_PREFIX_BYTES = 64 * 1024

    /** HTTP 206 Partial Content — streamable like a success. */
    private const val STATUS_PARTIAL_CONTENT = 206

    /** The JSON path only quotes 500 characters, so it needs far less than [ERROR_PREFIX_BYTES]. */
    private const val JSON_ERROR_PREFIX_BYTES = 4 * 1024

    /** HTTP 204 No Content — never has a body to parse. */
    private const val STATUS_NO_CONTENT = 204

    /** Characters of an error body quoted into an IOException message. */
    private const val JSON_ERROR_MESSAGE_CHARS = 500

    // Published API – accessible from public inline functions.
    @PublishedApi
    internal val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    @Volatile
    var defaultSslSocketFactory: SSLSocketFactory? = null
        private set

    /**
     * The trust manager behind [defaultSslSocketFactory], retained because pooled clients
     * (OkHttp, issue 815) need the manager alongside the factory at construction time.
     * Null exactly when the factory is null (SYSTEM trust).
     */
    @Volatile
    var defaultTrustManager: javax.net.ssl.X509TrustManager? = null
        private set

    @Volatile
    var currentBundle: TrustBundle = TrustBundle.SYSTEM
        private set

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context, bundle: TrustBundle) {
        val appCtx = context.applicationContext
        // Allow re-init with different bundle (useful for tests) but avoid redundant work.
        if (isAlreadyInitialized(bundle)) {
            return
        }
        // Total: BundledTrust.createFactory catches its own failures and returns null,
        // so there is nothing left here that needs a catch-all.
        applyBundle(appCtx, bundle)
        initialized = true
    }

    private fun isAlreadyInitialized(bundle: TrustBundle): Boolean {
        if (!initialized || bundle != currentBundle) return false
        if (bundle == TrustBundle.SYSTEM) return true
        return defaultSslSocketFactory != null
    }

    private fun applyBundle(appCtx: Context, bundle: TrustBundle) {
        currentBundle = bundle
        if (bundle == TrustBundle.SYSTEM) {
            defaultSslSocketFactory = null
            defaultTrustManager = null
            Log.status(TAG, "Initialized with SYSTEM bundle (platform default trust)")
            return
        }
        val result = BundledTrust.createFactory(appCtx, bundle)
        defaultSslSocketFactory = result?.first
        defaultTrustManager = result?.second
        if (defaultSslSocketFactory == null) {
            Log.status(TAG, "Bundle $bundle produced no factory (missing DERs?), falling back to system trust")
        } else {
            Log.status(TAG, "Initialized with bundle $bundle")
        }
    }

    @Synchronized
    fun initWithFactory(factory: SSLSocketFactory?, bundle: TrustBundle = TrustBundle.SYSTEM) {
        defaultSslSocketFactory = factory
        // A bare factory without its manager cannot back a pooled client; the pool falls back
        // to system trust until init() supplies the pair.
        defaultTrustManager = null
        currentBundle = bundle
        initialized = true
    }

    private fun resolveFactory(
        explicit: SSLSocketFactory?,
        useSystemTrust: Boolean,
    ): SSLSocketFactory? {
        if (useSystemTrust) return null
        return explicit ?: defaultSslSocketFactory
    }

    /** Reads at most [maxBytes] as UTF-8; a failed read just shortens the diagnostic. */
    private fun readErrorPrefix(input: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(maxBytes)
        var used = 0
        try {
            while (used < buffer.size) {
                val n = input.read(buffer, used, buffer.size - used)
                if (n < 0) break
                used += n
            }
        } catch (_: java.io.IOException) {
        }
        return String(buffer, 0, used, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------
    // Bytes / Full / SimpleResponse
    // ------------------------------------------------------------------

    suspend fun performRequestBytes(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): Pair<Int, ByteArray> {
        val r = HttpUrlEngine.internalExecute(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects = true, connectTimeoutMs = null,
            sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
        )
        return r.status to r.bodyBytes
    }

    suspend fun performRequestBytesFull(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): Triple<Int, Map<String, List<String>>, ByteArray> {
        val r = HttpUrlEngine.internalExecute(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects = true, connectTimeoutMs = null,
            sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
        )
        return Triple(r.status, r.headers, r.bodyBytes)
    }

    suspend fun performRequest(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): SimpleResponse {
        // The body is decoded straight off the socket here rather than after returning, so the
        // (potentially hundreds of MB) UTF-8 conversion never lands on the caller's dispatcher —
        // the main thread, for Compose callers.
        return withContext(Dispatchers.IO) {
            val response = HttpUrlEngine.openResponse(
                url, method, headers, HttpUrlEngine.toBodyBytes(body),
                followRedirects = true, connectTimeoutMs = null,
                sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
            )
            response.use {
                // Bounded like the byte path (#582): readText() grew a StringWriter with no cap
                // until the heap gave out (#657), so count bytes during the read and fail fast.
                val text = try {
                    HttpUrlEngine.readBodyText(it)
                } catch (e: java.io.IOException) {
                    throw java.io.IOException("Failed to read response body from ${it.finalUrl}", e)
                }
                SimpleResponse(it.status, it.statusMessage, text, it.headers, it.finalUrl)
            }
        }
    }

    /**
     * Full-control buffered request: binary body, per-request timeouts and
     * optional redirect suppression. Does not throw on 4xx/5xx — inspect
     * [RawResponse.status].
     *
     * Served by the pooled OkHttp engine ([PooledHttpEngine], issue 815) unless its kill-switch
     * is off or the call pins a bare factory the pool cannot share — both fall back to the
     * historical `HttpURLConnection` path with identical semantics.
     *
     * [sslSocketFactory] pins the TLS trust anchors for this call (Signal uses
     * a factory built over its bundled root); null keeps the platform default or
     * the bundle default set via [init]. Use [useSystemTrust]=true to force system
     * trust for dynamic hosts (email custom IMAP hostnames, vpn user endpoint).
     */
    suspend fun execute(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        followRedirects: Boolean = true,
        connectTimeoutMs: Long? = null,
        readTimeoutMs: Long? = connectTimeoutMs,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): RawResponse {
        if (PooledHttpEngine.USE_POOLED_ENGINE && PooledHttpEngine.canServe(sslSocketFactory)) {
            return PooledHttpEngine.execute(
                url, method, headers, body, followRedirects,
                connectTimeoutMs, readTimeoutMs, useSystemTrust,
            )
        }
        val r = HttpUrlEngine.internalExecute(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects, connectTimeoutMs, readTimeoutMs,
            sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
        )
        return RawResponse(r.status, r.statusMessage, r.bodyBytes, r.headers, r.finalUrl)
    }

    // ------------------------------------------------------------------
    // Streaming variants
    // ------------------------------------------------------------------

    /**
     * Open a response and hand its body to [block] as a live stream, keeping the
     * connection open for the duration of the callback. On a non-streamable status
     * the block gets a null stream and a bounded prefix of the error body in
     * [SimpleResponse.body].
     */
    suspend fun stream(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        connectTimeoutMs: Long? = null,
        readTimeoutMs: Long? = connectTimeoutMs,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
        followRedirects: Boolean = true,
        block: suspend (stream: NetworkDataStream?, response: SimpleResponse) -> Unit,
    ): SimpleResponse {
        var ownedResponse: HttpUrlEngine.OpenResponse? = null
        suspend fun closeResponse() = withContext(NonCancellable + Dispatchers.IO) {
            ownedResponse?.close()
            ownedResponse = null
        }
        try {
            currentCoroutineContext().ensureActive()
            val response = withContext(NonCancellable + Dispatchers.IO) {
                // Shield acquisition until ownership is recorded; socket timeouts still apply.
                HttpUrlEngine.openResponse(
                    url, method, headers, HttpUrlEngine.toBodyBytes(body),
                    followRedirects = followRedirects, connectTimeoutMs = connectTimeoutMs, readTimeoutMs = readTimeoutMs,
                    sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
                    // This entry point has always let connect failures surface as they are.
                    wrapConnectErrors = false,
                ).also { ownedResponse = it }
            }
            currentCoroutineContext().ensureActive()
            var simple = SimpleResponse(response.status, response.statusMessage, "", response.headers, response.finalUrl)
            if (simple.isSuccess || simple.status == STATUS_PARTIAL_CONTENT) {
                if (!response.hasStream) {
                    closeResponse()
                    block(null, simple)
                    return simple
                }
                val raw = response.stream
                var closed = false
                val streamObj = object : NetworkDataStream {
                    override val isClosedForRead: Boolean get() = closed
                    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        withContext(Dispatchers.IO) {
                            try {
                                val n = raw.read(buffer, offset, length)
                                if (n == -1) closed = true
                                n
                            } catch (_: java.io.IOException) {
                                closed = true
                                -1
                            }
                        }
                }
                block(streamObj, simple)
            } else {
                val errorBody = withContext(Dispatchers.IO) {
                    try {
                        readErrorPrefix(response.stream, ERROR_PREFIX_BYTES)
                    } finally {
                        closeResponse()
                    }
                }
                simple = simple.copy(body = errorBody)
                block(null, simple)
            }
            return simple
        } finally {
            closeResponse()
        }
    }

    /**
     * Used by youpipe SABR – returns code, headers, InputStream that disconnects on close.
     * Manual redirect handling; no forced Content-Type when body == null.
     */
    suspend fun performRequestInputStream(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        timeoutMs: Long? = null,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): Triple<Int, Map<String, List<String>>, InputStream> {
        val response = HttpUrlEngine.openResponse(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects = true, connectTimeoutMs = timeoutMs, readTimeoutMs = timeoutMs,
            sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
            // SABR callers expect the raw IOException, not a wrapped one.
            wrapConnectErrors = false,
        )
        return Triple(response.status, response.headers, response.stream)
    }

    suspend fun getContentLength(
        url: String,
        headers: Map<String, *> = emptyMap<String, Any>(),
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): Long? {
        return withContext(Dispatchers.IO) {
            probeContentLength(url, headers, resolveFactory(sslSocketFactory, useSystemTrust))
        }
    }

    private fun probeContentLength(
        url: String,
        headers: Map<String, *>,
        effectiveFactory: SSLSocketFactory?,
    ): Long? {
        var currentUrl = url
        var redirects = 0
        while (true) {
            val conn = HttpUrlEngine.openConnection(
                currentUrl, "HEAD", headers, null, null,
                sslSocketFactory = effectiveFactory,
            )
            val next = followLengthRedirect(conn, currentUrl, redirects) ?: return readLength(conn)
            currentUrl = next.url
            redirects = next.count
        }
    }

    private data class LengthRedirect(val url: String, val count: Int)

    private fun followLengthRedirect(
        conn: java.net.HttpURLConnection,
        currentUrl: String,
        redirects: Int,
    ): LengthRedirect? {
        return try {
            val status = conn.responseCode
            val redirectable = status in HttpUrlEngine.REDIRECT_STATUS_MIN..HttpUrlEngine.REDIRECT_STATUS_MAX &&
                status != HttpUrlEngine.STATUS_NOT_MODIFIED &&
                redirects < HttpUrlEngine.MAX_REDIRECTS
            val loc = if (redirectable) {
                conn.getHeaderField("Location") ?: conn.getHeaderField("location")
            } else {
                null
            }
            if (loc != null) {
                val next = URL(URL(currentUrl), loc).toString()
                conn.disconnect()
                LengthRedirect(next, redirects + 1)
            } else {
                null
            }
        } catch (_: java.io.IOException) {
            conn.disconnect()
            null
        }
    }

    private fun readLength(conn: java.net.HttpURLConnection): Long? {
        return try {
            val len = conn.getHeaderField("Content-Length")?.toLongOrNull()
                ?: conn.getHeaderField("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
            val respHeaders = HttpUrlEngine.extractHeaders(conn)
            conn.disconnect()
            len ?: respHeaders.entries.firstOrNull {
                it.key.equals("Content-Length", ignoreCase = true)
            }?.value?.firstOrNull()?.toLongOrNull()
        } catch (_: java.io.IOException) {
            conn.disconnect()
            null
        }
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    /**
     * Streaming JSON request. The body is parsed straight off the socket, so no whole-body
     * `ByteArray` or `String` is ever materialized.
     *
     * Not inline itself so that [callJson] only has to supply the reified pieces.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    internal suspend fun <T> decodeJsonResponse(
        url: String,
        method: String,
        headers: Map<String, *>,
        body: Any?,
        sslSocketFactory: SSLSocketFactory?,
        useSystemTrust: Boolean,
        deserializer: DeserializationStrategy<T>,
        isBoolean: Boolean,
        isUnit: Boolean,
    ): T = withContext(Dispatchers.IO) {
        val response = HttpUrlEngine.openResponse(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects = true, connectTimeoutMs = null,
            sslSocketFactory = resolveFactory(sslSocketFactory, useSystemTrust),
        )
        response.use { resp ->
            // The empty-body shortcuts below need to know whether there is anything to parse.
            // Leading whitespace means nothing to a JSON parser, so it is skipped while looking:
            // a body of just a newline counted as empty before this was a stream, and endpoints
            // that answer `callJson<Unit>` that way must keep working.
            val peekable = PushbackInputStream(resp.stream, 1)
            val first = skipJsonWhitespace(peekable, resp.finalUrl)
            if (first >= 0) peekable.unread(first)
            val empty = first < 0

            if (resp.status == STATUS_NO_CONTENT || empty) {
                if (isBoolean) return@use resp.isSuccess as T
                if (isUnit) return@use Unit as T
            }

            if (!resp.isSuccess) {
                val prefix = readErrorPrefix(peekable, JSON_ERROR_PREFIX_BYTES)
                throw java.io.IOException("HTTP ${resp.status}: ${prefix.take(JSON_ERROR_MESSAGE_CHARS)}")
            }

            if (empty) {
                jsonConfig.decodeFromString(deserializer, "null")
            } else {
                jsonConfig.decodeFromStream(deserializer, peekable)
            }
        }
    }

    /** JSON whitespace bytes skipped when probing for an empty body. */
    private val JSON_WHITESPACE = setOf(' '.code, '\t'.code, '\r'.code, '\n'.code)

    /**
     * Skips leading JSON whitespace, returning the first significant byte or -1 at EOF.
     * A body of just a newline counted as empty before this was a stream, and endpoints
     * that answer `callJson<Unit>` that way must keep working.
     */
    private fun skipJsonWhitespace(peekable: PushbackInputStream, finalUrl: String): Int {
        return try {
            var byte = peekable.read()
            while (byte in JSON_WHITESPACE) {
                byte = peekable.read()
            }
            byte
        } catch (e: java.io.IOException) {
            throw java.io.IOException("Failed to read response body from $finalUrl", e)
        }
    }
    suspend inline fun <reified T> callJson(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): T = decodeJsonResponse(
        url, method, headers, body, sslSocketFactory, useSystemTrust,
        deserializer = serializer<T>(),
        isBoolean = T::class == Boolean::class,
        isUnit = T::class == Unit::class,
    )

    suspend inline fun <reified T> getJson(
        url: String,
        headers: Map<String, *> = emptyMap<String, Any>(),
        sslSocketFactory: SSLSocketFactory? = null,
        useSystemTrust: Boolean = false,
    ): T = callJson(url, "GET", headers, null, sslSocketFactory, useSystemTrust)
}
