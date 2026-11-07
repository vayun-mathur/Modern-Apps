package com.vayunmathur.email.mime

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.network.smtp.MimeBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins MIME message construction against structural assertions (header
 * presence, part boundaries, transfer encodings).
 *
 * [MimeBuilder] uses `android.util.Base64`, which returns defaults on plain
 * JVM tests, so these run under Robolectric. A disagreement here produces
 * malformed outbound mail — missing recipients, unencoded subjects, broken
 * multipart framing — so each test asserts the exact wire shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimeBuilderTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun simple(
        subject: String = "Hello",
        body: String = "World",
        asHtml: Boolean = false,
        cc: String? = null,
        bcc: String? = null,
        inReplyTo: String? = null,
        references: String? = null,
    ) = MimeBuilder.buildMessage(
        context = context,
        from = "me@example.com",
        to = "you@example.com",
        subject = subject,
        body = body,
        asHtml = asHtml,
        cc = cc,
        bcc = bcc,
        inReplyTo = inReplyTo,
        references = references,
    )

    @Test
    fun `plain text message carries headers and body`() {
        val raw = simple()
        assertTrue(raw.contains("From: me@example.com\r\n"), raw)
        assertTrue(raw.contains("To: you@example.com\r\n"), raw)
        assertTrue(raw.contains("Subject: Hello\r\n"), raw)
        assertTrue(raw.contains("MIME-Version: 1.0\r\n"), raw)
        assertTrue(raw.contains("Message-ID: <"), raw)
        assertTrue(raw.contains("World"), raw)
    }

    @Test
    fun `multiple recipients split on comma and semicolon`() {
        val raw = MimeBuilder.buildMessage(
            context, "me@example.com", "a@x.com, b@x.com;c@x.com",
            "s", "b", false,
        )
        assertTrue(raw.contains("To: a@x.com, b@x.com, c@x.com\r\n"), raw)
    }

    @Test
    fun `entries without at-sign are dropped`() {
        val raw = MimeBuilder.buildMessage(
            context, "me@example.com", "a@x.com, not-an-address",
            "s", "b", false,
        )
        assertTrue(raw.contains("To: a@x.com\r\n"), raw)
        assertFalse(raw.contains("not-an-address"), raw)
    }

    @Test
    fun `cc bcc reply headers appear only when set`() {
        val bare = simple()
        assertFalse(bare.contains("Cc:"), bare)
        assertFalse(bare.contains("Bcc:"), bare)
        assertFalse(bare.contains("In-Reply-To:"), bare)
        assertFalse(bare.contains("References:"), bare)
        val full = simple(cc = "c@x.com", bcc = "d@x.com", inReplyTo = "<m1>", references = "<m1> <m0>")
        assertTrue(full.contains("Cc: c@x.com\r\n"), full)
        assertTrue(full.contains("Bcc: d@x.com\r\n"), full)
        assertTrue(full.contains("In-Reply-To: <m1>\r\n"), full)
        assertTrue(full.contains("References: <m1> <m0>\r\n"), full)
    }

    @Test
    fun `non-ascii subject encodes as rfc2047`() {
        val raw = simple(subject = "Café")
        assertTrue(raw.contains("Subject: =?UTF-8?B?"), raw)
        assertFalse(raw.contains("Subject: Café"), raw)
    }

    @Test
    fun `html body declares html content type`() {
        val raw = simple(body = "<b>hi</b>", asHtml = true)
        assertTrue(raw.contains("text/html", ignoreCase = true), raw)
    }

    @Test
    fun `plain body declares plain content type`() {
        val raw = simple()
        assertTrue(raw.contains("text/plain", ignoreCase = true), raw)
    }

    @Test
    fun `attachment forces multipart mixed with boundary`() {
        val raw = MimeBuilder.buildMessage(
            context, "me@example.com", "you@example.com", "s", "b", false,
            attachments = listOf(Uri.parse("content://x/y.pdf")),
        )
        assertTrue(raw.contains("multipart/mixed"), raw)
        assertTrue(raw.contains("boundary=\"----=_Part_"), raw)
    }

    @Test
    fun `base64 chunking wraps at 76 chars`() {
        val bytes = ByteArray(200) { (it % 250).toByte() }
        val chunked = MimeBuilder.encodeBase64Chunked(bytes)
        val lines = chunked.split("\r\n").filter { it.isNotEmpty() }
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.length <= 76 }, chunked)
        // Reassembly recovers the input.
        val joined = lines.joinToString("")
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(bytes),
            joined,
        )
        assertEquals("", MimeBuilder.encodeBase64Chunked(ByteArray(0)))
    }

    @Test
    fun `quoted printable encodes non-ascii and wraps`() {
        val encoded = MimeBuilder.encodeQuotedPrintableForText("Café")
        // é (UTF-8 0xC3 0xA9) must be hex-escaped, never raw.
        assertTrue(encoded.contains("=C3=A9"), encoded)
        assertFalse(encoded.contains("é"), encoded)
        // Plain ASCII passes through.
        assertTrue(MimeBuilder.encodeQuotedPrintableForText("Hi!").contains("Hi!"))
        // Newlines become CRLF line breaks, not =0A escapes.
        assertTrue(MimeBuilder.encodeQuotedPrintableForText("a\nb").contains("a\r\nb"))
    }
}
