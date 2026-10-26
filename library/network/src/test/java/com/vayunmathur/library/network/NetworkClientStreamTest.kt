package com.vayunmathur.library.network

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NetworkClientStreamTest {
    @Test
    fun defaultRedirectsArePreservedAndOptOutDoesNotForwardBody() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val destinationHits = AtomicInteger()
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/redirect") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Location", "$base/destination")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/destination") { exchange ->
            destinationHits.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }
        server.start()
        try {
            val default = NetworkClient.stream("$base/redirect", connectTimeoutMs = 1000) { input, _ ->
                assertEquals(2, input!!.read(ByteArray(8)))
            }
            assertEquals(200, default.status)
            assertEquals("$base/destination", default.url)
            assertEquals(1, destinationHits.get())
            val suppressed = NetworkClient.stream(
                "$base/redirect", method = "POST", body = "buyer-private-data",
                followRedirects = false, connectTimeoutMs = 1000,
            ) { input, response ->
                assertEquals(307, response.status)
                assertEquals(null, input)
            }
            assertEquals("$base/redirect", suppressed.url)
            assertEquals(1, destinationHits.get())
        } finally { server.stop(0) }
    }

    @Test
    fun closesAndDisconnectsAfterSuccessOrCallbackFailure() = runBlocking {
        val success = FakeConnection()
        NetworkClient.stream(register(success)) { input, _ -> assertEquals(1, input!!.read(ByteArray(1))) }
        assertTrue(success.closed)
        assertTrue(success.disconnected)
        val failed = FakeConnection()
        assertFailsWith<IllegalStateException> {
            NetworkClient.stream(register(failed)) { _, _ -> error("parser failure") }
        }
        assertTrue(failed.closed)
        assertTrue(failed.disconnected)
    }

    @Test
    fun cancelledCallbackStillClosesAndDisconnects() = runBlocking {
        val connection = FakeConnection()
        val opened = CompletableDeferred<Unit>()
        val job = launch {
            NetworkClient.stream(register(connection)) { _, _ ->
                opened.complete(Unit)
                awaitCancellation()
            }
        }
        opened.await()
        assertFalse(connection.closed)
        job.cancel()
        job.join()
        assertTrue(connection.closed)
        assertTrue(connection.disconnected)
    }

    @Test
    fun parentCancellationClosesLegacyStyleGetAndNestedPost() = runBlocking {
        val get = FakeConnection()
        val post = FakeConnection()
        val parent = Job(coroutineContext[Job])
        val nested = CompletableDeferred<Unit>()
        val child = CoroutineScope(coroutineContext + parent).launch {
            NetworkClient.stream(
                register(get), headers = mapOf("Accept" to "text/event-stream"), followRedirects = false,
            ) { input, _ ->
                assertEquals(1, input!!.read(ByteArray(1)))
                NetworkClient.stream(register(post), method = "POST", followRedirects = false) { _, _ ->
                    nested.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        try {
            nested.await()
            assertFalse(get.closed)
            assertFalse(post.closed)
            parent.cancel()
            child.join()
            assertTrue(child.isCancelled)
            assertTrue(get.closed && get.disconnected)
            assertTrue(post.closed && post.disconnected)
        } finally {
            parent.cancel()
            child.join()
        }
    }

    @Test
    fun parentCancellationDuringResponseAcquisitionStillClosesAndDisconnects() = runBlocking {
        val acquiring = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val connection = FakeConnection(onStatus = {
            acquiring.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "Acquisition test barrier timed out" }
        })
        val parent = Job(coroutineContext[Job])
        var callbackEntered = false
        val child = CoroutineScope(coroutineContext + parent).launch {
            NetworkClient.stream(register(connection), followRedirects = false) { _, _ -> callbackEntered = true }
        }
        try {
            acquiring.await()
            parent.cancel()
            release.countDown()
            child.join()
            assertTrue(child.isCancelled)
            assertFalse(callbackEntered)
            assertTrue(connection.closed)
            assertTrue(connection.disconnected)
        } finally {
            release.countDown()
            parent.cancel()
            child.join()
        }
    }

    private class FakeConnection(private val onStatus: () -> Unit = {}) : HttpURLConnection(URL("http://example.test/stream")) {
        @Volatile var closed = false
        @Volatile var disconnected = false
        private val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() { closed = true; super.close() }
        }
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int { onStatus(); return 200 }
        override fun getResponseMessage(): String = "OK"
        override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
        override fun getHeaderField(name: String): String? = null
        override fun getInputStream(): InputStream = input
    }

    companion object {
        private val connections = ConcurrentHashMap<String, FakeConnection>()
        private val installed = run {
            // A unique test-only scheme leaves HTTP/HTTPS and other test engines untouched.
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "ucpstreamtest") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL): URLConnection =
                        requireNotNull(connections.remove(url.toString()))
                }
            }
            true
        }
        private fun register(connection: FakeConnection): String {
            check(installed)
            val url = "ucpstreamtest://example.test/${UUID.randomUUID()}"
            connections[url] = connection
            return url
        }
    }
}
