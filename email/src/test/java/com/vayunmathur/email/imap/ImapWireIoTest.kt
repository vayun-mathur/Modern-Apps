package com.vayunmathur.email.imap

import com.vayunmathur.email.network.imap.ImapWireIo
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [ImapWireIo] line/literal reads against canned streams: the FETCH
 * accumulator downstream trusts these bytes blindly, so a short literal read
 * or a swallowed line corrupts every message parsed from that connection.
 */
@RunWith(RobolectricTestRunner::class)
class ImapWireIoTest {

    private fun wire(bytes: ByteArray, maxLine: Int = 8192): Pair<ImapWireIo, MutableList<String>> {
        val sent = mutableListOf<String>()
        var stream = ByteArrayInputStream(bytes)
        val io = ImapWireIo(input = { stream }, onSendLine = { sent.add(it) }, maxLine = maxLine)
        return io to sent
    }

    @Test
    fun `sendLine forwards the exact command`() {
        val (io, sent) = wire(ByteArray(0))
        io.sendLine("A1 NOOP")
        assertEquals(listOf("A1 NOOP"), sent)
    }

    @Test
    fun `plain line reads without a literal`() {
        val (io, _) = wire("* OK hello\r\n".toByteArray())
        val (line, literal) = assertNotNull(io.readLineWithLiteral())
        assertEquals("* OK hello", line)
        assertNull(literal)
    }

    @Test
    fun `literal suffix reads the exact byte count`() {
        val payload = "From: a@b.com\r\n\r\n".toByteArray()
        val stream = "* 1 FETCH (BODY[] {${payload.size}}\r\n".toByteArray() + payload
        val (io, _) = wire(stream)
        val (line, literal) = assertNotNull(io.readLineWithLiteral())
        assertTrue(line.contains("FETCH"), line)
        assertNotNull(literal)
        assertEquals(payload.toList(), literal.toList())
    }

    @Test
    fun `zero-length literal yields empty bytes`() {
        val (io, _) = wire("* 1 FETCH (BODY[] {0}\r\n".toByteArray())
        val (_, literal) = assertNotNull(io.readLineWithLiteral())
        assertNotNull(literal)
        assertEquals(0, literal.size)
    }

    @Test
    fun `exhausted stream returns null`() {
        val (io, _) = wire(ByteArray(0))
        assertNull(io.readLineWithLiteral())
    }

    @Test
    fun `non-zero literal marker without header context still reads`() {
        // A bare {N} suffix on a non-FETCH line still denotes a literal.
        val payload = "abcde".toByteArray()
        val stream = "+ continuation {5}\r\n".toByteArray() + payload
        val (io, _) = wire(stream)
        val (_, literal) = assertNotNull(io.readLineWithLiteral())
        assertNotNull(literal)
        assertEquals(5, literal.size)
    }

    @Test
    fun `CRLF terminators are stripped from the line`() {
        val (io, _) = wire("A1 OK done\r\n".toByteArray())
        val (line, _) = assertNotNull(io.readLineWithLiteral())
        assertEquals("A1 OK done", line)
    }
}
