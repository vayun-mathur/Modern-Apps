package com.vayunmathur.library.network

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer

/**
 * Static copies of user-independent server answers on Cloudflare R2.
 *
 * The server publishes everything whose answer is the same for every user —
 * video-catalog shards, rates, the TMDB catalog, traffic squares — to a public
 * bucket served at [BASE] (an R2 custom domain on the same Cloudflare zone as
 * `api.maproj.org`, so the `FIRST_PARTY` pins cover it). Apps read the static
 * object first and fall back to the old endpoint when it is missing, failing,
 * or older than they accept, so server cost stops growing with users and
 * nothing breaks before the bucket goes live (location_share_server
 * `src/static_r2`, `cloudflare/STATIC-R2.md`).
 */
object StaticMirror {
    /** Public origin of the static bucket. */
    const val BASE = "https://static.maproj.org"

    /** Test seam: when set, serves every request instead of [NetworkClient]. */
    internal var transport: (suspend (url: String) -> RawResponse)? = null

    private suspend fun fetch(url: String): RawResponse =
        transport?.invoke(url) ?: NetworkClient.execute(url)

    /**
     * The static object at [staticUrl] when it is usable ([accept]), else null —
     * for callers whose fallback is not a plain GET, or for whom a 200 is
     * authoritative (e.g. a catalog shard that simply lacks the item).
     */
    suspend fun getStatic(
        staticUrl: String,
        maxAgeMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): RawResponse? = runCatching { fetch(staticUrl) }.getOrNull()?.takeIf { accept(it, maxAgeMs, nowMs) }

    /**
     * GET [staticUrl]; when that throws, is not 2xx, or — with [maxAgeMs] — has no
     * `Last-Modified` within that age, GET [fallbackUrl] instead and return its
     * response whatever its status.
     */
    suspend fun get(
        staticUrl: String,
        fallbackUrl: String,
        maxAgeMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): RawResponse = getStatic(staticUrl, maxAgeMs, nowMs) ?: fetch(fallbackUrl)

    /**
     * [get] decoded as JSON with the shared lenient config. A static copy that
     * does not decode also falls back. A non-2xx fallback throws an
     * `IOException("HTTP <status>: ...")`, like [NetworkClient.getJson].
     */
    suspend fun <T> getJson(
        staticUrl: String,
        fallbackUrl: String,
        deserializer: DeserializationStrategy<T>,
        maxAgeMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): T {
        getStatic(staticUrl, maxAgeMs, nowMs)?.let { primary ->
            runCatching { NetworkClient.jsonConfig.decodeFromString(deserializer, primary.text) }
                .onSuccess { return it }
        }
        val resp = fetch(fallbackUrl)
        if (!resp.isSuccess) {
            throw java.io.IOException("HTTP ${resp.status}: ${resp.text.take(ERROR_PREFIX_CHARS)}")
        }
        return NetworkClient.jsonConfig.decodeFromString(deserializer, resp.text)
    }

    /** Reified [getJson]. */
    suspend inline fun <reified T> getJson(staticUrl: String, fallbackUrl: String, maxAgeMs: Long? = null): T =
        getJson(staticUrl, fallbackUrl, serializer<T>(), maxAgeMs)

    /** Whether a static response is usable: 2xx and, with [maxAgeMs], fresh enough. */
    fun accept(resp: RawResponse, maxAgeMs: Long?, nowMs: Long): Boolean {
        if (!resp.isSuccess) return false
        if (maxAgeMs == null) return true
        val modified = resp.header("Last-Modified")?.let(::parseHttpDate) ?: return false
        return nowMs - modified <= maxAgeMs
    }

    /** RFC 1123 HTTP date (`Fri, 13 Nov 2026 22:01:58 GMT`) to epoch millis, or null. */
    fun parseHttpDate(value: String): Long? = runCatching {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

    private const val ERROR_PREFIX_CHARS = 200
}
