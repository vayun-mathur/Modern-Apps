package com.vayunmathur.appstore.data.play

import com.aurora.gplayapi.data.models.PlayResponse
import com.aurora.gplayapi.network.IHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Implementation of gplayapi's IHttpClient using HttpURLConnection.
 * Mirrors Aurora Store's HttpClient.kt behavior but avoids OkHttp.
 *
 * Uses manual redirect handling (301-308, up to 5 hops) similar to
 * library:network HttpUrlEngine, and ensures POST with empty body does not
 * force an unwanted Content-Type.
 *
 * Requests are paced and a refusal is waited out rather than passed on. Play answers a
 * burst — "update all" across a phone's worth of Play apps is one purchase call and a
 * delivery per app — with 429, and the store used to hand that straight to the user as
 * "Failed: Too Many Requests" (issue #596). Pacing and backoff live here rather than at
 * each call site because the limit is on the account, not on any one feature: browse, the
 * update check and an install all spend from the same budget, and the background worker
 * runs in its own process-wide instance of the store's Play stack.
 */
class PlayHttpClient : IHttpClient {

    /** Holds HTTP timeout and status constants for Play requests. */
    companion object {
        private const val CONNECT_TIMEOUT = 30_000
        private const val READ_TIMEOUT = 30_000
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private const val HTTP_ERROR_MIN = 400

        private const val TOO_MANY_REQUESTS = 429
        private const val SERVICE_UNAVAILABLE = 503
        private const val MILLIS_PER_SECOND = 1_000L
        private const val NETWORK_FAILURE_CODE = -1

        /** Smallest gap between two requests starting, before anything has been refused. */
        private const val MIN_REQUEST_GAP_MS = 250L
        private const val MAX_RETRIES = 3
        private const val BACKOFF_BASE_MS = 2_000L
        private const val BACKOFF_CEILING_MS = 30_000L

        /** Spread so several waiting callers don't all return at the same instant. */
        private const val BACKOFF_JITTER_MS = 500L

        private val pacingLock = Any()

        /** Earliest the next request may start. Shared: the limit is per account, not per client. */
        private var nextRequestAtMs = 0L

        private val waiting = AtomicInteger(0)
        private val _throttled = MutableStateFlow(false)

        /** True while any caller is sitting out a refusal, for a status line rather than an error. */
        val throttled: StateFlow<Boolean> = _throttled.asStateFlow()
    }

    private val _responseCode = MutableStateFlow(0)
    override val responseCode: StateFlow<Int> get() = _responseCode.asStateFlow()

    override fun get(url: String, headers: Map<String, String>): PlayResponse {
        return execute(url, "GET", headers, null)
    }

    override fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        val fullUrl = if (params.isNotEmpty()) {
            val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            if (url.contains("?")) "$url&$query" else "$url?$query"
        } else url
        return execute(fullUrl, "GET", headers, null)
    }

    override fun get(
        url: String,
        headers: Map<String, String>,
        paramString: String
    ): PlayResponse {
        val fullUrl = if (paramString.isNotEmpty()) {
            if (paramString.startsWith("?")) "$url$paramString" else "$url?$paramString"
        } else url
        return execute(fullUrl, "GET", headers, null)
    }

    override fun getAuth(url: String): PlayResponse {
        return execute(url, "GET", emptyMap(), null)
    }

    override fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        // POST with query params encoded in URL (as Aurora does)
        val fullUrl = if (params.isNotEmpty()) {
            val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            if (url.contains("?")) "$url&$query" else "$url?$query"
        } else url
        return execute(fullUrl, "POST", headers, ByteArray(0), contentType = null)
    }

    override fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray
    ): PlayResponse {
        return execute(url, "POST", headers, body)
    }

    override fun postAuth(url: String, body: ByteArray): PlayResponse {
        return execute(url, "POST", emptyMap(), body, contentType = "application/json")
    }

    /** One attempt's answer, plus how long the server asked us to wait before the next. */
    internal data class Attempt(val response: PlayResponse, val retryAfterMs: Long)

    private fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        rawBody: ByteArray?,
        contentType: String? = null
    ): PlayResponse {
        var attempt = 0
        while (true) {
            awaitTurn()
            val (response, retryAfterMs) = executeOnce(url, method, headers, rawBody, contentType)
            val refused = response.code == TOO_MANY_REQUESTS || response.code == SERVICE_UNAVAILABLE
            if (!refused || attempt >= MAX_RETRIES) return response
            backOff(attempt, retryAfterMs)
            attempt++
        }
    }

    /** Block until the shared pacing window opens, and claim it. */
    private fun awaitTurn() {
        val wait = synchronized(pacingLock) {
            val now = System.currentTimeMillis()
            val startAt = maxOf(now, nextRequestAtMs)
            nextRequestAtMs = startAt + MIN_REQUEST_GAP_MS
            startAt - now
        }
        if (wait > 0) sleep(wait)
    }

    /**
     * Wait out a refusal, and hold every other caller back for the same period — a limit
     * this request hit is one the next one would hit too.
     */
    private fun backOff(attempt: Int, retryAfterMs: Long) {
        val delay = (retryAfterMs.takeIf { it > 0 } ?: (BACKOFF_BASE_MS shl attempt))
            .coerceAtMost(BACKOFF_CEILING_MS)
        synchronized(pacingLock) {
            nextRequestAtMs = maxOf(nextRequestAtMs, System.currentTimeMillis() + delay)
        }
        waiting.incrementAndGet()
        _throttled.value = true
        try {
            sleep(delay + Random.nextLong(BACKOFF_JITTER_MS))
        } finally {
            _throttled.value = waiting.decrementAndGet() > 0
        }
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /** `Retry-After` in milliseconds, or 0 when the server did not name a delay in seconds. */
    private fun retryAfterMs(conn: HttpURLConnection): Long =
        conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.times(MILLIS_PER_SECOND)
            ?: 0L

    private fun executeOnce(
        url: String,
        method: String,
        headers: Map<String, String>,
        rawBody: ByteArray?,
        contentType: String? = null
    ): Attempt {
        return try {
            RedirectFollower(
                ::openConnection,
                ::readResponseCode,
                ::readAttempt,
                ::closeQuietly,
            ).follow(url, method, headers, rawBody, contentType)
        } catch (expected: IOException) {
            networkFailure(expected)
        } catch (expected: IllegalStateException) {
            networkFailure(expected)
        } catch (expected: SecurityException) {
            networkFailure(expected)
        }
    }

    private fun networkFailure(e: Exception): Attempt {
        return Attempt(
            PlayResponse(
                isSuccessful = false,
                code = NETWORK_FAILURE_CODE,
                errorString = e.message ?: "Network error",
                errorBytes = ByteArray(0),
                responseBytes = ByteArray(0)
            ),
            0L,
        )
    }

    private fun readResponseCode(conn: HttpURLConnection): Int {
        return try {
            conn.responseCode
        } catch (expected: IOException) {
            conn.disconnect()
            throw expected
        }
    }

    private fun readAttempt(conn: HttpURLConnection, code: Int): Attempt {
        val ct = conn.getHeaderField("Content-Type")
        val responseMessage = readResponseMessage(conn)
        val retryAfter = retryAfterMs(conn)
        _responseCode.value = code

        val bytes = readBodyBytes(conn, code)
        closeQuietly(conn)

        val isSuccessful = code in HTTP_OK_MIN..HTTP_OK_MAX
        val errStr = if (!isSuccessful) {
            responseMessage.ifEmpty { "Error $code" }
        } else ""

        val errBytes = if (!isSuccessful) bytes else ByteArray(0)
        val respBytes = if (isSuccessful) bytes else ByteArray(0)

        return Attempt(
            PlayResponse(
                responseBytes = respBytes,
                errorBytes = errBytes,
                errorString = errStr,
                isSuccessful = isSuccessful,
                code = code,
                type = ct
            ),
            retryAfter,
        )
    }

    private fun readResponseMessage(conn: HttpURLConnection): String {
        return try {
            conn.responseMessage
        } catch (_: IOException) {
            ""
        } ?: ""
    }

    private fun readBodyBytes(conn: HttpURLConnection, code: Int): ByteArray {
        return try {
            val stream = if (code >= HTTP_ERROR_MIN) {
                conn.errorStream ?: conn.inputStream
            } else {
                conn.inputStream
            }
            stream?.readBytes() ?: ByteArray(0)
        } catch (_: IOException) {
            ByteArray(0)
        }
    }

    private fun closeQuietly(conn: HttpURLConnection) {
        try {
            conn.inputStream?.close()
        } catch (_: IOException) {
        }
        try {
            conn.errorStream?.close()
        } catch (_: IOException) {
        }
        conn.disconnect()
    }

    private fun openConnection(
        urlString: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray?,
        contentType: String?,
    ): HttpURLConnection {
        val rawConn = URL(urlString).openConnection()
        // Play Store auth uses Google GTS — pin via app-wide STANDARD bundle (contains GTS R1-R4).
        val factory = com.vayunmathur.library.network.NetworkClient.defaultSslSocketFactory
        if (factory != null && rawConn is javax.net.ssl.HttpsURLConnection) {
            rawConn.sslSocketFactory = factory
        }
        val conn = (rawConn as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            doOutput = bodyBytes != null
        }

        setRequestMethod(conn, method)

        headers.forEach { (k, v) ->
            conn.setRequestProperty(k, v)
        }

        applyContentType(conn, method, bodyBytes, contentType)
        writeBody(conn, bodyBytes)

        return conn
    }

    // Set method, with reflection fallback for custom verbs
    private fun setRequestMethod(conn: HttpURLConnection, method: String) {
        try {
            conn.requestMethod = method
        } catch (_: java.net.ProtocolException) {
            setMethodByReflection(conn, method)
        }
    }

    private fun setMethodByReflection(conn: HttpURLConnection, method: String) {
        var clazz: Class<*>? = conn.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField("method")
                f.isAccessible = true
                f.set(conn, method)
                return
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            } catch (_: IllegalAccessException) {
                clazz = clazz.superclass
            } catch (_: SecurityException) {
                return
            }
        }
    }

    // Content-Type handling: explicit param wins, otherwise default protobuf for POST
    private fun applyContentType(
        conn: HttpURLConnection,
        method: String,
        bodyBytes: ByteArray?,
        contentType: String?,
    ) {
        val effectiveContentType = when {
            contentType != null -> contentType
            method == "POST" && bodyBytes != null -> "application/x-protobuf"
            else -> null
        }
        if (effectiveContentType != null) {
            conn.setRequestProperty("Content-Type", effectiveContentType)
        }
    }

    private fun writeBody(conn: HttpURLConnection, bodyBytes: ByteArray?) {
        if (bodyBytes == null) return
        try {
            conn.setFixedLengthStreamingMode(bodyBytes.size)
        } catch (_: IllegalStateException) {
            try {
                conn.setChunkedStreamingMode(0)
            } catch (_: IllegalStateException) {
            }
        }
        // A write failure propagates to the caller as-is.
        conn.outputStream.use { it.write(bodyBytes) }
    }
}

