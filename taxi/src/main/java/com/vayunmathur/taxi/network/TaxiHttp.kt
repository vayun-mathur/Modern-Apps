package com.vayunmathur.taxi.network

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.library.network.SimpleResponse
import javax.net.ssl.SSLSocketFactory

/**
 * The taxi module's HTTP surface: every provider call goes through here so
 * tests can substitute a fake transport instead of touching the network
 * (provider base URLs are hardcoded, so a local test server cannot be
 * reached by URL substitution). Production behavior is exactly
 * [NetworkClient]; the overrides are internal, never set in production.
 */
internal object TaxiHttp {
    @Volatile
    var executeOverride: (suspend (url: String, method: String, headers: Map<String, *>, body: Any?, sslSocketFactory: SSLSocketFactory?) -> RawResponse)? = null

    @Volatile
    var performOverride: (suspend (url: String, method: String, headers: Map<String, *>, body: Any?) -> SimpleResponse)? = null

    suspend fun execute(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        sslSocketFactory: SSLSocketFactory? = null,
    ): RawResponse = executeOverride?.invoke(url, method, headers, body, sslSocketFactory)
        ?: NetworkClient.execute(
            url = url, method = method, headers = headers, body = body,
            sslSocketFactory = sslSocketFactory,
        )

    suspend fun performRequest(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
    ): SimpleResponse = performOverride?.invoke(url, method, headers, body)
        ?: NetworkClient.performRequest(
            url = url, method = method, headers = headers, body = body,
        )
}
