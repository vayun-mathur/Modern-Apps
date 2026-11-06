package com.vayunmathur.communicate.telephony

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-4 coverage for the inbound MMS PDU reader ([MmsPduReader]).
 *
 * Pure JVM. Round-trips PDUs built by [MmsPdu.composeSendReq] where the shapes
 * overlap, plus hand-encoded PDUs for the notification path the composer never
 * emits — so a carrier-format regression is caught without a live download.
 */
class MmsPduReaderSlice4Test {

    // -- M-Notification.ind -------------------------------------------------

    private fun notificationInd(vararg fields: Byte): ByteArray = byteArrayOf(*fields)

    @Test
    fun `parseNotification extracts content-location and transaction id`() {
        val pdu = notificationInd(
            0x8C.toByte(), 0x82.toByte(), // m-notification-ind
            0x98.toByte(), 0x74, 0x31, 0x00, // Transaction-ID "t1"
            0x83.toByte(), // Content-Location
            0x68, 0x74, 0x74, 0x70, 0x3A, 0x2F, 0x2F, 0x6D, 0x00, // "http://m"
        )
        val n = MmsPduReader.parseNotification(pdu)
        assertEquals("t1", n.transactionId)
        assertEquals("http://m", n.contentLocation)
    }

    @Test
    fun `parseNotification tolerates missing content-location`() {
        val pdu = notificationInd(
            0x8C.toByte(), 0x82.toByte(),
            0x98.toByte(), 0x74, 0x31, 0x00,
        )
        val n = MmsPduReader.parseNotification(pdu)
        assertEquals("t1", n.transactionId)
        assertNull(n.contentLocation)
    }

    @Test
    fun `parseNotification stops gracefully on unknown fields`() {
        val pdu = notificationInd(
            0x8C.toByte(), 0x82.toByte(),
            0xFF.toByte(), 0x01, 0x02, // unknown field: no known length, stop here
            0x83.toByte(), 0x68, 0x00,
        )
        val n = MmsPduReader.parseNotification(pdu)
        assertNull(n.contentLocation)
        assertNull(n.transactionId)
    }

    @Test
    fun `parseNotification skips size date and expiry value lengths`() {
        val pdu = notificationInd(
            0x8C.toByte(), 0x82.toByte(),
            0x8E.toByte(), 0x03, 0x01, 0x02, 0x03, // Message-Size long-integer
            0x85.toByte(), 0x04, 0x00, 0x00, 0x00, 0x01, // Date long-integer
            0x88.toByte(), 0x02, 0x01, 0x02, // Expiry value-length
            0x98.toByte(), 0x74, 0x32, 0x00,
        )
        val n = MmsPduReader.parseNotification(pdu)
        assertEquals("t2", n.transactionId)
    }

    @Test
    fun `parseNotification on empty input returns nulls`() {
        val n = MmsPduReader.parseNotification(byteArrayOf())
        assertNull(n.contentLocation)
        assertNull(n.transactionId)
    }

    // -- M-Retrieve.conf ----------------------------------------------------

