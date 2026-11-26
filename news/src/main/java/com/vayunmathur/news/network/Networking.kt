package com.vayunmathur.news.network

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.StaticMirror
import java.io.IOException

/**
 * The news app's single networking entry point. Owns the client, the
 * browser-like request headers publisher CDNs expect, and status mapping.
 *
 * Feed path is the server's static copy of the feed on R2 at
 * [STATIC_FEED_URL] (`https://static.maproj.org/news/v1/news.json`,
 * republished by the server on every 12-minute refresh), read through
 * [StaticMirror]. There is no origin fallback: the origin serves
 * nothing for news. When the static copy is missing, failing, or older
 * than [STATIC_MAX_AGE_MS], [fetchServerFeedText] throws and the
 * repository falls back to its on-device sitemap/RSS crawl with the
 * Room-cached feed visible throughout. Article bodies still fetch
 * publisher URLs directly on-device.
 *
 * Trust stays SYSTEM (initialised in MainActivity), not FIRST_PARTY:
 * FIRST_PARTY pins only cover api.maproj.org and would break every
 * publisher fetch, while SYSTEM (platform roots) covers
 * static.maproj.org and the publisher hosts. Narrowing would need a
 * per-host client, which NetworkClient does not offer.
 */
object Networking {
    /** The server's feed on R2 (the only distribution copy); key `news/v1/news.json` in the static bucket. */
    const val STATIC_FEED_URL = "${StaticMirror.BASE}/news/v1/news.json"

    /**
     * Older static copies mean the server stopped republishing them
     * (refresh is every 12 min): treat the feed as unavailable so the
     * repository crawls on-device instead. Five missed refresh cycles
     * of slack, then the crawl wins.
     */
    const val STATIC_MAX_AGE_MS = 60L * 60 * 1000
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) NewsReader",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-GB,en;q=0.8",
    )

    /**
     * The merged server feed as text: the R2 static copy only, fresh
     * within [STATIC_MAX_AGE_MS]. Throws [IOException] when the copy is
     * missing, failing, or stale, so the repository's failure handling
     * (cached feed, then on-device crawl) takes over.
     */
    suspend fun fetchServerFeedText(): String {
        val response = StaticMirror.getStatic(STATIC_FEED_URL, STATIC_MAX_AGE_MS)
            ?: throw IOException("static news feed unavailable or stale: $STATIC_FEED_URL")
        return response.text
    }

    /**
     * GET [url] as text; throws [IOException] on transport failure or non-2xx.
     * Explicit per-request timeouts (the client's pooled defaults are
     * 10s connect / 30s read, and a read timeout only bounds stalls
     * between bytes — NewsRepository additionally bounds each whole
     * source fetch, see its SOURCE_TIMEOUT_MS).
     */
    suspend fun fetchText(url: String): String {
        val response = NetworkClient.execute(
            url, headers = headers,
            connectTimeoutMs = CONNECT_TIMEOUT_MS, readTimeoutMs = READ_TIMEOUT_MS,
        )
        if (!response.isSuccess) throw IOException("HTTP ${response.status} for $url")
        return response.text
    }

    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val READ_TIMEOUT_MS = 15_000L
}
