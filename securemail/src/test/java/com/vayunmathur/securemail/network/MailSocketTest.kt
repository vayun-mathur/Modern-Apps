package com.vayunmathur.securemail.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailSocketTest {

    @Test
    fun subFrameRoundTripShape() {
        val sig = byteArrayOf(7, 7, 7)
        val frame = MailSocket.subFrame("Alice@MAEMAIL.ORG", sig, "ARMOR")
        assertEquals(MailSocket.OP_SUB, frame[0])
        val addrLen = MailSocket.readU16(frame, 1)
        assertEquals("alice@maemail.org", frame.copyOfRange(3, 3 + addrLen).toString(Charsets.UTF_8))
        val sigLen = MailSocket.readU16(frame, 3 + addrLen)
        assertContentEquals(sig, frame.copyOfRange(5 + addrLen, 5 + addrLen + sigLen))
        assertEquals("ARMOR", frame.copyOfRange(5 + addrLen + sigLen, frame.size).toString(Charsets.UTF_8))
    }

    @Test
    fun subPayloadMatchesServerContext() {
        val nonce = ByteArray(MailSocket.NONCE_LEN) { it.toByte() }
        val payload = MailSocket.subPayload(nonce, "A@maemail.org")
        val context = "maemail-sub-v1\u0000".toByteArray(Charsets.UTF_8)
        assertContentEquals(context + nonce + "a@maemail.org".toByteArray(Charsets.UTF_8), payload)
    }

    @Test
    fun pubFrameCarriesSenderAndFlags() {
        val enc = MailSocket.pubFrame("Me@maemail.org", "b@maemail.org", byteArrayOf(1, 2, 3), unencrypted = false)
        assertEquals(MailSocket.OP_PUB, enc[0])
        assertEquals(0, enc[1])
        val fromLen = MailSocket.readU16(enc, 2)
        assertEquals("me@maemail.org", enc.copyOfRange(4, 4 + fromLen).toString(Charsets.UTF_8))
        val rcptLen = MailSocket.readU16(enc, 4 + fromLen)
        assertEquals("b@maemail.org", enc.copyOfRange(6 + fromLen, 6 + fromLen + rcptLen).toString(Charsets.UTF_8))
        assertContentEquals(byteArrayOf(1, 2, 3), enc.copyOfRange(6 + fromLen + rcptLen, enc.size))
        val plain = MailSocket.pubFrame("me@maemail.org", "b@maemail.org", byteArrayOf(1, 2, 3), unencrypted = true)
        assertEquals(MailSocket.FLAG_PLAIN, plain[1])
    }

    @Test
    fun getKeyFrameShape() {
        val frame = MailSocket.getKeyFrame("B@Proton.me")
        assertEquals(MailSocket.OP_GETKEY_REQ, frame[0])
        val len = MailSocket.readU16(frame, 1)
        assertEquals("b@proton.me", frame.copyOfRange(3, 3 + len).toString(Charsets.UTF_8))
    }

    /** Builds a v2 MSG frame: [0x03][flags][u32 idLen][id][u16 rcptLen][rcpt][raw]. */
    private fun msgFrame(id: String, flags: Byte, rcpt: String, raw: ByteArray): ByteArray {
        val idBytes = id.toByteArray(Charsets.UTF_8)
        val rcptBytes = rcpt.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(6 + idBytes.size + 2 + rcptBytes.size + raw.size)
        buf[0] = MailSocket.OP_MSG
        buf[1] = flags
        buf[5] = idBytes.size.toByte()
        idBytes.copyInto(buf, 6)
        MailSocket.putU16(buf, 6 + idBytes.size, rcptBytes.size)
        rcptBytes.copyInto(buf, 8 + idBytes.size)
        raw.copyInto(buf, 8 + idBytes.size + rcptBytes.size)
        return buf
    }

    @Test
    fun parseMsgRoundTrip() {
        val raw = byteArrayOf(9, 8, 7)
        val parsed = MailSocket.parseMsg(msgFrame("abc-123", 0, "quiet-otter-4821@maemail.org", raw))!!
        assertEquals("abc-123", parsed.id)
        assertEquals("quiet-otter-4821@maemail.org", parsed.deliveredTo)
        assertEquals(MailSocket.MsgFlags(unencrypted = false, spam = false), parsed.flags)
        assertContentEquals(raw, parsed.raw)
    }

    @Test
    fun parseMsgSpamFlag() {
        val raw = byteArrayOf(1, 2, 3)
        val parsed = MailSocket.parseMsg(msgFrame("spam-1", MailSocket.FLAG_SPAM, "a@maemail.org", raw))!!
        assertEquals("spam-1", parsed.id)
        // Spam bit set, plaintext bit clear → encrypted spam.
        assertEquals(MailSocket.MsgFlags(unencrypted = false, spam = true), parsed.flags)
        assertContentEquals(raw, parsed.raw)
    }

    @Test
    fun parseMsgPlainAndSpamBits() {
        val flags = (MailSocket.FLAG_PLAIN.toInt() or MailSocket.FLAG_SPAM.toInt()).toByte()
        val parsed = MailSocket.parseMsg(msgFrame("both-1", flags, "a@maemail.org", ByteArray(0)))!!
        assertEquals(MailSocket.MsgFlags(unencrypted = true, spam = true), parsed.flags)
    }

    @Test
    fun parseMsgRejectsTruncated() {
        assertNull(MailSocket.parseMsg(byteArrayOf(MailSocket.OP_MSG)))
        assertNull(MailSocket.parseMsg(byteArrayOf(MailSocket.OP_MSG, 0, 0)))
        val full = msgFrame("id", 0, "a@maemail.org", byteArrayOf(1))
        assertNull(MailSocket.parseMsg(full.copyOfRange(0, 10)), "rcpt cut off")
    }

    @Test
    fun parseSubResp() {
        val addr = "a@maemail.org".toByteArray(Charsets.UTF_8)
        val frame = ByteArray(4 + addr.size)
        frame[0] = MailSocket.OP_SUBRESP
        frame[1] = MailSocket.SUB_TAKEN.toByte()
        MailSocket.putU16(frame, 2, addr.size)
        addr.copyInto(frame, 4)
        assertEquals("a@maemail.org" to MailSocket.SUB_TAKEN, MailSocket.parseSubResp(frame))
        assertNull(MailSocket.parseSubResp(frame.copyOfRange(0, 5)))
    }

    @Test
    fun parseKeyRespFoundAndMissing() {
        val addr = "a@maemail.org".toByteArray(Charsets.UTF_8)
        val key = "ARMOR".toByteArray(Charsets.UTF_8)
        val found = ByteArray(1 + 1 + 2 + addr.size + key.size)
        found[0] = MailSocket.OP_GETKEY_RESP
        found[1] = MailSocket.KEY_FOUND.toByte()
        MailSocket.putU16(found, 2, addr.size)
        addr.copyInto(found, 4)
        key.copyInto(found, 4 + addr.size)
        val (parsedAddr, parsedKey) = MailSocket.parseKeyResp(found)!!
        assertEquals("a@maemail.org", parsedAddr)
        assertEquals("ARMOR", parsedKey)
        val missing = ByteArray(1 + 1 + 2 + addr.size)
        missing[0] = MailSocket.OP_GETKEY_RESP
        missing[1] = MailSocket.KEY_NONE.toByte()
        MailSocket.putU16(missing, 2, addr.size)
        addr.copyInto(missing, 4)
        val (_, missingKey) = MailSocket.parseKeyResp(missing)!!
        assertNull(missingKey)
    }

    @Test
    fun parseFetchedRoundTrip() {
        val frame = ByteArray(1 + 2 + 3 + 2)
        frame[0] = MailSocket.OP_FETCH_RESP
        MailSocket.putU16(frame, 1, 3)
        frame[3] = 'a'.code.toByte(); frame[4] = 'b'.code.toByte(); frame[5] = 'c'.code.toByte()
        frame[6] = 1; frame[7] = 2
        val (id, raw) = MailSocket.parseFetched(frame)!!
        assertEquals("abc", id)
        assertTrue(raw.contentEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun u16RoundTrip() {
        val buf = ByteArray(4)
        MailSocket.putU16(buf, 1, 0xABCD)
        assertEquals(0xABCD, MailSocket.readU16(buf, 1))
    }
}
