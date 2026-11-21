package com.vayunmathur.library.network

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/**
 * Central retry (NetworkRetry): status/IOException retry, idempotency gate,
 * `Retry-After` handling and give-up. Backoff logic runs through [withRetry]
 * with a recording sleeper (no wall-clock waits); the [NetworkClient]
 * wrappers run against a local server like the other engine tests.
 */
class NetworkRetryTest {

    private lateinit var server: HttpServer
    private var baseUrl = ""

    /** Deterministic policy: no jitter, no real delay at the wrapper level. */
    private val fastPolicy = RetryPolicy(
        maxAttempts = 3,
        initialDelayMs = 1L,
        maxDelayMs = 10L,
        jitterMs = 0L,
    )

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun response(status: Int, retryAfter: String? = null): RawResponse {
        val headers = if (retryAfter == null) {
            emptyMap()
        } else {
            mapOf(RETRY_AFTER_HEADER to listOf(retryAfter))
        }
        return RawResponse(status, "", ByteArray(0), headers, "http://localhost/")
    }

    @Test
    fun retries_on_503_then_succeeds() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val result = withRetry(
            policy = fastPolicy,
            shouldRetry = { it.status in fastPolicy.retryableStatuses },
            retryAfterMsOf = { RetryAfter.parseMs(it.header(RETRY_AFTER_HEADER)) },
            sleeper = { sleeps.add(it) },
        ) {
            calls++
            if (calls < 3) response(503) else response(200)
        }
        assertEquals(200, result.status)
        assertEquals(3, calls)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun retry_after_header_is_honoured_over_backoff() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 1L, maxDelayMs = 60_000L, jitterMs = 0L)
        val result = withRetry(
            policy = policy,
            shouldRetry = { it.status == 429 },
            retryAfterMsOf = { RetryAfter.parseMs(it.header(RETRY_AFTER_HEADER)) },
            sleeper = { sleeps.add(it) },
        ) {
            calls++
            if (calls == 1) response(429, retryAfter = "2") else response(200)
        }
        assertEquals(200, result.status)
        assertEquals(listOf(2_000L), sleeps)
    }

    @Test
    fun gives_up_after_max_attempts_and_returns_last_response() = runBlocking {
        var calls = 0
        val result = withRetry(
            policy = fastPolicy,
            shouldRetry = { it.status == 503 },
            sleeper = { },
        ) {
            calls++
            response(503)
        }
        assertEquals(503, result.status)
        assertEquals(fastPolicy.maxAttempts, calls)
    }

    @Test
    fun gives_up_after_max_attempts_and_rethrows_ioexception() = runBlocking {
        var calls = 0
        assertFailsWith<IOException> {
            withRetry<RawResponse>(
                policy = fastPolicy,
                shouldRetry = { false },
                sleeper = { },
            ) {
                calls++
                throw IOException("boom $calls")
            }
        }
        assertEquals(fastPolicy.maxAttempts, calls)
    }

    @Test
    fun non_retryable_status_is_returned_immediately() = runBlocking {
        var calls = 0
        val result = withRetry(
            policy = fastPolicy,
            shouldRetry = { it.status in fastPolicy.retryableStatuses },
            sleeper = { },
        ) {
            calls++
            response(404)
        }
        assertEquals(404, result.status)
        assertEquals(1, calls)
    }

    @Test
    fun executeWithRetry_retries_get_on_503_then_succeeds() = runBlocking {
        val hits = AtomicInteger()
        server.createContext("/flaky") { exchange ->
            val n = hits.incrementAndGet()
            if (n < 3) {
                exchange.sendResponseHeaders(503, -1)
            } else {
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        val result = NetworkClient.executeWithRetry("$baseUrl/flaky", retryPolicy = fastPolicy)
        assertEquals(200, result.status)
        assertEquals("ok", result.text)
        assertEquals(3, hits.get())
    }

    @Test
    fun executeWithRetry_does_not_retry_post_without_idempotency_key() = runBlocking {
        val hits = AtomicInteger()
        server.createContext("/submit") { exchange ->
            hits.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        val result = NetworkClient.executeWithRetry(
            "$baseUrl/submit",
            method = "POST",
            body = "payload",
            retryPolicy = fastPolicy,
        )
        assertEquals(503, result.status)
        assertEquals(1, hits.get())
    }

    @Test
    fun executeWithRetry_retries_post_with_idempotency_key_and_sends_header() = runBlocking {
        val hits = AtomicInteger()
        val seenKeys = mutableListOf<String?>()
        server.createContext("/submit-keyed") { exchange ->
            val n = hits.incrementAndGet()
            synchronized(seenKeys) { seenKeys.add(exchange.requestHeaders.getFirst(IDEMPOTENCY_KEY_HEADER)) }
            exchange.requestBody.use { it.readBytes() }
            if (n < 2) {
                exchange.sendResponseHeaders(503, -1)
            } else {
                exchange.sendResponseHeaders(200, -1)
            }
            exchange.close()
        }
        val result = NetworkClient.executeWithRetry(
            "$baseUrl/submit-keyed",
            method = "POST",
            body = "payload",
            retryPolicy = fastPolicy,
            idempotencyKey = "booking-123",
        )
        assertEquals(200, result.status)
        assertEquals(2, hits.get())
        assertEquals(listOf<String?>("booking-123", "booking-123"), synchronized(seenKeys) { seenKeys.toList() })
    }

    @Test
    fun performRequestWithRetry_retries_get_on_503_then_succeeds() = runBlocking {
        val hits = AtomicInteger()
        server.createContext("/flaky-text") { exchange ->
            val n = hits.incrementAndGet()
            if (n < 2) {
                exchange.sendResponseHeaders(503, -1)
            } else {
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        val result = NetworkClient.performRequestWithRetry("$baseUrl/flaky-text", retryPolicy = fastPolicy)
        assertEquals(200, result.status)
        assertEquals("ok", result.body)
        assertEquals(2, hits.get())
    }
}
