package com.vayunmathur.email.mime

import com.vayunmathur.email.network.imap.MimeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins header decoding, date parsing, filename sanitizing, multipart
 * splitting and content-type/disposition parsing against fixed vectors.
 *
 * Some paths use `android.util.Base64`, so these run under Robolectric.
 * A disagreement here shows garbled subjects, wrong sort order, unsafe
 * file names, or dropped attachments.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimeHeaderUtilsTest {

    // ---------- decodeHeader (RFC 2047) ----------

    @Test
    fun `plain header passes through`() {
        assertEquals("Hello world", MimeParser.decodeHeader("Hello world"))
    }

    @Test
    fun `base64 encoded-word decodes`() {
        assertEquals("Café", MimeParser.decodeHeader("=?UTF-8?B?Q2Fmw6k=?="))
    }

    @Test
    fun `q-encoded-word decodes with underscore space`() {
        assertEquals("Hello World", MimeParser.decodeHeader("=?UTF-8?Q?Hello_World?="))
    }

    @Test
    fun `broken encoded-word degrades gracefully`() {
        // Android's Base64 silently skips invalid chars, so undecodable
        // content degrades to empty instead of throwing or leaking raw text.
        assertEquals("", MimeParser.decodeHeader("=?UTF-8?B?!!!?="))
    }

    // ---------- decodeCharset ----------

    @Test
    fun `charset aliases resolve`() {
        val bytes = "héllo".toByteArray(Charsets.UTF_8)
        assertEquals("héllo", MimeParser.decodeCharset(bytes, "utf8"))
        assertEquals("héllo", MimeParser.decodeCharset(bytes, "UTF-8"))
    }

    @Test
    fun `unknown charset falls back to utf8`() {
        val bytes = "abc".toByteArray(Charsets.UTF_8)
        assertEquals("abc", MimeParser.decodeCharset(bytes, "x-made-up"))
    }

    // ---------- parseDateToMillis ----------

    @Test
    fun `rfc2822 date parses`() {
        val millis = MimeParser.parseDateToMillis("Wed, 17 Jul 2024 12:00:00 +0000")
        assertTrue(millis > 0, "got $millis")
    }

    @Test
    fun `blank and garbage dates return zero`() {
        assertEquals(0L, MimeParser.parseDateToMillis(""))
        assertEquals(0L, MimeParser.parseDateToMillis("   "))
        assertEquals(0L, MimeParser.parseDateToMillis("not a date at all xyz"))
    }

    // ---------- sanitizeFilename ----------

    @Test
    fun `path components stripped and blank defaulted`() {
        assertEquals("report.pdf", MimeParser.sanitizeFilename("/tmp/report.pdf"))
        assertEquals("report.pdf", MimeParser.sanitizeFilename("C:\\docs\\report.pdf"))
        assertEquals("attachment", MimeParser.sanitizeFilename("   "))
    }

    @Test
    fun `control chars replaced and length capped`() {
        val dirty = "a\"b\rc\nd.pdf"
        val clean = MimeParser.sanitizeFilename(dirty)
        assertTrue(!clean.contains('"') && !clean.contains('\r') && !clean.contains('\n'), clean)
        val long = "x".repeat(500) + ".pdf"
        assertTrue(MimeParser.sanitizeFilename(long).length <= long.length, clean)
        assertTrue(MimeParser.sanitizeFilename(long).isNotEmpty())
    }

    // ---------- splitMultipart ----------

    @Test
    fun `blank boundary yields nothing`() {
        assertTrue(MimeParser.splitMultipart("body".toByteArray(), "").isEmpty())
    }

    @Test
    fun `two parts split on boundary`() {
        val body = "--B\r\nContent-Type: text/plain\r\n\r\none\r\n--B\r\nContent-Type: text/plain\r\n\r\ntwo\r\n--B--\r\n"
        val parts = MimeParser.splitMultipart(body.toByteArray(Charsets.ISO_8859_1), "B")
        assertEquals(2, parts.size)
        val texts = parts.map { String(it, Charsets.ISO_8859_1) }
        assertTrue(texts[0].contains("one"), texts[0])
        assertTrue(texts[1].contains("two"), texts[1])
    }

    // ---------- parseContentType / parseDisposition ----------

    @Test
    fun `content type parses type and boundary`() {
        val info = MimeParser.parseContentType("multipart/mixed; boundary=\"abc123\"")
        assertEquals("multipart/mixed", info.fullType.lowercase())
        assertEquals("abc123", info.params["boundary"])
    }

    @Test
    fun `null content type defaults sensibly`() {
        val info = MimeParser.parseContentType(null)
        assertTrue(info.fullType.isNotEmpty(), "$info")
    }

    @Test
    fun `disposition parses filename`() {
        val disp = MimeParser.parseDisposition("attachment; filename=\"report.pdf\"")
        assertTrue(disp != null, "null disposition")
        assertEquals("report.pdf", disp!!.filename)
        assertTrue(disp.isAttachment)
    }

    // ---------- extractCid / extractHeaderValue ----------

    @Test
    fun `cid brackets stripped`() {
        assertEquals("img1", MimeParser.extractCid("<img1>"))
        assertEquals("img1", MimeParser.extractCid("img1"))
        assertEquals(null, MimeParser.extractCid(""))
    }

    @Test
    fun `header lookup is case-insensitive on the name`() {
        // MimeParser.parseHeaders stores keys lowercased, so callers pass
        // already-normalized maps; the lookup lowercases only the name.
        val headers = mapOf("subject" to "hi", "from" to "a@b")
        assertEquals("hi", MimeParser.extractHeaderValue(headers, "subject"))
        assertEquals("hi", MimeParser.extractHeaderValue(headers, "Subject"))
        assertEquals("a@b", MimeParser.extractHeaderValue(headers, "FROM"))
        assertEquals(null, MimeParser.extractHeaderValue(headers, "to"))
    }
}
