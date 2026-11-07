package com.vayunmathur.auto.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamTransportAndCredentialTest {

    @Test
    fun `stream transport reads writes flushes and closes both`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        val output = ByteArrayOutputStream()
        val transport = StreamTransport(input, output)
        val buffer = ByteArray(2)
        assertEquals(2, transport.read(buffer, 0, 2))
        assertContentEquals(byteArrayOf(1, 2), buffer)
        assertEquals(2, transport.read(buffer, 0, 2))
        assertEquals(-1, transport.read(buffer, 0, 2))
        transport.write(byteArrayOf(9, 8, 7), 1, 1)
        transport.flush()
        assertContentEquals(byteArrayOf(8), output.toByteArray())
        transport.close()
    }

    @Test
    fun `close still closes output when input close throws`() {
        val throwingInput = object : InputStream() {
            override fun read(): Int = -1
            override fun close(): Unit = throw IOException("input close failed")
        }
        val output = ByteArrayOutputStream()
        var outputClosed = false
        val trackingOutput = object : java.io.OutputStream() {
            override fun write(b: Int) = output.write(b)
            override fun close() { outputClosed = true }
        }
        val transport = StreamTransport(throwingInput, trackingOutput)
        assertFailsWith<IOException> { transport.close() }
        assertTrue(outputClosed)
    }

    @Test
    fun `read and write honour offsets`() {
        val transport = StreamTransport(ByteArrayInputStream(byteArrayOf(5, 6, 7)), ByteArrayOutputStream())
        val buffer = byteArrayOf(0, 0, 0, 0)
        assertEquals(3, transport.read(buffer, 1, 3))
        assertContentEquals(byteArrayOf(0, 5, 6, 7), buffer)
    }

    private fun asset(name: String): String = File("src/main/assets/gal", name).readText()

    @Test
    fun `credential asset names and expiry arithmetic`() {
        assertEquals("gal/client-cert.pem", GalCredential.CERT_ASSET)
        assertEquals("gal/client-key.pem", GalCredential.KEY_ASSET)
        assertEquals("gal/root.pem", GalCredential.ROOT_ASSET)
        val cert = asset("client-cert.pem")
        val notAfter = GalCredential.notAfterMillis(cert)
        assertTrue(notAfter > 0L)
        assertEquals(0L, GalCredential.daysRemaining(cert, notAfter))
        assertEquals(30L, GalCredential.daysRemaining(cert, notAfter - 30L * 86_400_000L - 3_600_000L))
        assertEquals(-2L, GalCredential.daysRemaining(cert, notAfter + 2L * 86_400_000L))
        // A context built from the shipped PEMs exposes TLSv1.2 engines.
        val context = GalCredential.create(cert, asset("client-key.pem"), asset("root.pem"))
        assertEquals("TLSv1.2", context.protocol)
    }

    @Test
    fun ` malformed private key is rejected`() {
        assertFailsWith<java.security.spec.InvalidKeySpecException> {
            GalCredential.create(asset("client-cert.pem"), "-----BEGIN PRIVATE KEY-----\naGVsbG8=\n-----END PRIVATE KEY-----", asset("root.pem"))
        }
    }
}
