package com.vayunmathur.library.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** NativeHttpBridge wire format + NetworkClient init/JSON-empty paths over loopback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkBridgeLoopbackTest {

    private fun packPairs(vararg pairs: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((k, v) in pairs) {
            val kb = k.toByteArray(); val vb = v.toByteArray()
            out.write((kb.size shr 8) and 0xFF); out.write(kb.size and 0xFF); out.write(kb)
            out.write((vb.size shr 8) and 0xFF); out.write(vb.size and 0xFF); out.write(vb)
        }
        return out.toByteArray()
    }

    private data class Reply(val status: Int, val url: String, val headers: String, val body: ByteArray)
    private fun unpackReply(frame: ByteArray): Reply {
        val buf = ByteBuffer.wrap(frame)
        val status = buf.short.toInt() and 0xFFFF
        val urlLen = buf.int; val urlBytes = ByteArray(urlLen); buf.get(urlBytes)
        val hdrLen = buf.int; val hdrBytes = ByteArray(hdrLen); buf.get(hdrBytes)
        val bodyLen = buf.int; val body = ByteArray(bodyLen); buf.get(body)
        return Reply(status, String(urlBytes, Charsets.UTF_8), String(hdrBytes, Charsets.UTF_8), body)
    }

    @Test
    fun bridgeGetPostHeadHeadersAndFailure() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/echo") { ex ->
            val req = ex.requestBody.readBytes()
            ex.responseHeaders.add("X-Method", ex.requestMethod)
            ex.responseHeaders.add("X-Seen", ex.requestHeaders.getFirst("X-Bridge") ?: "none")
            val out = if (ex.requestMethod == "HEAD") ByteArray(0) else "resp:".toByteArray() + req
            ex.sendResponseHeaders(207, if (ex.requestMethod == "HEAD") -1 else out.size.toLong())
            if (out.isNotEmpty()) ex.responseBody.use { it.write(out) }
            ex.close()
        }
        server.start()
        try {
            NetworkClient.initWithFactory(null)
            val get = unpackReply(NativeHttpBridge.request(0, "$base/echo", packPairs("X-Bridge" to "yes", "Accept" to "text/plain"), null))
            assertEquals(207, get.status); assertEquals("$base/echo", get.url); assertTrue(get.headers.isNotEmpty()); assertEquals("resp:", get.body.toString(Charsets.UTF_8))
            val post = unpackReply(NativeHttpBridge.request(1, "$base/echo", null, "payload".toByteArray()))
            assertEquals("resp:payload", post.body.toString(Charsets.UTF_8))
            val head = unpackReply(NativeHttpBridge.request(2, "$base/echo", ByteArray(0), null))
            assertEquals(207, head.status); assertEquals(0, head.body.size)
            // unknown method code falls back to GET
            val unknown = unpackReply(NativeHttpBridge.request(99, "$base/echo", packPairs("X-Bridge" to "u"), null))
            assertEquals(207, unknown.status)
            // truncated header block: only a name without value -> no headers, request still succeeds
            val truncated = unpackReply(NativeHttpBridge.request(0, "$base/echo", byteArrayOf(0, 5, 'h'.code.toByte()), null))
            assertEquals(207, truncated.status)
            // failure: unparseable / unreachable URL -> status 0, reason as body, never throws
            val failed = unpackReply(NativeHttpBridge.request(0, "http://127.0.0.1:1/never", null, null))
            assertEquals(0, failed.status); assertTrue(failed.body.isNotEmpty())
            // batch: two ranges in order, one bad range value still returns a frame per slot
            val batch = NativeHttpBridge.requestBatch("$base/echo", arrayOf("bytes=0-1", "bytes=2-3"))
            val replies = NativeHttpBridge.unpackBatch(batch)!!
            assertEquals(2, replies.size)
            assertTrue(replies.all { unpackReply(it).status in setOf(200, 206, 207) })
            // unpackBatch rejects truncation / trailing garbage / empty
            assertEquals(null, NativeHttpBridge.unpackBatch(ByteArray(2)))
            assertEquals(null, NativeHttpBridge.unpackBatch(batch + byteArrayOf(1)))
            assertEquals(null, NativeHttpBridge.unpackBatch(ByteArray(0)))
            assertEquals(emptyList(), NativeHttpBridge.unpackBatch(NativeHttpBridge.requestBatch("$base/echo", emptyArray())))
        } finally { server.stop(0) }
    }

    @Test
    fun initSystemBundleAndJsonEmptyBooleanPaths() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NetworkClient.init(context, TrustBundle.SYSTEM)
        assertEquals(TrustBundle.SYSTEM, NetworkClient.currentBundle)
        assertEquals(null, NetworkClient.defaultSslSocketFactory)
        // re-init same bundle is a no-op and keeps state
        NetworkClient.init(context, TrustBundle.SYSTEM)
        assertEquals(TrustBundle.SYSTEM, NetworkClient.currentBundle)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/blank") { ex -> val b = " \n\t ".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }; ex.close() }
        server.createContext("/true") { ex -> val b = "true".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }; ex.close() }
        server.createContext("/nocontent") { ex -> ex.sendResponseHeaders(204, -1); ex.close() }
        server.start()
        try {
            // whitespace-only body counts as empty: Boolean -> isSuccess, Unit -> Unit
            assertTrue(NetworkClient.callJson<Boolean>("$base/blank"))
            assertEquals(Unit, NetworkClient.callJson<Unit>("$base/blank"))
            assertTrue(NetworkClient.callJson<Boolean>("$base/true"))
            assertTrue(NetworkClient.callJson<Boolean>("$base/nocontent"))
            assertEquals(Unit, NetworkClient.callJson<Unit>("$base/nocontent"))
            // getContentLength on a 404 without length -> null, and on unreachable -> exception surfaces or null per engine; just exercise 404
            server.createContext("/missing") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
            assertEquals(null, NetworkClient.getContentLength("$base/missing"))
        } finally { server.stop(0) }
        assertFalse(NetworkClient.currentBundle != TrustBundle.SYSTEM)
    }
}
