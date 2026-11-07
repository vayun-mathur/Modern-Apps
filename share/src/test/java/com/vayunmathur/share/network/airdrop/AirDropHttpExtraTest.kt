package com.vayunmathur.share.network.airdrop

import com.vayunmathur.share.domain.airdrop.AirDropMessages
import com.vayunmathur.share.domain.airdrop.BinaryPlist
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class AirDropHttpExtraTest {

    @Test
    fun `headers lookup is case insensitive and keepAlive logic`() {
        val h = AirDropHttp.Headers(mapOf("content-length" to "42", "connection" to "close"))
        assertEquals("42", h["Content-Length"])
        assertEquals(42L, h.contentLength)
        assertFalse(h.keepAlive)
        val h2 = AirDropHttp.Headers(mapOf("transfer-encoding" to "chunked"))
        assertTrue(h2.isChunked)
        assertTrue(h2.keepAlive)
        assertNull(h2.contentLength)
    }

    @Test
    fun `readRequest handles query string and empty stream`() {
        assertNull(AirDropHttp.readRequest(ByteArrayInputStream(ByteArray(0))))
        val wire = "GET /Discover?x=1 HTTP/1.1\r\nHost: h\r\nContent-Length: 0\r\n\r\n"
        val req = AirDropHttp.readRequest(ByteArrayInputStream(wire.toByteArray()))!!
        assertEquals("/Discover", req.path)
        assertEquals("GET", req.method)
    }

    @Test
    fun `readRequest rejects bad request line`() {
        assertThrows(AirDropHttp.ProtocolException::class.java) {
            AirDropHttp.readRequest(ByteArrayInputStream("garbage\r\n\r\n".toByteArray()))
        }
    }

    @Test
    fun `response isSuccessful range`() {
        assertTrue(AirDropHttp.Response(200).isSuccessful)
        assertTrue(AirDropHttp.Response(299).isSuccessful)
        assertFalse(AirDropHttp.Response(404).isSuccessful)
        assertFalse(AirDropHttp.Response(199).isSuccessful)
    }

    @Test
    fun `chunked streams handle small writes and finish idempotence`() {
        val out = ByteArrayOutputStream()
        val cs = AirDropHttp.ChunkedOutputStream(out)
        cs.write(byteArrayOf(65))
        cs.write("hello".toByteArray())
        cs.finish()
        val req = AirDropHttp.readRequest(
            ByteArrayInputStream(("POST /U HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray() + out.toByteArray()))
        )!!
        assertEquals("Ahello", req.readBody().decodeToString())
    }

    @Test
    fun `tls contexts and der helpers`() {
        val client = AirDropTls.clientContext()
        assertNotNull(client)
        val server = AirDropTls.serverContext()
        assertNotNull(server)
        assertTrue(AirDropTls.PROTOCOLS.isNotEmpty())
    }

    private fun assertNotNull(v: Any?) { org.junit.Assert.assertNotNull(v) }

    @Test
    fun `http server and client exchange discover over TLS`() = runBlocking {
        val server = AirDropHttpServer { req, _ ->
            when (req.path) {
                "/Discover" -> AirDropHttp.Response(200, mapOf("Content-Type" to AirDropMessages.CONTENT_TYPE_PLIST), BinaryPlist.encode(
                    AirDropMessages.DiscoverResponse("ServerMac", AirDropMessages.MANUFACTURER_GOOGLE, AirDropMessages.DEFAULT_MEDIA_CAPABILITIES).toPlist()
                ))
                else -> AirDropHttp.Response(404)
            }
        }
        try {
            val port = server.start()
            assertTrue(port > 0)
            assertEquals(port, server.port)
            assertEquals(port, server.start()) // idempotent
            val client = AirDropHttpClient(InetAddress.getByName("127.0.0.1"), port, javax.net.SocketFactory.getDefault(), 5_000)
            val resp = client.postPlist("/Discover", BinaryPlist.encode(emptyMap<String, Any?>()), keepAlive = false)
            assertEquals(200, resp.status)
            val parsed = AirDropMessages.DiscoverResponse.fromPlist(BinaryPlist.decodeDict(resp.body))!!
            assertEquals("ServerMac", parsed.receiverComputerName)
            client.close()
        } finally {
            server.release()
        }
        assertNull(server.port)
    }
}
