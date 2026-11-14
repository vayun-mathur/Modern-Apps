package com.vayunmathur.library.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Pooled HTTP engine behind [NetworkClient.execute] (issue 815).
 *
 * MapLibre parity for the range-fetch path: [HttpUrlEngine] opens a fresh `HttpURLConnection`
 * per request with no pool or HTTP/2 config, so a cold screenful of tiles pays a TLS handshake
 * per range. This keeps one shared `OkHttpClient` — connection pool, HTTP/2 multiplexing, and
 * `Dispatcher.maxRequestsPerHost = 20` matching MapLibre's `HttpRequestImpl` — so the second
 * range reuses the first's connection.
 *
 * Scope is deliberately narrow: only `execute` (the JNI bridge's range fetches, image
 * fetching, traffic/street-view reads) moves here. `performRequest`, `stream`,
 * `performRequestInputStream` and the JSON path stay on [HttpUrlEngine], whose redirect,
 * decompression and streaming semantics they depend on. Redirects *are* followed here
 * (OkHttp default), and the final URL is reported like `execute` always did.
 *
 * TLS never leaves `:library:network`: the client is built once per trust state from the same
 * [BundledTrust] pair `init` installed, and rebuilt when `init` changes the bundle. A per-call
 * [SSLSocketFactory] without its manager cannot back a pooled client, so explicit factories
 * fall back to [HttpUrlEngine] rather than partitioning the pool per connection.
 *
 * Synchronous `suspend` shape, like the engine it replaces: the JNI bridge calls through
 * `runBlocking`, so this must not change threading — OkHttp's own dispatcher threads do the
 * I/O inside the blocking `execute()` call, which runs on `Dispatchers.IO`.
 */
internal object PooledHttpEngine {

    /**
     * MapLibre's per-host concurrency (`DEFAULT_MAXIMUM_CONCURRENT_REQUESTS`, OkHttp
     * `Dispatcher.setMaxRequestsPerHost(20)`): enough that a screenful of ranges overlaps
     * instead of serialising, few enough to stay inside a radio's happy place.
     */
    const val MAX_REQUESTS_PER_HOST = 20

    /** Range fetches are small and latency-bound; fail fast rather than hang a tile worker. */
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 30L

    /** Pooled connections idle out after a minute; the pool holds a handful per host. */
    private const val KEEP_ALIVE_MINUTES = 1L
    private const val MAX_IDLE_CONNECTIONS = 10

    /** Set while [USE_POOLED_ENGINE] routes `execute` here; cleared by the fallback path. */
    @Volatile
    var lastUsedPooled = false
        private set

    /**
     * Kill-switch for the rollout (issue 815): true routes [NetworkClient.execute] through the
     * pool, false keeps the historical [HttpUrlEngine] path. One release with the flag, then the
     * old path goes.
     */
    @Volatile
    var USE_POOLED_ENGINE = true

    private val lock = Any()

    @Volatile
    private var client: OkHttpClient? = null

    /** Same, but with redirects disabled for callers that inspect 3xx themselves. */
    @Volatile
    private var noRedirectClient: OkHttpClient? = null

    /** The trust state [client] was built for; a mismatch rebuilds it (see [clientFor]). */
    @Volatile
    private var builtFactory: SSLSocketFactory? = null

    @Volatile
    private var builtManager: X509TrustManager? = null

    @Volatile
    private var builtSystemTrust = false

    /**
     * Whether this engine can serve a call with [explicitFactory]: only when it is null (use the
     * client's trust) — a bare factory without its manager cannot join the pool.
     */
    fun canServe(explicitFactory: SSLSocketFactory?): Boolean = explicitFactory == null

