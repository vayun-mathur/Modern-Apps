package com.vayunmathur.library.network

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the pure [HttpUrlEngine] surface the body tests don't reach: [HttpUrlEngine.toBodyBytes],
 * [HttpUrlEngine.readBody] (including endpoint naming on failure), the [HttpUrlEngine.OpenResponse]
 * success predicate, and the cap/pre-size boundaries of [HttpUrlEngine.drainFully].
 */
class HttpUrlEnginePureTest {

    private fun response(
        status: Int,
        stream: InputStream,
        url: String = "https://example.test/x",
    ) = HttpUrlEngine.OpenResponse(
        status = status,
        statusMessage = "msg",
        headers = emptyMap(),
        finalUrl = url,
        stream = stream,
        hasStream = true,
        contentLength = null,
        isIdentityEncoding = true,
    )

    private fun failingAfter(firstBytes: Int): InputStream = object : InputStream() {
        private var served = 0
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= firstBytes) throw IOException("boom")
            val n = minOf(len, firstBytes - served)
            b.fill(7, off, off + n)
            served += n
            return n
        }
    }

    @Test
    fun to_body_bytes_null_stays_null() {
        assertNull(HttpUrlEngine.toBodyBytes(null))
    }

    @Test
    fun to_body_bytes_byte_array_passes_through() {
        val input = byteArrayOf(1, 2, 3)
        assertSame(input, HttpUrlEngine.toBodyBytes(input))
    }

    @Test
    fun to_body_bytes_string_encodes_utf8() {
        assertContentEquals("héllo".toByteArray(Charsets.UTF_8), HttpUrlEngine.toBodyBytes("héllo"))
    }

    @Test
    fun to_body_bytes_other_uses_to_string() {
        assertContentEquals("42".toByteArray(Charsets.UTF_8), HttpUrlEngine.toBodyBytes(42))
    }

    @Test
    fun read_body_returns_the_bytes() {
        val data = ByteArray(3000) { (it % 251).toByte() }
        assertContentEquals(data, HttpUrlEngine.readBody(response(200, ByteArrayInputStream(data))))
    }

    @Test
    fun read_body_default_cap_names_the_endpoint_on_breach() {
        val data = ByteArray(10_000) { (it % 251).toByte() }
        val resp = response(200, ByteArrayInputStream(data), url = "https://example.test/big")
        val e = assertFailsWith<IOException> {
            resp.use { HttpUrlEngine.readBody(it, maxBytes = 1024) }
        }
        assertTrue(e.message!!.contains("https://example.test/big"), "got: ${e.message}")
        assertTrue(e.message!!.contains("1024"), "got: ${e.message}")
    }

    @Test
    fun read_body_null_cap_stays_uncapped() {
        val data = ByteArray(5000) { (it % 251).toByte() }
        assertContentEquals(
            data,
            HttpUrlEngine.readBody(response(200, ByteArrayInputStream(data)), maxBytes = null),
        )
    }

    @Test
    fun read_body_names_the_endpoint_on_failure() {
        val resp = response(200, failingAfter(300), url = "https://example.test/models/x")
        val e = assertFailsWith<IOException> { resp.use { HttpUrlEngine.readBody(it) } }
        assertTrue(e.message!!.contains("https://example.test/models/x"), "got: ${e.message}")
    }

    @Test
    fun is_success_covers_the_2xx_band() {
        assertEquals(false, response(199, ByteArrayInputStream(ByteArray(0))).isSuccess)
        assertEquals(true, response(200, ByteArrayInputStream(ByteArray(0))).isSuccess)
        assertEquals(true, response(299, ByteArrayInputStream(ByteArray(0))).isSuccess)
        assertEquals(false, response(300, ByteArrayInputStream(ByteArray(0))).isSuccess)
    }

    @Test
    fun cap_exact_boundary_reads() {
        val data = ByteArray(1024) { (it % 251).toByte() }
        assertContentEquals(data, HttpUrlEngine.drainFully(ByteArrayInputStream(data), null, true, maxBytes = 1024))
    }

    @Test
    fun cap_off_by_one_fails_with_counts() {
        val data = ByteArray(1025) { (it % 251).toByte() }
        val e = assertFailsWith<HttpUrlEngine.BodyTooLargeException> {
            HttpUrlEngine.drainFully(ByteArrayInputStream(data), null, true, maxBytes = 1024)
        }
        assertEquals(1024L, e.maxBytes)
        assertTrue(e.bytesRead > 1024, "cap must be counted during the read, got ${e.bytesRead}")
    }

    @Test
    fun presize_exact_boundary_allocates_once_and_reads_exact() {
        val size = 1024 * 1024
        val data = ByteArray(size) { (it % 251).toByte() }
        assertContentEquals(data, HttpUrlEngine.drainFully(ByteArrayInputStream(data), size.toLong(), true))
    }

    @Test
    fun hint_just_past_the_presize_limit_falls_back_to_segments() {
        val size = 1024 * 1024 + 1
        val data = ByteArray(size) { (it % 251).toByte() }
        assertContentEquals(data, HttpUrlEngine.drainFully(ByteArrayInputStream(data), size.toLong(), true))
    }

    @Test
    fun zero_and_negative_hints_read_to_eof() {
        val data = ByteArray(5000) { (it % 251).toByte() }
        assertContentEquals(data, HttpUrlEngine.drainFully(ByteArrayInputStream(data), 0L, true))
        assertContentEquals(data, HttpUrlEngine.drainFully(ByteArrayInputStream(data), -5L, true))
    }
}
