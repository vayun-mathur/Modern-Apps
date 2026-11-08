package com.vayunmathur.share.network.airdrop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirDropHttpTest {

    @Test
    fun `parses a content-length request and leaves the next one aligned`() {
        val wire = "POST /Ask HTTP/1.1\r\nHost: [fe80::1]:8770\r\nContent-Length: 3\r\n\r\nabc" +
            "POST /Upload HTTP/1.1\r\nConnection: close\r\n\r\n"
        val input = ByteArrayInputStream(wire.toByteArray())
        val first = AirDropHttp.readRequest(input)!!
        assertEquals("/Ask", first.path)
        assertEquals("[fe80::1]:8770", first.headers["HOST"])
        assertEquals("abc", first.readBody().decodeToString())
        val second = AirDropHttp.readRequest(input)!!
        assertEquals("/Upload", second.path)
        assertEquals(false, second.headers.keepAlive)
        assertNull(AirDropHttp.readRequest(input))
    }

    @Test
    fun `chunked body round trips through the writer and reader`() {
        val payload = ByteArray(70_000) { (it % 97).toByte() }
        val out = ByteArrayOutputStream()
        AirDropHttp.writeRequestHead(out, "POST", "/Upload", listOf("Transfer-Encoding" to "chunked"))
        val chunked = AirDropHttp.ChunkedOutputStream(out)
        chunked.write(payload, 0, 1000)
        chunked.write(payload, 1000, payload.size - 1000)
        chunked.finish()
        val req = AirDropHttp.readRequest(ByteArrayInputStream(out.toByteArray()))!!
        assertTrue(req.headers.isChunked)
        assertArrayEquals(payload, req.readBody(limit = 100_000))
    }

    @Test
    fun `response round trips with its body`() {
        val out = ByteArrayOutputStream()
        AirDropHttp.writeResponse(out, AirDropHttp.Response(401), keepAlive = true)
        AirDropHttp.writeResponse(out, AirDropHttp.Response(200, body = "ok".toByteArray()), keepAlive = false)
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(401, AirDropHttp.readResponse(input).status)
        val ok = AirDropHttp.readResponse(input)
        assertTrue(ok.isSuccessful)
        assertEquals("ok", ok.body.decodeToString())
    }

    @Test
    fun `response round trips with its headers`() {
        val out = ByteArrayOutputStream()
        AirDropHttp.writeResponse(
            out,
            AirDropHttp.Response(
                200,
                mapOf("Content-Type" to "application/x-apple-binary-plist"),
                "ok".toByteArray(),
            ),
            keepAlive = true,
        )
        val resp = AirDropHttp.readResponse(ByteArrayInputStream(out.toByteArray()))
        assertEquals("application/x-apple-binary-plist", resp.headers["content-type"])
    }

    @Test
    fun `self signed certificate parses and verifies`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = AirDropTls.selfSignedCertificate(pair, "Pixel")
        cert.verify(pair.public)
        cert.checkValidity()
        assertEquals(3, cert.version)
        assertEquals("CN=Pixel", cert.subjectX500Principal.name)
    }
}
