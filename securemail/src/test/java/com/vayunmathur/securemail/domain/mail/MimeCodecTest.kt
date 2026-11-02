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

    @Test
    fun protonQuotedPrintableReply() {
        val (subject, body, from) = MimeCodec.parseMime(
            "From: vayun12 <vayun12@proton.me>\r\n" +
                "Subject: Re: H2\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Transfer-Encoding: quoted-printable\r\n" +
                "\r\n" +
                "hello there =E2=80=94 caf=C3=A9\r\n" +
                "\r\n" +
                "On Sunday, vayun12345@maemail.org <vayun1234=\r\n" +
                "5@maemail.org> wrote:\r\n",
        )
        assertEquals("Re: H2", subject)
        assertEquals("vayun12 <vayun12@proton.me>", from)
        assertEquals("hello there \u2014 caf\u00e9\n\nOn Sunday, vayun12345@maemail.org <vayun12345@maemail.org> wrote:", body)
    }

    @Test
    fun base64HtmlOnlyBecomesText() {
        val html = "<html><head><style>p{}</style></head><body><p>Hi&nbsp;there</p><div>2 &lt; 3 &amp; ok</div></body></html>"
        val b64 = java.util.Base64.getMimeEncoder().encodeToString(html.toByteArray())
        val (_, body, _) = MimeCodec.parseMime(
            "Subject: x\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n$b64\r\n",
        )
        assertEquals("Hi there\n2 < 3 & ok", body)
    }

    @Test
    fun multipartAlternativePrefersPlainText() {
        val (_, body, _) = MimeCodec.parseMime(
            "Subject: x\r\n" +
                "Content-Type: multipart/alternative;\r\n boundary=\"b1\"\r\n" +
                "\r\n" +
                "--b1\r\nContent-Type: text/html\r\n\r\n<p>html version</p>\r\n" +
                "--b1\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nplain version\r\n" +
                "--b1--\r\n",
        )
        assertEquals("plain version", body)
    }

    @Test
    fun encodedWordSubject() {
        val (subject, _, _) = MimeCodec.parseMime(
            "Subject: =?UTF-8?B?Q2Fmw6k=?= =?utf-8?Q?_ol=C3=A9?=\r\nContent-Type: text/plain\r\n\r\nx\r\n",
        )
        assertEquals("Caf\u00e9 ol\u00e9", subject)
    }

    @Test
    fun ownComposerFormat() {
        val (subject, body, from) = MimeCodec.parseMime(
            "From: alice@maemail.org\r\nSubject: Hello\r\nMIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=utf-8; protected-headers=\"v1\"\r\n" +
                "Content-Transfer-Encoding: 8bit\r\n\r\nline one\r\nline two\r\n",
        )
        assertEquals(Triple("Hello", "line one\nline two", "alice@maemail.org"), Triple(subject, body, from))
    }
}