    suspend fun execute(
        url: String,
        method: String,
        headers: Map<String, *>,
        body: Any?,
        followRedirects: Boolean,
        connectTimeoutMs: Long?,
        readTimeoutMs: Long? = connectTimeoutMs,
        useSystemTrust: Boolean,
    ): RawResponse = withContext(Dispatchers.IO) {
        val pooled = clientFor(useSystemTrust, followRedirects)
        // The shared client carries default timeouts; a caller that asked for its own
        // gets a derived client (same pool and dispatcher, so pooling is preserved).
        // The HttpURLConnection path always honoured these, so the pooled path must too.
        val client = if (readTimeoutMs == null && connectTimeoutMs == null) {
            pooled
        } else {
            pooled.newBuilder().apply {
                connectTimeoutMs?.let { connectTimeout(it, TimeUnit.MILLISECONDS) }
                readTimeoutMs?.let { readTimeout(it, TimeUnit.MILLISECONDS) }
            }.build()
        }
        val bodyBytes = HttpUrlEngine.toBodyBytes(body)
        val builder = Request.Builder().url(url)
        applyHeaders(builder, headers)
        // OkHttp (unlike HttpURLConnection) requires POST/PUT/PATCH to carry a body object even
        // when empty; every current `execute` caller is a GET, but a null must not become a crash.
        val requestBody = bodyBytes?.toRequestBody(null, 0, bodyBytes.size)
            ?: if (method == "POST" || method == "PUT" || method == "PATCH") {
                ByteArray(0).toRequestBody(null, 0, 0)
            } else {
                null
            }
        builder.method(method, requestBody)
        val call = client.newCall(builder.build())
        // try-with-resources equivalent: `use` closes the response (and its body) on every path.
        call.execute().use { response ->
            val bytes = try {
                response.body?.bytes() ?: ByteArray(0)
            } catch (e: IOException) {
                throw IOException("Failed to read response body from $url", e)
            }
            val respHeaders = mutableMapOf<String, List<String>>()
            for (name in response.headers.names()) {
                respHeaders[name] = response.headers.values(name)
            }
            // Redirects were followed by the client when asked; otherwise 3xx answers here as a
            // normal response and the caller inspects status, exactly like HttpURLConnection.
            val finalUrl = response.request.url.toString()
            lastUsedPooled = true
            RawResponse(
                response.code,
                response.message,
                bytes,
                respHeaders,
                finalUrl,
            )
        }
    }

    private fun applyHeaders(builder: Request.Builder, headers: Map<String, *>) {
        headers.forEach { (k, v) ->
            when (v) {
                is Iterable<*> -> v.forEach { elem ->
                    if (elem != null) builder.addHeader(k, elem.toString())
                }
                else -> if (v != null) builder.header(k, v.toString())
            }
        }
    }

    /**
     * The shared client for [useSystemTrust] and [followRedirects], rebuilding when `init`
     * changed the trust state underneath it. SYSTEM trust (or no bundled factory) builds a plain
     * client; otherwise the bundled factory + manager are pinned once at construction so every
     * connection shares one pool instead of partitioning it per request. Redirect-following is a
     * client-level setting in OkHttp, so the suppressing caller gets its own shared instance
     * rather than a per-call build.
     */
    private fun clientFor(useSystemTrust: Boolean, followRedirects: Boolean): OkHttpClient {
        val factory = if (useSystemTrust) null else NetworkClient.defaultSslSocketFactory
        val manager = if (useSystemTrust) null else NetworkClient.defaultTrustManager
        synchronized(lock) {
            if (followRedirects) {
                val current = client
                val trustMatches = factory === builtFactory && manager === builtManager &&
                    useSystemTrust == builtSystemTrust
                if (current != null && trustMatches) {
                    return current
                }
                val built = buildClient(factory, manager, true)
                client = built
                builtFactory = factory
                builtManager = manager
                builtSystemTrust = useSystemTrust
                return built
            }
            noRedirectClient?.let { return it }
            val built = buildClient(factory, manager, false)
            noRedirectClient = built
            return built
        }
    }

    private fun buildClient(
        factory: SSLSocketFactory?,
        manager: X509TrustManager?,
        followRedirects: Boolean,
    ): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequestsPerHost = MAX_REQUESTS_PER_HOST
        }
        val pool = ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)
        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(pool)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .followRedirects(followRedirects)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (factory != null && manager != null) {
            builder.sslSocketFactory(factory, manager)
        }
        return builder.build()
    }

    /** Test seam: drop the cached client so the next call rebuilds from current trust state. */
    internal fun resetForTests() {
        synchronized(lock) {
            client?.dispatcher?.executorService?.shutdown()
            client?.connectionPool?.evictAll()
            noRedirectClient?.dispatcher?.executorService?.shutdown()
            noRedirectClient?.connectionPool?.evictAll()
            client = null
            noRedirectClient = null
            builtFactory = null
            builtManager = null
            builtSystemTrust = false
            lastUsedPooled = false
        }
    }
}
