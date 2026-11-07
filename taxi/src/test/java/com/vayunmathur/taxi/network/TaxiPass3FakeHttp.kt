package com.vayunmathur.taxi.network

import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.library.network.SimpleResponse
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSocketFactory

/** Records TaxiHttp calls and answers them from URL-substring routes. */
class TaxiPass3FakeHttp {
    data class Call(val url: String, val method: String, val headers: Map<String, *>, val body: Any?)

    val calls = CopyOnWriteArrayList<Call>()
    private val routes = CopyOnWriteArrayList<Pair<String, (Call) -> RawResponse>>()
    @Volatile var defaultRaw: RawResponse? = null
    @Volatile var performHandler: ((Call) -> SimpleResponse)? = null

    fun on(substring: String, handler: (Call) -> RawResponse) {
        routes += substring to handler
    }

    fun install() {
        calls.clear()
        routes.clear()
        defaultRaw = null
        performHandler = null
        TaxiHttp.executeOverride = { url, method, headers, body, _ ->
            val call = Call(url, method, headers, body)
            calls += call
            routes.firstOrNull { url.contains(it.first) }?.second?.invoke(call)
                ?: defaultRaw
                ?: RawResponse(404, "Not Found", "no fake route for $url".toByteArray(), emptyMap(), url)
        }
        TaxiHttp.performOverride = { url, method, headers, body ->
            val call = Call(url, method, headers, body)
            calls += call
            performHandler?.invoke(call)
                ?: SimpleResponse(404, "Not Found", "no fake perform route", emptyMap(), url)
        }
    }

    fun uninstall() {
        TaxiHttp.executeOverride = null
        TaxiHttp.performOverride = null
    }

    fun raw(status: Int, body: String, contentType: String = "application/json", url: String = ""): RawResponse =
        RawResponse(
            status = status,
            statusMessage = if (status in 200..299) "OK" else "Error",
            bytes = body.toByteArray(Charsets.UTF_8),
            headers = mapOf("Content-Type" to listOf(contentType)),
            url = url,
        )

    fun simple(status: Int, body: String, url: String = ""): SimpleResponse =
        SimpleResponse(status, if (status in 200..299) "OK" else "Error", body, emptyMap(), url)

    fun callsTo(substring: String): List<Call> = calls.filter { it.url.contains(substring) }
}
