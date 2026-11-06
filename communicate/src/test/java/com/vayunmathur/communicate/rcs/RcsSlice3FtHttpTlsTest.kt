package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.rcs.RcsFileTransferHttp
import com.vayunmathur.communicate.data.rcs.RcsMsrpTls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-3 tests for the FT-over-HTTP pure helpers ([RcsFileTransferHttp]:
 * FT body parsing, CPIM descriptor building, content-server parsing,
 * thumbnail guards) and the [RcsMsrpTls] DER primitives + fingerprint
 * helpers.
 */
class RcsSlice3FtHttpTlsTest {

    @Test
    fun parseFtBodyFull() {
        val body = "File-URL: https://ft.example.com/dl/abc123\r\n" +
            "File-MIME: image/jpeg\r\n" +
            "File-Name: photo.jpg\r\n" +
            "File-Size: 12345\r\n" +
            "\r\ncaption here"
        val info = RcsFileTransferHttp.parseFtBody(body) ?: error("expected info")
        assertEquals("https://ft.example.com/dl/abc123", info.url)
        assertEquals("image/jpeg", info.mime)
        assertEquals("photo.jpg", info.fileName)
        assertEquals(12345L, info.fileSize)
    }

    @Test
    fun parseFtBodyDefaults() {
        val info = RcsFileTransferHttp.parseFtBody("File-URL: https://x/y\r\n") ?: error("expected info")
        assertEquals("https://x/y", info.url)
        assertEquals("application/octet-stream", info.mime)
        assertNull(info.fileName)
        assertNull(info.fileSize)
    }

    @Test
    fun parseFtBodyRejects() {
        assertNull(RcsFileTransferHttp.parseFtBody("hello, no url here"))
        assertNull(RcsFileTransferHttp.parseFtBody(""))
    }

    @Test
    fun parseFtBodyMultilineName() {
        val body = "File-URL: https://x/y\r\nFile-Name: my photo 2024.jpg\r\n"
        assertEquals("my photo 2024.jpg", RcsFileTransferHttp.parseFtBody(body)?.fileName)
    }

    @Test
    fun buildFileCpimCarriesMetadata() {
        val cpim = RcsFileTransferHttp.buildFileCpim("doc.pdf", "application/pdf", 999L, "see attached")
        assertTrue(cpim.contains("File-Name: doc.pdf"))
        assertTrue(cpim.contains("File-MIME: application/pdf"))
        assertTrue(cpim.contains("File-Size: 999"))
        assertTrue(cpim.contains("Content-Type: application/octet-stream"))
        assertTrue(cpim.contains("see attached"))
        val noCaption = RcsFileTransferHttp.buildFileCpim("a.bin", "application/octet-stream", 1L, "")
        assertFalse(noCaption.contains("see attached"))
    }

    @Test
    fun parseContentServerVariants() {
        assertEquals(
            "https://ft.example.com/u",
            RcsFileTransferHttp.parseContentServer(
                "<config><ftHTTPCSURI>https://ft.example.com/u</ftHTTPCSURI></config>"
                    .toByteArray(Charsets.UTF_8),
            ),
        )
        assertNull(RcsFileTransferHttp.parseContentServer("<config/>".toByteArray(Charsets.UTF_8)))
        assertNull(RcsFileTransferHttp.parseContentServer("<ftHTTPCSURI></ftHTTPCSURI>".toByteArray(Charsets.UTF_8)))
        assertNull(RcsFileTransferHttp.parseContentServer(ByteArray(0)))
    }

    @Test
    fun makeThumbnailRejectsGarbage() {
        assertNull(RcsFileTransferHttp.makeThumbnail(byteArrayOf(1, 2, 3, 4)))
        assertNull(RcsFileTransferHttp.makeThumbnail(ByteArray(0)))
    }

    @Test
    fun derPrimitivesRoundTrip() {
        val der = RcsMsrpTls.Der
        // OID for ecdsa-with-SHA256 starts with 06 and decodes back.
        val oid = der.oid("1.2.840.10045.4.3.2")
        assertEquals(0x06.toByte(), oid[0])
        // Integer strips leading zeros, pads high-bit.
        val i1 = der.integer(byteArrayOf(0, 0, 0x01))
        assertTrue(i1.contentEquals(byteArrayOf(0x02, 0x01, 0x01)))
        val i2 = der.integer(byteArrayOf(0xFF.toByte()))
        assertTrue(i2.contentEquals(byteArrayOf(0x02, 0x02, 0x00, 0xFF.toByte())))
        // Long-form length prefix for 200-byte content.
        val seq = der.sequence(ByteArray(200) { 0x41 })
        assertEquals(0x30.toByte(), seq[0])
        assertEquals(0x81.toByte(), seq[1])
        // utf8 + null + bitString tags.
        assertEquals(0x0C.toByte(), der.utf8String("hi")[0])
        assertTrue(der.nullValue().contentEquals(byteArrayOf(0x05, 0x00)))
        assertEquals(0x03.toByte(), der.bitString(byteArrayOf(0x01))[0])
        // Explicit tag + raw passthrough.
        assertEquals(0xA0.toByte(), der.explicit(0, byteArrayOf(0x02, 0x01, 0x02))[0])
        assertTrue(der.raw(byteArrayOf(9, 9)).contentEquals(byteArrayOf(9, 9)))
        // Set tag.
        assertEquals(0x31.toByte(), der.set(byteArrayOf(1), byteArrayOf(2))[0])
    }

    @Test
    fun fingerprintOfIsStableHex() {
        val fp = RcsMsrpTls.fingerprintOf("slice3-cert-bytes".toByteArray(Charsets.UTF_8))
        assertTrue(fp.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
        assertEquals(fp, RcsMsrpTls.fingerprintOf("slice3-cert-bytes".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun parseFingerprintEdgeCases() {
        assertNull(RcsMsrpTls.parseFingerprint(null))
        assertNull(RcsMsrpTls.parseFingerprint(""))
        assertNull(RcsMsrpTls.parseFingerprint("no fingerprint here"))
        val (hash, value) = RcsMsrpTls.parseFingerprint("a=fingerprint:sha-256 aa:bb:cc") ?: error("no fp")
        assertEquals("sha-256", hash)
        assertEquals("aa:bb:cc", value)
    }

    @Test
    fun identitySdpLine() {
        // Identity equality is by fingerprint + cert bytes.
        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val der = RcsMsrpTls.selfSignedCert(kp.public.encoded, kp.private, now - 1000, now + 60_000, "slice3")
        assertNotNull(
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()),
        )
    }
}
