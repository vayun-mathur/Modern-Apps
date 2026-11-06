package com.vayunmathur.communicate.telephony

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice-4 coverage for the pure RCS inbound helpers ([headerValue],
 * [bufferLargeChunk], [decodePayloadBody], [extractTextBody]).
 *
 * Robolectric (Base64 shadows for the payload path): pins the SIP header
 * lookup, the large-message reassembly buffer, the CPIM payload decoding and
 * the text extraction — the logic that decides what lands in the inbox —
 * without the IMS stack.
 */
@RunWith(RobolectricTestRunner::class)
class RcsInboundHelpersSlice4Test {

    // -- headerValue ------------------------------------------------------------

    @Test
    fun `headerValue matches case-insensitively and trims`() {
        val headers = "Via: SIP/2.0/TCP h;branch=1\r\nFrom: \"Bob\" <sip:bob@x>;tag=t1\r\nCSeq: 4 MESSAGE\r\n"
        assertEquals("\"Bob\" <sip:bob@x>;tag=t1", headerValue(headers, "From:"))
        assertEquals("\"Bob\" <sip:bob@x>;tag=t1", headerValue(headers, "from:"))
        assertEquals("4 MESSAGE", headerValue(headers, "CSEQ:"))
        assertNull(headerValue(headers, "To:"))
        // A trailing space in the name still prefix-matches the same header.
        assertEquals("\"Bob\" <sip:bob@x>;tag=t1", headerValue(headers, "From: "))
    }

    @Test
    fun `headerValue skips blank values`() {
        assertNull(headerValue("From:   \r\nTo: <sip:a@b>\r\n", "From:"))
        assertEquals("<sip:a@b>", headerValue("From:   \r\nTo: <sip:a@b>\r\n", "To:"))
    }

    // -- bufferLargeChunk ---------------------------------------------------------

    @Test
    fun `chunks reassemble in order and clean up`() {
        val convo = "convo-lm-${System.nanoTime()}"
        val id = "msg-${System.nanoTime()}"
        // Parts are trimmed then concatenated once every part has arrived.
        assertNull(bufferLargeChunk(convo, id, 1, 3, "h1\r\n\r\nHello"))
        assertNull(bufferLargeChunk(convo, id, 3, 3, "h3\r\n\r\nworld!"))
        assertEquals("Hellobravenewworld!", bufferLargeChunk(convo, id, 2, 3, "h2\r\n\r\nbravenew"))
        // Buffer drained: a repeat part starts a fresh incomplete buffer.
        assertNull(bufferLargeChunk(convo, id, 1, 3, "h1\r\n\r\nHello"))
    }

    @Test
    fun `chunk bounds are enforced`() {
        val convo = "convo-bounds-${System.nanoTime()}"
        assertNull(bufferLargeChunk(convo, "m", 0, 2, "x\r\n\r\na"))
        assertNull(bufferLargeChunk(convo, "m", 3, 2, "x\r\n\r\na"))
        assertNull(bufferLargeChunk(convo, "m", 1, 0, "x\r\n\r\na"))
        assertNull(bufferLargeChunk(convo, "m", 1, -1, "x\r\n\r\na"))
    }

    @Test
    fun `blank reassembled text yields null`() {
        val convo = "convo-blank-${System.nanoTime()}"
        assertNull(bufferLargeChunk(convo, "m2", 1, 1, "h\r\n\r\n   "))
    }

    @Test
    fun `conversations do not share buffers`() {
        val a = "convo-A-${System.nanoTime()}"
        val b = "convo-B-${System.nanoTime()}"
        assertNull(bufferLargeChunk(a, "m", 1, 2, "h\r\n\r\none"))
        // Same chunk id in another conversation is independent (still incomplete).
        assertNull(bufferLargeChunk(b, "m", 1, 2, "h\r\n\r\nuno"))
        assertEquals("onetwo", bufferLargeChunk(a, "m", 2, 2, "h\r\n\r\ntwo"))
    }

    // -- decodePayloadBody ----------------------------------------------------------

    @Test
    fun `decodes after the header gap in all line-ending forms`() {
        val expected = "payload-bytes".toByteArray(Charsets.UTF_8).toList()
        val b64 = android.util.Base64.encodeToString("payload-bytes".toByteArray(), android.util.Base64.NO_WRAP)
        assertEquals(expected, decodePayloadBody("h: v\r\n\r\n$b64")!!.toList())
        assertEquals(expected, decodePayloadBody("h: v\n\n$b64")!!.toList())
        assertEquals(expected, decodePayloadBody(b64)!!.toList())
    }

    @Test
    fun `blank bodies yield null`() {
        assertNull(decodePayloadBody(""))
        assertNull(decodePayloadBody("   "))
        assertNull(decodePayloadBody("h: v\r\n\r\n"))
    }

    @Test
    fun `undecodable bodies yield null`() {
        // Control characters are outside every Base64 alphabet, so decoding
        // must fail rather than produce garbage.
        assertNull(decodePayloadBody(""))
    }

    // -- extractTextBody --------------------------------------------------------------

    @Test
    fun `plain text passes through`() {
        assertEquals("hello", extractTextBody("hello".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `cpim yields the payload after the last gap`() {
        val cpim = "From: <sip:a@b>\r\nContent-Type: message/cpim\r\n\r\n" +
            "Content-Type: text/plain\r\n\r\nthe actual text"
        assertEquals("the actual text", extractTextBody(cpim.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `cpim without a gap yields null and blank yields null`() {
        assertNull(extractTextBody("Content-Type: message/cpim no-gap".toByteArray(Charsets.UTF_8)))
        assertNull(extractTextBody("".toByteArray(Charsets.UTF_8)))
        assertNull(extractTextBody("   ".toByteArray(Charsets.UTF_8)))
    }

    // -- data shapes ---------------------------------------------------------------------

    @Test
    fun `inbound kinds cover text receipt typing`() {
        assertEquals(3, InboundKind.entries.size)
        assertTrue(InboundKind.entries.contains(InboundKind.Text))
    }

    @Test
    fun `envelope and inbound records hold their fields`() {
        val env = Envelope("alice", "text/plain", "hi", "c1", "b1")
        assertEquals("alice", env.from)
        val inbound = InboundRcs("conv", "hi", "alice", "m1")
        assertEquals(InboundKind.Text, inbound.kind)
        assertNull(inbound.ftUrl)
        assertEquals(inbound, inbound.copy())
        assertEquals(env, env.copy())
    }
}
