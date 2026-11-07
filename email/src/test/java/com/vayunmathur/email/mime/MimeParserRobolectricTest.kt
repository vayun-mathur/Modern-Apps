package com.vayunmathur.email.mime

import android.app.Application
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.network.imap.MimeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric paths of [MimeParser]: base64 CTE, RFC2047 header words,
 * full-message parse, EML projection and CID cache extraction. These need
 * android.util.Base64, so they cannot run as plain host tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimeParserRobolectricTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun b64(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    @Test
    fun `parsePart decodes base64 text body`() {
        val raw = "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n${b64("hello base64")}"
        val part = MimeParser.parsePart(raw.toByteArray(Charsets.UTF_8), "")
        assertEquals("hello base64", part.bodyText)
    }

    @Test
    fun `decodeHeader decodes RFC2047 base64 and quoted printable words`() {
        assertEquals("Héllo", MimeParser.decodeHeader("=?UTF-8?B?${b64("Héllo")} ?="))
        assertEquals("Hello World", MimeParser.decodeHeader("=?UTF-8?Q?Hello_World?="))
        assertEquals("plain", MimeParser.decodeHeader("plain"))
    }

    @Test
    fun `parseMessage returns plain body and attachment metadata`() {
        val raw = "From: a@b\r\nSubject: Hi\r\nContent-Type: multipart/mixed; boundary=m\r\n\r\n" +
            "--m\r\nContent-Type: text/plain\r\n\r\nBody text\r\n" +
            "--m\r\nContent-Type: application/pdf\r\nContent-Disposition: attachment; filename=\"r.pdf\"\r\n\r\n12345\r\n--m--\r\n"
        val (body, isHtml, attachments) = MimeParser.parseMessage(raw.toByteArray(Charsets.UTF_8), 9L, "me@x.com", "INBOX")
        assertEquals("Body text", body)
        assertFalse(isHtml)
        assertEquals(listOf("r.pdf"), attachments.map { it.fileName })
        assertEquals(9L, attachments.single().messageId)
    }

    @Test
    fun `parseMessage with context extracts cid files`() {
        val png = b64("PNGDATA")
        val raw = "Content-Type: multipart/related; boundary=m\r\n\r\n" +
            "--m\r\nContent-Type: text/html\r\n\r\n<img src=\"cid:img1\">\r\n" +
            "--m\r\nContent-Type: image/png\r\nContent-Id: <img1>\r\nContent-Transfer-Encoding: base64\r\n\r\n$png\r\n--m--\r\n"
        val (_, _, _) = MimeParser.parseMessage(raw.toByteArray(Charsets.UTF_8), 42L, "me@x.com", "INBOX", context)
        val cidMap = MimeParser.extractCidMap(context, raw.toByteArray(Charsets.UTF_8), 42L)
        assertTrue(cidMap.containsKey("img1"))
        assertEquals("PNGDATA", cidMap.getValue("img1").readText())
    }

    @Test
    fun `parseEmlToParsedMessage projects headers body and attachments`() {
        val raw = "From: Sender <s@x.com>\r\nTo: me@x.com\r\nSubject: EML Subject\r\n" +
            "Date: Thu, 21 Oct 2026 18:24:12 +0000\r\nMessage-ID: <m1@x>\r\n" +
            "Content-Type: multipart/mixed; boundary=m\r\n\r\n" +
            "--m\r\nContent-Type: text/plain\r\n\r\nEml body\r\n" +
            "--m\r\nContent-Type: text/csv\r\nContent-Disposition: attachment; filename=\"d.csv\"\r\n\r\na,b\r\n--m--\r\n"
        val parsed = MimeParser.parseEmlToParsedMessage(raw.toByteArray(Charsets.UTF_8), 77L, context)
        assertEquals("EML Subject", parsed.message.subject)
        assertEquals("Sender <s@x.com>", parsed.message.from)
        assertEquals("me@x.com", parsed.message.to)
        assertEquals(77L, parsed.message.id)
        assertEquals("Eml body", parsed.message.body)
        assertTrue(parsed.message.dateMillis > 0)
        assertEquals(listOf("d.csv"), parsed.emlAttachments.map { it.fileName })
        assertEquals("a,b", String(parsed.emlAttachments.single().bytes, Charsets.UTF_8))
    }

    @Test
    fun `parseEmlToParsedMessage defaults subject and collects inline cid`() {
        val png = b64("IMG")
        val raw = "From: a@b\r\nContent-Type: multipart/related; boundary=m\r\n\r\n" +
            "--m\r\nContent-Type: text/html\r\n\r\n<p>hi</p>\r\n" +
            "--m\r\nContent-Type: image/png\r\nContent-Id: <logo>\r\nContent-Transfer-Encoding: base64\r\n\r\n$png\r\n--m--\r\n"
        val parsed = MimeParser.parseEmlToParsedMessage(raw.toByteArray(Charsets.UTF_8), 88L, context)
        assertEquals("(no subject)", parsed.message.subject)
        assertEquals("<p>hi</p>", parsed.message.body)
        assertTrue(parsed.message.isHtml)
        assertNotNull(parsed.inlineCidMap["logo"])
        assertTrue(parsed.emlAttachments.isEmpty())
    }

    @Test
    fun `extractCidMap reconciles meta files on second call`() {
        val png = b64("XYZ")
        val raw = "Content-Type: image/png\r\nContent-Id: <c1>\r\nContent-Disposition: inline; filename=\"c.png\"\r\nContent-Transfer-Encoding: base64\r\n\r\n$png"
        val first = MimeParser.extractCidMap(context, raw.toByteArray(Charsets.UTF_8), 99L)
        assertEquals("XYZ", first.getValue("c1").readText())
        val second = MimeParser.extractCidMap(context, raw.toByteArray(Charsets.UTF_8), 99L)
        assertEquals(first.getValue("c1").name, second.getValue("c1").name)
    }
}
