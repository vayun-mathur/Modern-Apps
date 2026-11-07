package com.vayunmathur.appstore.data.play

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PlayHttpClient against a loopback HTTP server: status/body/header
 * plumbing, query construction, and POST bodies. No external network.
 * (429/503 retry pacing is deliberately not exercised: it sleeps for
 * seconds by design.)
 */
@RunWith(RobolectricTestRunner::class)
class PlayHttpClientLocalhostTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val lastQuery = AtomicReference<String?>(null)
    private val lastBody = AtomicReference<ByteArray?>(null)
    private val lastMethod = AtomicReference<String?>(null)

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { exchange ->
            lastQuery.set(exchange.requestURI.rawQuery)
            lastMethod.set(exchange.requestMethod)
            lastBody.set(exchange.requestBody.readAllBytes())
            val bytes = "hello-play".toByteArray()
            exchange.responseHeaders.add("X-Test-Header", "yes")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/redir") { exchange ->
            exchange.responseHeaders.add("Location", "/ok")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/missing") { exchange ->
            val bytes = "gone".toByteArray()
            exchange.sendResponseHeaders(404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    @Test
    fun `get returns the body and records the response code`() {
        val client = PlayHttpClient()
        val response = client.get("$base/ok", mapOf("X-From" to "test"))
        assertEquals(200, response.code)
        assertEquals("hello-play", response.responseBytes.decodeToString())
        assertEquals(200, client.responseCode.value)
        assertEquals("GET", lastMethod.get())
    }

    @Test
    fun `get with params appends the query string`() {
        val client = PlayHttpClient()
        client.get("$base/ok", emptyMap(), mapOf("q" to "signal", "page" to "2"))
        assertEquals("q=signal&page=2", lastQuery.get())
    }

    @Test
    fun `get with a param string appends it verbatim`() {
        val client = PlayHttpClient()
        client.get("$base/ok", emptyMap(), "?raw=1")
        assertEquals("raw=1", lastQuery.get())
    }

    @Test
    fun `post sends the body bytes`() {
        val client = PlayHttpClient()
        val response = client.post("$base/ok", emptyMap(), "payload".toByteArray())
        assertEquals(200, response.code)
        assertEquals("payload", lastBody.get()?.decodeToString())
        assertEquals("POST", lastMethod.get())
    }

    @Test
    fun `a 404 is surfaced as the response not an exception`() {
        val client = PlayHttpClient()
        val response = client.get("$base/missing", emptyMap())
        assertEquals(404, response.code)
        assertEquals(false, response.isSuccessful)
        // Error bodies are carried separately from successful response bytes.
        assertTrue(response.errorBytes.decodeToString().contains("gone"))
    }

    @Test
    fun `a redirect is followed to the final body`() {
        val client = PlayHttpClient()
        val response = client.get("$base/redir", emptyMap())
        assertEquals(200, response.code)
        assertEquals("hello-play", response.responseBytes.decodeToString())
    }

    @Test
    fun `throttled starts false`() {
        assertEquals(false, PlayHttpClient.throttled.value)
    }
}
