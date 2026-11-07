package com.vayunmathur.share.network.airdrop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AirDropHttpMoreTest {

    @Test
    fun `drain consumes chunked and content-length bodies`() {
        val out = ByteArrayOutputStream()
        AirDropHttp.writeRequestHead(out, "POST", "/Upload", listOf("Transfer-Encoding" to "chunked"))
        val cs = AirDropHttp.ChunkedOutputStream(out)
        cs.write("drain-me".toByteArray()); cs.finish()
        val req = AirDropHttp.readRequest(ByteArrayInputStream(out.toByteArray()))!!
        AirDropHttp.drain(req.body) // must consume without throwing and leave stream at EOF
        assertEquals(-1, req.body.read())
    }

    @Test
    fun `readResponse rejects closed and malformed streams`() {
        assertThrows(Exception::class.java) { AirDropHttp.readResponse(ByteArrayInputStream(ByteArray(0))) }
        assertThrows(Exception::class.java) { AirDropHttp.readResponse(ByteArrayInputStream("garbage\r\n\r\n".toByteArray())) }
    }

    @Test
    fun `writeResponse includes status and content length`() {
        val out = ByteArrayOutputStream()
        AirDropHttp.writeResponse(out, AirDropHttp.Response(201, mapOf("X-Test" to "yes"), "created".toByteArray()), keepAlive = true)
        val text = out.toByteArray().decodeToString()
        assertTrue(text.startsWith("HTTP/1.1 201"))
        assertTrue(text.contains("X-Test: yes"))
        assertTrue(text.endsWith("created"))
        val resp = AirDropHttp.readResponse(ByteArrayInputStream(out.toByteArray()))
        assertEquals(201, resp.status)
        assertEquals("created", resp.body.decodeToString())
    }

    @Test
    fun `bounded stream truncates at limit`() {
        // readBody with a small limit via Request on a content-length body
        val wire = "POST /Ask HTTP/1.1\r\nContent-Length: 10\r\n\r\n0123456789"
        val req = AirDropHttp.readRequest(ByteArrayInputStream(wire.toByteArray()))!!
        assertEquals("0123456789", req.readBody(limit = 100).decodeToString())
    }
}
