package com.vayunmathur.communicate.telephony

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice-4 coverage for the outbound MMS PDU composer ([MmsPdu]).
 *
 * Pure JVM: asserts the exact WAP-209/WSP byte layout (headers, recipient
 * encoding, multipart entries) so a regression in the encoder is caught
 * without a carrier round-trip.
 */
class MmsPduSlice4Test {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `text-only send-req has the exact wire layout`() {
        val pdu = MmsPdu.composeSendReq(
            transactionId = "t1",
            recipients = listOf("+1555"),
            text = "hi",
        )
        val ct = "application/vnd.wap.multipart.mixed"
        val expected = byteArrayOf(
            0x8C.toByte(), 0x80.toByte(), // X-Mms-Message-Type: m-send-req
            0x98.toByte(), 0x74, 0x31, 0x00, // Transaction-ID "t1"
            0x8D.toByte(), 0x92.toByte(), // MMS version 1.2
            0x89.toByte(), 0x01, 0x81.toByte(), // From: insert-address-token
            // To: "+1555/TYPE=PLMN" as UTF-8 encoded-string (0x11 = 17 value bytes).
            0x97.toByte(), 0x11,
            0xEA.toByte(), 0x2B, 0x31, 0x35, 0x35, 0x35,
            0x2F, 0x54, 0x59, 0x50, 0x45, 0x3D, 0x50, 0x4C, 0x4D, 0x4E, 0x00,
            // Content-Type: multipart.mixed text-string.
            0x84.toByte(),
        ) + ct.toByteArray(Charsets.UTF_8) + byteArrayOf(0x00) + byteArrayOf(
            0x01, // one part
            // Part 0: headersLen=23, dataLen=2, ct "text/plain", location "text_0.txt", "hi".
            0x17, 0x02,
            0x74, 0x65, 0x78, 0x74, 0x2F, 0x70, 0x6C, 0x61, 0x69, 0x6E, 0x00,
            0x8E.toByte(),
            0x74, 0x65, 0x78, 0x74, 0x5F, 0x30, 0x2E, 0x74, 0x78, 0x74, 0x00,
            0x68, 0x69,
        )
        assertContentEquals(expected, pdu)
    }

    @Test
    fun `group send carries one To header per recipient`() {
        val pdu = MmsPdu.composeSendReq(
            transactionId = "t1",
            recipients = listOf("+1111", "+2222"),
            text = "hi",
        )
        val text = String(pdu, Charsets.UTF_8)
        assertTrue(text.contains("+1111/TYPE=PLMN"))
        assertTrue(text.contains("+2222/TYPE=PLMN"))
        // Two 0x97 To field markers.
        assertEquals(2, pdu.count { it == 0x97.toByte() })
    }

    @Test
    fun `media parts get indexed default names by kind`() {
        val pdu = MmsPdu.composeSendReq(
            transactionId = "t1",
            recipients = listOf("+1555"),
            text = null,
            mediaParts = listOf(
                MmsPdu.Part("image/jpeg", byteArrayOf(1, 2, 3)),
                MmsPdu.Part("video/mp4", byteArrayOf(4, 5)),
                MmsPdu.Part("application/octet-stream", byteArrayOf(6)),
            ),
        )
        val text = String(pdu, Charsets.UTF_8)
        assertTrue(text.contains("image_0.jpeg"))
        assertTrue(text.contains("video_1.mp4"))
        assertTrue(text.contains("part_2"))
        // Text part is skipped when the body is null: 3 parts, not 4.
        // Part count uintvar sits right after the multipart.mixed NUL.
        val ctEnd = text.indexOf("application/vnd.wap.multipart.mixed") +
            "application/vnd.wap.multipart.mixed".length + 1
        assertEquals(3, pdu[ctEnd].toInt())
        // Media bytes are embedded verbatim.
        assertTrue(hex(pdu).contains("01 02 03"))
    }

    @Test
    fun `explicit part name wins over the default`() {
        val pdu = MmsPdu.composeSendReq(
            transactionId = "t1",
            recipients = listOf("+1555"),
            text = "hi",
            mediaParts = listOf(MmsPdu.Part("image/png", byteArrayOf(9), name = "custom.png")),
        )
        assertTrue(String(pdu, Charsets.UTF_8).contains("custom.png"))
    }

    @Test
    fun `empty body composes zero parts`() {
        val pdu = MmsPdu.composeSendReq(
            transactionId = "t1",
            recipients = listOf("+1555"),
            text = null,
        )
        val text = String(pdu, Charsets.UTF_8)
        val ctEnd = text.indexOf("application/vnd.wap.multipart.mixed") +
            "application/vnd.wap.multipart.mixed".length + 1
        assertEquals(0, pdu[ctEnd].toInt())
    }

    @Test
    fun `transaction id with a high first byte gets the quote prefix`() {
        // "ïd": first UTF-8 byte is 0xC3 >= 0x80, so the text-string needs 0x7F.
        val pdu = MmsPdu.composeSendReq(
            transactionId = "ïd",
            recipients = listOf("+1555"),
            text = "hi",
        )
        val h = hex(pdu)
        // 98 7F C3 AF 64 00
        assertTrue(h.contains("98 7F C3 AF 64 00"), h)
    }

    @Test
    fun `large part lengths use multi-byte uintvar encoding`() {
        val big = ByteArray(200) { it.toByte() }
        val pdu = MmsPdu.composeSendReq(
            transactionId = "t1",
            recipients = listOf("+1555"),
            text = null,
            mediaParts = listOf(MmsPdu.Part("image/jpeg", big)),
        )
        // Data length 200 encodes as 0x81 0x48 (uintvar, 7 bits per byte).
        assertTrue(hex(pdu).contains("81 48"), hex(pdu))
        // Payload survives intact at the tail.
        assertContentEquals(big, pdu.copyOfRange(pdu.size - 200, pdu.size))
    }

    @Test
    fun `text part name defaults to text index form`() {
        val pdu = MmsPdu.composeSendReq(
            transactionId = "t1",
            recipients = listOf("+1555"),
            text = "hello",
            mediaParts = listOf(MmsPdu.Part("image/jpeg", byteArrayOf(1))),
        )
        val text = String(pdu, Charsets.UTF_8)
        assertTrue(text.contains("text_0.txt"))
        assertTrue(text.contains("image_1.jpeg"))
        assertTrue(text.contains("hello"))
    }
}