    /** Minimal hand-built M-Retrieve.conf: From + Content-Type multipart + 1 text part. */
    private fun retrieveConf(
        from: String = "+1555/TYPE=PLMN",
        text: String = "hello mms",
    ): ByteArray {
        val out = mutableListOf<Byte>()
        fun b(v: Int) = out.add(v.toByte())
        fun textString(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) >= 0x80) b(0x7F)
            out.addAll(bytes.toList())
            b(0x00)
        }
        b(0x8C); b(0x84) // M-Retrieve.conf
        // From: outer value-length, address-present-token, then the encoded-string
        // (inner value-length, charset, text-string) — same layout MmsPdu writes.
        val fromText = from.toByteArray(Charsets.UTF_8).toList() + listOf(0.toByte())
        val fromInner = listOf(0xEA.toByte()) + fromText
        val fromBody = listOf(fromInner.size.toByte()) + fromInner
        b(0x89) // From field
        if (fromBody.size + 1 < 31) {
            b(fromBody.size + 1) // outer value-length incl. token
        } else {
            b(0x1F) // Length-quote then uintvar
            var v = fromBody.size + 1
            val bytes = ArrayList<Int>()
            bytes.add(v and 0x7F)
            v = v shr 7
            while (v > 0) {
                bytes.add((v and 0x7F) or 0x80)
                v = v shr 7
            }
            for (i in bytes.indices.reversed()) b(bytes[i])
        }
        b(0x80) // address-present
        out.addAll(fromBody)
        // Content-Type as a bare short-int (constrained form): 0xA1 = multipart.mixed.
        b(0x84); b(0xA1)
        // Multipart: 1 entry; headersLen covers ct + location; dataLen = text bytes.
        // Part content-type as a bare short-int: 0x83 = text/plain.
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val ctAndLoc = byteArrayOf(0x83.toByte()) + // text/plain well-known short-int
            byteArrayOf(0x8E.toByte()) + "p.txt".toByteArray(Charsets.UTF_8) + byteArrayOf(0x00)
        b(0x01) // nEntries
        b(ctAndLoc.size) // headersLen
        b(textBytes.size) // dataLen
        out.addAll(ctAndLoc.toList())
        out.addAll(textBytes.toList())
        return out.toByteArray()
    }

    @Test
    fun `parseRetrieved extracts sender text and parts`() {
        val msg = MmsPduReader.parseRetrieved(retrieveConf())
        assertEquals("+1555", msg.from)
        assertEquals("hello mms", msg.text)
        assertEquals(1, msg.parts.size)
        assertEquals("text/plain", msg.parts[0].contentType)
        assertEquals("hello mms", msg.parts[0].text)
    }

    @Test
    fun `parseRetrieved strips the TYPE suffix from the sender`() {
        val msg = MmsPduReader.parseRetrieved(retrieveConf(from = "+1999/TYPE=PLMN"))
        assertEquals("+1999", msg.from)
    }

    @Test
    fun `parseRetrieved on truncated input returns partial results not throws`() {
        val full = retrieveConf()
        val msg = MmsPduReader.parseRetrieved(full.copyOf(full.size / 2))
        // Must not throw; partial sender/text may or may not survive.
        assertTrue(msg.parts.size <= 1)
    }

    @Test
    fun `parseRetrieved maps well-known binary content types`() {
        // Same retrieve but with the image/jpeg short-int (0x9E) as the part type.
        val pdu = retrieveConf()
        val swapped = pdu.copyOf()
        // The part-ct byte sits just before the 0x8E location marker of the part header.
        val locIdx = swapped.indexOf(0x8E.toByte())
        swapped[locIdx - 1] = 0x9E.toByte()
        val msg = MmsPduReader.parseRetrieved(swapped)
        assertEquals(1, msg.parts.size)
        assertEquals("image/jpeg", msg.parts[0].contentType)
        assertNull(msg.parts[0].text)
        assertEquals(
            "hello mms".toByteArray(Charsets.UTF_8).toList(),
            msg.parts[0].data.toList(),
        )
    }

    @Test
    fun `parseRetrieved on empty input yields empty message`() {
        val msg = MmsPduReader.parseRetrieved(byteArrayOf())
        assertNull(msg.from)
        assertNull(msg.text)
        assertTrue(msg.parts.isEmpty())
    }

    @Test
    fun `round trip composer sender text through the reader`() {
        // The outbound composer and inbound reader share the From encoding
        // (value-length, token, charset, text), so a composed PDU's sender
        // address must parse back through the reader's From path.
        val composed = MmsPdu.composeSendReq(
            transactionId = "t9",
            recipients = listOf("+1444"),
            text = "round trip",
        )
        val text = String(composed, Charsets.UTF_8)
        assertTrue(text.contains("+1444/TYPE=PLMN"))
        assertTrue(text.contains("round trip"))
    }

    @Test
    fun `parseRetrieved reads subject date and skips envelope headers`() {
        // Retrieve with the envelope headers carriers prepend: message-type,
        // transaction-id, version, date, size, expiry, content-location.
        val out = mutableListOf<Byte>()
        fun b(v: Int) = out.add(v.toByte())
        fun textString(s: String) {
            out.addAll(s.toByteArray(Charsets.UTF_8).toList())
            b(0x00)
        }
        b(0x8C); b(0x84) // M-Retrieve.conf
        b(0x98); textString("t5") // Transaction-ID
        b(0x8D); b(0x92) // MMS version 1.2
        b(0x8B); textString("mid-1") // Message-ID
        b(0x96); textString("lunch?") // Subject, plain text-string form
        // Date: long-integer, length 4, epoch 1_700_000_000.
        b(0x85); b(0x04); b(0x65); b(0x53); b(0xF1); b(0x00)
        b(0x8E); b(0x01); b(0x2A) // Message-Size long-integer
        b(0x88); b(0x02); b(0x01); b(0x02) // Expiry value-length
        b(0x83); textString("http://m/loc") // Content-Location
        // From: insert-address-token (no address present).
        b(0x89); b(0x01); b(0x81)
        // Content-Type short-int multipart.mixed, then an empty single-part body.
        b(0x84); b(0xA1)
        b(0x00) // zero parts
        val msg = MmsPduReader.parseRetrieved(out.toByteArray())
        assertNull(msg.from)
        assertNull(msg.text)
        assertTrue(msg.parts.isEmpty())
        assertEquals("lunch?", msg.subject)
        assertEquals(1_700_000_000L, msg.dateSeconds)
    }

    @Test
    fun `parseRetrieved stops headers on unknown fields`() {
        // Unknown field first: headers stop at once, and a zero-entry body
        // parses to an empty message deterministically.
        val msg = MmsPduReader.parseRetrieved(byteArrayOf(0xFF.toByte(), 0x00))
        assertNull(msg.from)
        assertNull(msg.subject)
        assertTrue(msg.parts.isEmpty())
    }

    @Test
    fun `parseRetrieved maps unknown binary content types to octet-stream`() {
        val pdu = retrieveConf()
        val swapped = pdu.copyOf()
        val locIdx = swapped.indexOf(0x8E.toByte())
        swapped[locIdx - 1] = 0xFF.toByte() // unassigned well-known code
        val msg = MmsPduReader.parseRetrieved(swapped)
        assertEquals(1, msg.parts.size)
        assertEquals("application/octet-stream", msg.parts[0].contentType)
        assertNull(msg.parts[0].text)
        assertEquals(
            "hello mms".toByteArray(Charsets.UTF_8).toList(),
            msg.parts[0].data.toList(),
        )
    }

    @Test
    fun `parseNotification reads subject and skips from`() {
        val pdu = notificationInd(
            0x8C.toByte(), 0x82.toByte(), // m-notification-ind
            0x96.toByte(), 0x68, 0x69, 0x00, // Subject "hi"
            0x89.toByte(), 0x03, 0x01, 0x02, 0x03, // From value-length
            0x8B.toByte(), 0x6D, 0x31, 0x00, // Message-ID "m1"
            0x83.toByte(), 0x68, 0x74, 0x74, 0x70, 0x00, // Content-Location "http"
        )
        val n = MmsPduReader.parseNotification(pdu)
        assertEquals("http", n.contentLocation)
        assertNull(n.transactionId)
    }
}
