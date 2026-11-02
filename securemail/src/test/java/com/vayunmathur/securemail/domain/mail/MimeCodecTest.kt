package com.vayunmathur.securemail.domain.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MimeCodecTest {

    private fun envelope(boundary: String, dataPart: String) = (
        "Content-Type: multipart/encrypted; boundary=\"$boundary\"; protocol=\"application/pgp-encrypted\"\r\n" +
            "\r\n" +
            "--$boundary\r\n" +
            "Content-Type: application/pgp-encrypted\r\n" +
            "\r\n" +
            "Version: 1\r\n" +
            "\r\n" +
            "--$boundary\r\n" +
            "Content-Type: application/octet-stream; name=\"encrypted.asc\"\r\n" +
            "\r\n" +
            dataPart +
            "--$boundary--\r\n"
        ).toByteArray()

    @Test
    fun armoredPartFromAnotherClient() {
        val proton = envelope(
            "------------ProtonBoundary0123",
            "-----BEGIN PGP MESSAGE-----\r\n" +
                "Version: ProtonMail\r\n" +
                "\r\n" +
                "wV4DAAEC\r\n" +
                "AwQF\r\n" +
                "=ABCD\r\n" +
                "-----END PGP MESSAGE-----\r\n",
        )
        assertEquals("wV4DAAECAwQF", MimeCodec.extractPgpPayloadB64(proton))
    }

    @Test
    fun armoredPartWithoutHeaders() {
        val ours = envelope(
            "maemail-1234abcd",
            "-----BEGIN PGP MESSAGE-----\r\n\r\nwV4DAAEC\r\n=ABCD\r\n-----END PGP MESSAGE-----\r\n",
        )
        assertEquals("wV4DAAEC", MimeCodec.extractPgpPayloadB64(ours))
    }

    @Test
    fun legacyBareBase64StillParses() {
        val legacy = envelope("maemail-1234abcd", "wV4DAAEC\r\nAwQF\r\n")
        assertEquals("wV4DAAECAwQF", MimeCodec.extractPgpPayloadB64(legacy))
    }

    @Test
    fun nonPgpMimeIsRejected() {
        assertNull(MimeCodec.extractPgpPayloadB64("Content-Type: text/plain\r\n\r\nhello\r\n".toByteArray()))
    }
}
