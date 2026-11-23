package com.vayunmathur.news.network

import com.vayunmathur.library.network.NetworkClient
import java.io.IOException

/**
 * The news app's single networking entry point. Owns the client, the
 * browser-like request headers publisher CDNs expect, and status mapping.
 * Every fetch is a direct GET of a publisher URL — there is no backend.
 * Trust is SYSTEM, initialised in MainActivity, because publisher hosts
 * are arbitrary public sites (see TrustBundle docs).
 */
object Networking {
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) NewsReader",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-GB,en;q=0.8",
    )

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