/**
 * Manual redirect follower (301-308, up to [maxRedirects] hops), extracted from
 * [PlayHttpClient] so the client stays under detekt's TooManyFunctions cap.
 */
internal class RedirectFollower(
    private val open: (
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
    ) -> HttpURLConnection,
    private val readCode: (HttpURLConnection) -> Int,
    private val readResult: (HttpURLConnection, Int) -> PlayHttpClient.Attempt,
    private val close: (HttpURLConnection) -> Unit,
    private val maxRedirects: Int = MAX_REDIRECTS,
) {
    private class State(
        var url: String,
        var method: String,
        var body: ByteArray?,
        var contentType: String?,
        var redirects: Int = 0,
    )

    private class Target(
        val url: String,
        val method: String,
        val body: ByteArray?,
        val contentType: String?,
    )

    fun follow(
        url: String,
        method: String,
        headers: Map<String, String>,
        rawBody: ByteArray?,
        contentType: String?,
    ): PlayHttpClient.Attempt {
        val state = State(url, method, rawBody, contentType)
        while (true) {
            val conn = open(state.url, state.method, headers, state.body, state.contentType)
            val code = readCode(conn)
            val target = redirectTarget(conn, code, state)
            if (target == null) return readResult(conn, code)
            close(conn)
            state.url = target.url
            state.method = target.method
            state.body = target.body
            state.contentType = target.contentType
            state.redirects++
        }
    }

    private fun redirectTarget(conn: HttpURLConnection, code: Int, state: State): Target? {
        // Manual redirect handling
        if (code !in REDIRECT_MIN..REDIRECT_MAX || code == REDIRECT_NOT_MODIFIED) return null
        if (state.redirects >= maxRedirects) return null
        val loc = conn.getHeaderField("Location") ?: conn.getHeaderField("location") ?: return null
        val nextUrl = URL(URL(state.url), loc).toString()
        if (code == REDIRECT_SEE_OTHER) {
            return Target(nextUrl, "GET", null, null)
        }
        return Target(nextUrl, state.method, state.body, state.contentType)
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val REDIRECT_MIN = 301
        private const val REDIRECT_MAX = 308
        private const val REDIRECT_NOT_MODIFIED = 304
        private const val REDIRECT_SEE_OTHER = 303
    }
}
