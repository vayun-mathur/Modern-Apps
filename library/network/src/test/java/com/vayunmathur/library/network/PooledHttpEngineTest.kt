package com.vayunmathur.library.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Covers [PooledHttpEngine], the pooled OkHttp transport behind [NetworkClient.execute]
 * (issue 815). A JDK in-process server keeps this hermetic — no network, no extra test
 * dependency. Only routing, mapping and redirect semantics are asserted here; connection
 * reuse itself is verified on device through the per-range timing logs.
 */
class PooledHttpEngineTest {

    private lateinit var server: HttpServer
    private var baseUrl = ""

    @BeforeTest
    fun setUp() {
        PooledHttpEngine.USE_POOLED_ENGINE = true
        PooledHttpEngine.resetForTests()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        PooledHttpEngine.USE_POOLED_ENGINE = true
        PooledHttpEngine.resetForTests()
    }

    @Test
    fun range_request_maps_status_headers_and_body() {
        runBlocking {
        val body = ByteArray(4096) { (it % 251).toByte() }
        server.createContext("/range") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            assertEquals("bytes=0-127", range)
            exchange.responseHeaders.add("Content-Range", "bytes 0-127/4096")
            exchange.sendResponseHeaders(206, 128)
            exchange.responseBody.use { it.write(body, 0, 128) }
        }
        val response = NetworkClient.execute(
            url = "$baseUrl/range",
            headers = mapOf("Range" to "bytes=0-127"),
        )
        assertEquals(206, response.status)
        assertContentEquals(body.copyOf(128), response.bytes)
        assertEquals(listOf("bytes 0-127/4096"), response.headerValues("Content-Range"))
        assertTrue(PooledHttpEngine.lastUsedPooled, "execute routes through the pool")
        }
    }

    @Test
    fun redirects_are_followed_and_final_url_reported() {
        runBlocking {
        server.createContext("/old") { exchange ->
            exchange.responseHeaders.add("Location", "$baseUrl/new")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/new") { exchange ->
            val body = "arrived".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val response = NetworkClient.execute(url = "$baseUrl/old")
        assertEquals(200, response.status)
        assertEquals("arrived", response.text)
        assertEquals("$baseUrl/new", response.url)
        }
    }

    @Test
    fun kill_switch_off_keeps_the_historical_path() {
        runBlocking {
        PooledHttpEngine.USE_POOLED_ENGINE = false
        server.createContext("/plain") { exchange ->
            val body = "legacy".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val response = NetworkClient.execute(url = "$baseUrl/plain")
        assertEquals(200, response.status)
        assertEquals("legacy", response.text)
        assertFalse(PooledHttpEngine.lastUsedPooled, "kill-switch off bypasses the pool")
        }
    }

    @Test
    fun an_explicit_factory_cannot_join_the_pool() {
        assertTrue(PooledHttpEngine.canServe(null), "default trust joins the pool")
        val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
        assertFalse(
            PooledHttpEngine.canServe(factory),
            "a bare factory without its manager falls back instead of partitioning the pool",
        )
    }

    @Test
    fun per_host_concurrency_matches_maplibre() {
        assertEquals(20, PooledHttpEngine.MAX_REQUESTS_PER_HOST)
    }

    @Test
    fun pooled_body_read_is_capped_and_names_the_url() {
        val body = ByteArray(10_000) { (it % 251).toByte() }
            .toResponseBodyForTest()
        val e = kotlin.test.assertFailsWith<java.io.IOException> {
            PooledHttpEngine.readPooledBody(body, "http://127.0.0.1/big", maxBytes = 1024)
        }
        assertTrue(e.message!!.contains("http://127.0.0.1/big"), "got: ${e.message}")
    }

    @Test
    fun pooled_body_under_the_cap_reads_exact_and_null_is_empty() {
        val data = "tiles".toByteArray()
        kotlin.test.assertContentEquals(
            data,
            PooledHttpEngine.readPooledBody(data.toResponseBodyForTest(), "http://x/", maxBytes = 1024),
        )
        assertEquals(0, PooledHttpEngine.readPooledBody(null, "http://x/").size)
    }
}

private fun ByteArray.toResponseBodyForTest(): okhttp3.ResponseBody {
    return with(okhttp3.ResponseBody.Companion) { this@toResponseBodyForTest.toResponseBody(null) }
}
