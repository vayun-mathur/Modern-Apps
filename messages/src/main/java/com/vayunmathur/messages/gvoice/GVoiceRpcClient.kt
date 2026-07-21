package com.vayunmathur.messages.gvoice

import android.util.Base64
import android.util.Log
import com.google.protobuf.Message
import com.vayunmathur.messages.gmessages.PbLite
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.utils.io.ByteReadChannel
import java.security.MessageDigest

/**
 * HTTP transport for the Google Voice protocol.
 *
 * Mirrors `pkg/libgv/request.go`:
 *  - All RPC bodies are pblite-encoded JSON by default; binary protobuf
 *    when explicitly requested.
 *  - Per-host header bundles (`Sec-Fetch-Site`, `X-Client-Version`,
 *    `X-ClientDetails`, `X-Goog-Api-Key`, etc) vary by destination
 *    domain — see [applyHeaders].
 *  - Cookies are sent on every request; the `SAPISID` cookie also
 *    materializes as an `Authorization: SAPISIDHASH …` header (same
 *    algorithm libgm uses).
 *  - Retries network errors and 5xx up to [MAX_RETRIES] with linear
 *    backoff.
 */
class GVoiceRpcClient(
    /** Mutable cookie jar; updated atomically by the session manager
     *  when Set-Cookie headers come back. */
    @Volatile private var cookies: Map<String, String>,
) {
    var onCookiesChanged: ((Map<String, String>) -> Unit)? = null

    private val normal: HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                // OkHttp properly handles hostname verification with SNI
                // Required for Android domain-specific TLS configurations
                retryOnConnectionFailure(true)
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
    }

    private val realtime: HttpClient = HttpClient(OkHttp) {
        // BrowserChannel long-polls can stay open for several minutes.
        engine {
            config {
                retryOnConnectionFailure(true)
                // Extended timeouts for long polling
                readTimeout(java.util.concurrent.TimeUnit.MINUTES.toMillis(6), java.util.concurrent.TimeUnit.MILLISECONDS)
                connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 6 * 60 * 1000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 90_000
        }
    }

    fun updateCookies(newCookies: Map<String, String>) {
        cookies = newCookies
    }

    fun close() {
        runCatching { normal.close() }
        runCatching { realtime.close() }
    }

    /** POST [body] as pblite. Returns the decoded response of type [T]. */
    suspend fun <T : Message> postPbLite(
        url: String,
        body: Message,
        responseTemplate: T,
    ): T {
        val resp = postRetrying(url, PbLite.encode(body).toByteArray(Charsets.UTF_8), pbLite = true)
        return decodeResponse(resp, responseTemplate)
    }

    /**
     * POST a raw JSON literal as a pblite body. Used by the BrowserChannel
     * subscribe path which sends a hardcoded magic JSON literal that has
     * no protobuf representation in the bridge either.
     */
    suspend fun <T : Message> postRawPbLite(
        url: String,
        jsonBody: String,
        responseTemplate: T,
    ): T {
        val resp = postRetrying(url, jsonBody.toByteArray(Charsets.UTF_8), pbLite = true)
        return decodeResponse(resp, responseTemplate)
    }

    /** POST raw bytes as protobuf binary. */
    suspend fun <T : Message> postBinary(
        url: String,
        body: Message,
        responseTemplate: T,
    ): T {
        val resp = postRetrying(url, body.toByteArray(), pbLite = false)
        return decodeResponse(resp, responseTemplate)
    }

    /** POST a form-encoded body. Returns the raw response so the caller
     *  can inspect headers / body shape (BrowserChannel needs this). */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val body = form.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        return postRetrying(
            url,
            body.toByteArray(Charsets.UTF_8),
            contentType = "application/x-www-form-urlencoded",
            pbLite = false,
            extraHeaders = extraHeaders,
        )
    }

    /**
     * GET a streaming response — used by the BrowserChannel long-poll.
     * Caller reads the body via [onResponse] which gets the raw
     * [HttpResponse]; keeping the connection open for the duration of
     * the callback (preparePost-style execution).
     */
    suspend fun <T> getStreaming(
        url: String,
        extraQuery: Map<String, String> = emptyMap(),
        onResponse: suspend (HttpResponse) -> T,
    ): T {
        val finalUrl = buildUrl(url, extraQuery)
        return realtime.preparePost(finalUrl) {
            method = HttpMethod.Get
            applyHeaders(finalUrl, accept = "*/*")
        }.execute { response ->
            refreshCookies(response)
            onResponse(response)
        }
    }

    /** Stream the body of a still-open response. Used by [Utf16ChunkReader]. */
    suspend fun bodyChannel(response: HttpResponse): ByteReadChannel = response.bodyAsChannel()

    /**
     * GET a raw resource (e.g. media attachment). Returns the raw bytes
     * and the Content-Type header value.
     */
    suspend fun getRaw(
        url: String,
        extraQuery: Map<String, String> = emptyMap(),
    ): Pair<ByteArray, String> {
        val finalUrl = buildUrl(url, extraQuery)
        val resp = normal.request(finalUrl) {
            method = HttpMethod.Get
            applyHeaders(finalUrl, accept = "*/*")
        }
        if (resp.status.value !in 200..299) {
            error("HTTP ${resp.status.value} on GET $url")
        }
        refreshCookies(resp)
        val mime = resp.headers["Content-Type"]?.substringBefore(';')?.trim() ?: "application/octet-stream"
        return resp.bodyAsBytes() to mime
    }

    // ----------------------------------------------------------------
    // POST core + retry
    // ----------------------------------------------------------------

    private suspend fun postRetrying(
        url: String,
        body: ByteArray,
        pbLite: Boolean = true,
        contentType: String? =
            if (pbLite) "application/json+protobuf" else "application/x-protobuf",
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val finalUrl = buildUrl(url, emptyMap())
        var attempt = 0
        while (true) {
            val resp = try {
                normal.request(finalUrl) {
                    method = HttpMethod.Post
                    if (contentType != null) contentType(ContentType.parse(contentType))
                    applyHeaders(finalUrl, accept = "*/*")
                    for ((k, v) in extraHeaders) headers.append(k, v)
                    setBody(body)
                }
            } catch (t: Throwable) {
                if (attempt > MAX_RETRIES) throw t
                attempt++
                Log.w(TAG, "POST $url network error attempt=$attempt: ${t.message}")
                kotlinx.coroutines.delay((attempt * 2_000L))
                continue
            }
            // Retry only on 5xx; 4xx is the caller's problem.
            if (resp.status.value in 500..599 && attempt <= MAX_RETRIES) {
                attempt++
                Log.w(TAG, "POST $url ${resp.status.value} attempt=$attempt")
                kotlinx.coroutines.delay((attempt * 2_000L))
                continue
            }
            refreshCookies(resp)
            return resp
        }
    }

    /**
     * Build a URL, appending the standard `key=...` (+ optional `alt=proto`)
     * query parameters that libgv adds for the API + Contacts domains.
     */
    private fun buildUrl(url: String, extraQuery: Map<String, String>): String {
        val builder = URLBuilder(url)
        val host = builder.host
        if (host.endsWith(VoiceEndpoints.ApiDomain) && host != VoiceEndpoints.WaaDomain) {
            builder.parameters.append("key", VoiceEndpoints.ApiKey)
            if (host == VoiceEndpoints.ApiDomain || host == VoiceEndpoints.ContactsDomain) {
                builder.parameters.append("alt", "proto")
            }
        }
        for ((k, v) in extraQuery) builder.parameters.append(k, v)
        return builder.buildString()
    }

    /**
     * Apply the per-host header bundle the bridge documents. Includes the
     * cookie jar + (when SAPISID is present) the SAPISIDHASH authorization.
     * Content-Type is NOT set here (matches Go's prepareHeaders); the caller
     * sets it via Ktor's contentType() before calling this method.
     */
    private fun io.ktor.client.request.HttpRequestBuilder.applyHeaders(
        url: String,
        accept: String,
    ) {
        val host = URLBuilder(url).host
        headers {
            append("Sec-Ch-Ua", VoiceEndpoints.SecChUa)
            append("Sec-Ch-Ua-Platform", VoiceEndpoints.SecChPlatform)
            append("Sec-Ch-Ua-Mobile", "?0")
            append("User-Agent", VoiceEndpoints.UserAgent)
            append("X-Goog-AuthUser", "0")
            if (host == VoiceEndpoints.ApiDomain) {
                append("X-Client-Version", VoiceEndpoints.ClientVersion)
                append("X-ClientDetails", VoiceEndpoints.ClientDetails)
                append("X-JavaScript-User-Agent", VoiceEndpoints.JavaScriptUserAgent)
                append("X-Requested-With", "XMLHttpRequest")
                append("X-Goog-Encode-Response-If-Executable", "base64")
            }
            if (host == VoiceEndpoints.ContactsDomain) {
                append("X-Goog-Api-Key", VoiceEndpoints.ApiKey)
                append("X-Goog-Encode-Response-If-Executable", "base64")
            }
            if (host == VoiceEndpoints.WaaDomain) {
                append("X-Goog-Api-Key", VoiceEndpoints.WaaApiKey)
                append("X-User-Agent", VoiceEndpoints.WaaXUserAgent)
            }
            append("Sec-Fetch-Dest", "empty")
            append("Sec-Fetch-Mode", "cors")
            val site = if (host.endsWith(".${VoiceEndpoints.ApiDomain}")) "same-site" else "same-origin"
            append("Sec-Fetch-Site", site)
            append("Accept", accept)
            append("Accept-Language", "en-US,en;q=0.5")
            if (host == VoiceEndpoints.UploadDomain) {
                append("Origin", "https://${VoiceEndpoints.UploadDomain}")
                append("Referer", "https://${VoiceEndpoints.UploadDomain}/")
            } else {
                append("Origin", VoiceEndpoints.Origin)
                append("Referer", "${VoiceEndpoints.Origin}/")
            }
            // Cookies + SAPISIDHASH.
            val cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
            if (cookieHeader.isNotEmpty()) append("Cookie", cookieHeader)
            cookies["SAPISID"]?.let { sapisid ->
                append("Authorization", sapisidHash(VoiceEndpoints.Origin, sapisid))
            }
        }
    }

    // ----------------------------------------------------------------
    // Cookie refresh from Set-Cookie response headers
    // ----------------------------------------------------------------

    private val ignoredCookieNames = setOf("__Secure-1PSIDCC", "__Secure-3PSIDCC", "SIDCC")

    private fun refreshCookies(resp: HttpResponse) {
        val setCookies = resp.headers.getAll("Set-Cookie")
        if (setCookies.isNullOrEmpty()) return

        val host = runCatching { URLBuilder(resp.call.request.url.toString()).host }.getOrNull() ?: return
        if (!host.endsWith(VoiceEndpoints.ApiDomain)) return

        val updated = cookies.toMutableMap()
        var significantChange = false

        for (header in setCookies) {
            val parts = header.split(';').map { it.trim() }
            val nameValue = parts.firstOrNull() ?: continue
            val eqIdx = nameValue.indexOf('=')
            if (eqIdx < 0) continue
            val name = nameValue.substring(0, eqIdx).trim()
            val value = nameValue.substring(eqIdx + 1).trim()

            val isExpired = parts.any { part ->
                val lower = part.lowercase()
                when {
                    lower.startsWith("max-age=") ->
                        (lower.removePrefix("max-age=").trim().toIntOrNull() ?: 1) <= 0
                    lower.startsWith("expires=") -> try {
                        val dateStr = part.substringAfter('=').trim()
                        val fmt = java.text.SimpleDateFormat(
                            "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US
                        )
                        val expiry = fmt.parse(dateStr)
                        expiry != null && expiry.before(java.util.Date())
                    } catch (_: Exception) { false }
                    else -> false
                }
            }
            if (isExpired) {
                if (updated.remove(name) != null) significantChange = true
                continue
            }
            if (updated[name] != value) {
                updated[name] = value
                if (name !in ignoredCookieNames) significantChange = true
            }
        }
        if (significantChange) {
            cookies = updated
            onCookiesChanged?.invoke(updated.toMap())
        }
    }

    // ----------------------------------------------------------------
    // Response decoding
    // ----------------------------------------------------------------

    private suspend fun <T : Message> decodeResponse(resp: HttpResponse, template: T): T {
        if (resp.status.value !in 200..299) {
            val body = runCatching { resp.bodyAsBytes() }.getOrNull()?.let {
                String(it, Charsets.UTF_8).take(500)
            } ?: "<no body>"
            error("HTTP ${resp.status.value}: $body")
        }
        val plainMime = resp.headers["Content-Type"]?.substringBefore(';')?.trim().orEmpty().lowercase()
        val safetyMime = resp.headers["X-Goog-Safety-Content-Type"]
            ?.substringBefore(';')?.trim().orEmpty().lowercase()
        val realMime = safetyMime.ifEmpty { plainMime }

        // utf16-chunk framing on the safety mime. Voice uses this on
        // some endpoints to defeat naive XSSI-style attacks.
        val raw: ByteArray = if (realMime == "text/plain") {
            val channel = resp.bodyAsChannel()
            Utf16ChunkReader(channel).readChunk() ?: error("empty utf16chunk body")
        } else {
            resp.bodyAsBytes()
        }

        @Suppress("UNCHECKED_CAST")
        return when (realMime) {
            "application/x-protobuf" -> {
                // Tolerate the base64-wrapped-protobuf variant Voice
                // sometimes serves (real MIME protobuf, declared MIME
                // text/plain because of the safety header).
                val bytes = if (plainMime == "text/plain") {
                    Base64.decode(raw, Base64.DEFAULT)
                } else raw
                template.parserForType.parseFrom(bytes) as T
            }
            "application/json+protobuf", "text/plain" -> {
                val builder = template.newBuilderForType()
                PbLite.decode<T>(String(raw, Charsets.UTF_8), builder)
            }
            else -> error("unknown response content-type: $realMime")
        }
    }

    companion object {
        private const val TAG = "GVoice/Rpc"
        private const val MAX_RETRIES = 10

        /** Same algorithm as libgm's SAPISIDHASH. Reproduced inline to
         *  avoid a cross-module import. */
        internal fun sapisidHash(origin: String, sapisid: String): String {
            val ts = System.currentTimeMillis() / 1000L
            val toHash = "$ts $sapisid $origin"
            val md = MessageDigest.getInstance("SHA-1")
            val hash = md.digest(toHash.toByteArray(Charsets.UTF_8))
            val hex = hash.joinToString("") { "%02x".format(it) }
            return "SAPISIDHASH ${ts}_$hex"
        }
    }
}
