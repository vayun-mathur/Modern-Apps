package com.vayunmathur.uniflix.data

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.RawResponse

/**
 * Test seam for the module's plain-HTTP reads (TMDB catalog and addon
 * manifest/stream fetches): when set, requests are served by this handler
 * instead of the network, so tests never reach a real server (the CI box
 * has internet). Null in production, where [NetworkClient] serves them.
 */
internal var uniflixTransport: (suspend (url: String) -> RawResponse)? = null

internal suspend fun uniflixGet(url: String, connectTimeoutMs: Long? = null): RawResponse =
    uniflixTransport?.invoke(url)
        ?: NetworkClient.execute(url, connectTimeoutMs = connectTimeoutMs)

/** Builds a text response for [uniflixTransport] fakes. */
internal fun fakeHttpResponse(status: Int, body: String, url: String = ""): RawResponse =
    RawResponse(
        status = status,
        statusMessage = if (status in 200..299) "OK" else "Error",
        bytes = body.toByteArray(Charsets.UTF_8),
        headers = emptyMap(),
        url = url,
    )
