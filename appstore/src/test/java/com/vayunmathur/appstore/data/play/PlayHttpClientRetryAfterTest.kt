package com.vayunmathur.appstore.data.play

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PlayHttpClient refusal handling against a loopback server: a 429
 * carrying `Retry-After: 1` is waited out for about that long and the
 * retry succeeds; the params-post and auth variants reach the server
 * with the right method and query.
 */
@RunWith(RobolectricTestRunner::class)
class PlayHttpClientRetryAfterTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val throttledHits = AtomicInteger(0)
    private val seen = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/throttled") { exchange ->
            if (throttledHits.incrementAndGet() == 1) {
                exchange.responseHeaders.add("Retry-After", "1")
                exchange.sendResponseHeaders(429, -1)
            } else {
                val body = "recovered".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.createContext("/echo") { exchange ->
            val entry = "${exchange.requestMethod} ${exchange.requestURI}"
            synchronized(seen) { seen += entry }
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    @Test
    fun `a retry-after refusal is waited out and the retry succeeds`() {
        val client = PlayHttpClient()
        val started = System.currentTimeMillis()
        val response = client.get("$base/throttled", emptyMap())
        val waited = System.currentTimeMillis() - started
        assertTrue(response.isSuccessful, "code=${response.code} err=${response.errorString}")
        assertEquals("recovered", response.responseBytes.toString(Charsets.UTF_8))
        assertEquals(2, throttledHits.get())
        assertTrue(waited >= 900, "Retry-After was not honoured (waited ${waited}ms)")
    }

    @Test
    fun `post with params encodes them in the query`() {
        val client = PlayHttpClient()
        val response = client.post(
            "$base/echo",
            mapOf("X-Test" to "1"),
            mapOf("a" to "1", "b" to "two"),
        )
        assertTrue(response.isSuccessful)
        assertTrue(seen.any { it.startsWith("POST /echo?") && it.contains("a=1") && it.contains("b=two") }, seen.toString())
    }

    @Test
    fun `auth variants issue their requests`() {
        val client = PlayHttpClient()
        val got = client.getAuth("$base/echo")
        assertTrue(got.isSuccessful)
        val posted = client.postAuth("$base/echo", "{}".toByteArray())
        assertTrue(posted.isSuccessful)
        assertEquals(2, synchronized(seen) { seen.size })
    }
}
