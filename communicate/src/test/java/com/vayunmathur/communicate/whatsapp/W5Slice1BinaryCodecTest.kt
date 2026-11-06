package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.buildFramedMessage
import com.vayunmathur.communicate.data.whatsapp.decodeNode
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.extractFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): binary XML codec + frame helpers (WhatsAppProtocolBinary.kt).
 * Pure JVM. Round-trip encode→decode must preserve tag/attrs/children/data.
 */
class W5Slice1BinaryCodecTest {

    // NOTE: BinaryEncoder's buffer starts with a 0x00 element (mutableListOf(0) is one
    // element, not capacity) which decodeNode's unpack() consumes as the compression-flag
    // byte, so encodeNode output decodes directly. That seed is load-bearing wire format;
    // production code is intentionally untouched.
    private fun roundTrip(node: WhatsAppProtocol.Node): WhatsAppProtocol.Node =
        WhatsAppProtocol.decodeNode(WhatsAppProtocol.encodeNode(node))

    @Test
    fun roundTrip_simpleNodeWithAttrs() {
        val node = WhatsAppProtocol.Node(
            tag = "message",
            attrs = mapOf("to" to "123@s.whatsapp.net", "id" to "ABC123", "type" to "text"),
        )
        assertEquals(node, roundTrip(node))
    }

    @Test
    fun roundTrip_nestedChildren() {
        val node = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf("id" to "1", "type" to "get", "xmlns" to "usync"),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "usync",
                    attrs = mapOf("mode" to "query"),
                    content = listOf(WhatsAppProtocol.Node(tag = "query")),
                ),
            ),
        )
        assertEquals(node, roundTrip(node))
    }

    @Test
    fun roundTrip_binaryData() {
        val data = ByteArray(64) { it.toByte() }
        val node = WhatsAppProtocol.Node(tag = "enc", attrs = mapOf("v" to "2", "type" to "pkmsg"), data = data)
        val back = roundTrip(node)
        assertEquals("enc", back.tag)
        assertEquals(mapOf("v" to "2", "type" to "pkmsg"), back.attrs)
        assertTrue(back.data!!.contentEquals(data))
    }

    @Test
    fun roundTrip_jidAttributes_useJidPairEncoding() {
        val node = WhatsAppProtocol.Node(
            tag = "user",
            attrs = mapOf("jid" to "15551234567@s.whatsapp.net"),
        )
        assertEquals(node, roundTrip(node))
    }

    @Test
    fun roundTrip_deviceJid_usesAdJidEncoding() {
        val node = WhatsAppProtocol.Node(
            tag = "user",
            attrs = mapOf("jid" to "15551234567.0:1@s.whatsapp.net"),
        )
        assertEquals(node, roundTrip(node))
    }

    @Test
    fun roundTrip_numericStrings_usePackedEncoding() {
        val node = WhatsAppProtocol.Node(
            tag = "item",
            attrs = mapOf("id" to "123456789012345", "code" to "ABCDEF1234"),
        )
        assertEquals(node, roundTrip(node))
    }

    @Test
    fun roundTrip_emptyAttrsAreDropped() {
        val node = WhatsAppProtocol.Node(tag = "x", attrs = mapOf("a" to "", "b" to "v"))
        val back = roundTrip(node)
        assertEquals(mapOf("b" to "v"), back.attrs)
    }

    @Test
    fun roundTrip_unicodeRawString() {
        val node = WhatsAppProtocol.Node(tag = "body", data = "héllo wörld 👋".toByteArray(Charsets.UTF_8))
        val back = roundTrip(node)
        assertEquals("héllo wörld 👋", String(back.data!!, Charsets.UTF_8))
    }

    @Test
    fun decode_emptyFrame_throws() {
        assertFailsWith<IllegalStateException> { WhatsAppProtocol.decodeNode(ByteArray(0)) }
    }

    @Test
    fun node_helpers_getChildrenAndChildByTag() {
        val child = WhatsAppProtocol.Node(tag = "body")
        val node = WhatsAppProtocol.Node(tag = "message", content = listOf(child))
        assertEquals(listOf(child), node.getChildren())
        assertEquals(child, node.getChildByTag("body"))
        assertEquals(null, node.getChildByTag("missing"))
    }

    @Test
    fun node_equalsAndHashCode_withData() {
        val a = WhatsAppProtocol.Node(tag = "enc", data = byteArrayOf(1, 2, 3))
        val b = WhatsAppProtocol.Node(tag = "enc", data = byteArrayOf(1, 2, 3))
        val c = WhatsAppProtocol.Node(tag = "enc", data = byteArrayOf(1, 2, 4))
        val d = WhatsAppProtocol.Node(tag = "enc")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
        assertTrue(a != d)
        assertTrue(d != a)
        assertFalse(a.equals("not a node"))
    }

    // ---- frame helpers ----

    @Test
    fun framedMessage_withHeader_roundTripsThroughExtract() {
        val payload = "frame payload".toByteArray()
        val header = byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 6, 3)
        val frame = WhatsAppProtocol.buildFramedMessage(payload, header)
        assertEquals(4 + 3 + payload.size, frame.size)
        assertTrue(frame.take(4).toByteArray().contentEquals(header))
        assertTrue(WhatsAppProtocol.extractFrame(frame.drop(4).toByteArray()).contentEquals(payload))
    }

    @Test
    fun framedMessage_noHeader_lengthPrefixOnly() {
        val payload = ByteArray(300) { it.toByte() }
        val frame = WhatsAppProtocol.buildFramedMessage(payload, null)
        assertEquals(3 + 300, frame.size)
        assertTrue(WhatsAppProtocol.extractFrame(frame).contentEquals(payload))
    }

    @Test
    fun framedMessage_tooLarge_throws() {
        assertFailsWith<IllegalArgumentException> {
            WhatsAppProtocol.buildFramedMessage(ByteArray(WhatsAppProtocol.FRAME_MAX_SIZE), null)
        }
    }

    @Test
    fun extractFrame_shortOrTruncated_returnsInput() {
        val short = byteArrayOf(1, 2)
        assertTrue(WhatsAppProtocol.extractFrame(short).contentEquals(short))
        // claims length 16 but only 3 bytes follow
        val truncated = byteArrayOf(0, 0, 16, 1, 2, 3)
        assertTrue(WhatsAppProtocol.extractFrame(truncated).contentEquals(truncated))
    }

    @Test
    fun binaryToken_lookups() {
        // well-known single-byte token
        val idx = WhatsAppProtocol.BinaryToken.indexOfSingleToken("message")
        assertTrue(idx >= 0, "message should be a single-byte token")
        assertEquals(-1, WhatsAppProtocol.BinaryToken.indexOfSingleToken("definitely-not-a-token-xyz"))
        val (dict, tok, found) = WhatsAppProtocol.BinaryToken.indexOfDoubleByteToken("not-a-token-xyz")
        assertTrue(!found)
        assertEquals(0, dict)
        assertEquals(0, tok)
        assertEquals("", WhatsAppProtocol.BinaryToken.getDoubleToken(99, 0))
        assertEquals("", WhatsAppProtocol.BinaryToken.getDoubleToken(0, 99999))
    }
}
