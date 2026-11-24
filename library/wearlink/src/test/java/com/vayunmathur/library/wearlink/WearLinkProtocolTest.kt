package com.vayunmathur.library.wearlink

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WearLinkProtocolTest {

    @Test
    fun `frame round trips and stream decoder reads consecutive frames`() {
        val frames = listOf(
            WearLinkFrame(FrameType.MESSAGE, 42, "/x/y", "hello".encodeToByteArray()),
            WearLinkFrame(FrameType.DATA, 43, "/d", ByteArray(100) { it.toByte() }),
            WearLinkFrame(FrameType.PING, 44),
        )
        val out = ByteArrayOutputStream()
        frames.forEach { FrameCodec.encode(it, out) }
        val input = ByteArrayInputStream(out.toByteArray())
        frames.forEach { expected ->
            assertEquals(expected, FrameCodec.decode(input))
        }
        assertNull(FrameCodec.decode(input))
    }

    @Test
    fun `frame codec rejects oversize and unknown types`() {
        val big = WearLinkFrame(
            FrameType.MESSAGE, 1, "/x", ByteArray(WearLinkProtocol.MAX_FRAME_PAYLOAD),
        )
        assertFailsWith<IllegalArgumentException> { FrameCodec.encodeToBytes(big) }
        // Corrupt length header.
        val bad = byteArrayOf(0, 0, 0, 2, 99, 0)
        assertFailsWith<java.io.IOException> { FrameCodec.decode(ByteArrayInputStream(bad)) }
    }

    @Test
    fun `handshake accepts same signer and app, rejects other signer or app`() {
        val cert = WearLinkHandshake.certHashHex("cert-der".encodeToByteArray())
        val nonce = ByteArray(WearLinkHandshake.NONCE_BYTES) { it.toByte() }
        val hello = HelloMessage(
            WearLinkProtocol.PROTOCOL_VERSION, "calendar_wear_companion",
            "com.vayunmathur.calendar", "watch-1", nonce,
            WearLinkHandshake.token(cert, nonce, "calendar_wear_companion"),
        )
        assertTrue(
            HandshakeVerifier.verify(hello, "calendar_wear_companion", cert, nonce)
                is HandshakeVerifier.Result.Accepted,
        )
        val otherCert = WearLinkHandshake.certHashHex("other".encodeToByteArray())
        assertTrue(
            HandshakeVerifier.verify(hello, "calendar_wear_companion", otherCert, nonce)
                is HandshakeVerifier.Result.Rejected,
        )
        assertTrue(
            HandshakeVerifier.verify(hello, "contacts_wear_companion", cert, nonce)
                is HandshakeVerifier.Result.Rejected,
        )
        // Replay into a new session (different nonce) fails.
        val newNonce = ByteArray(WearLinkHandshake.NONCE_BYTES) { (it + 9).toByte() }
        assertTrue(
            HandshakeVerifier.verify(hello, "calendar_wear_companion", cert, newNonce)
                is HandshakeVerifier.Result.Rejected,
        )
        // Hello binary round-trip.
        assertEquals(hello, HelloMessage.decode(hello.encode()))
    }

    @Test
    fun `simultaneous dial tie break is symmetric`() {
        assertTrue(HandshakeVerifier.keepInitiatedBySmallerInstance("b", "a"))
        assertFalse(HandshakeVerifier.keepInitiatedBySmallerInstance("a", "b"))
    }

    @Test
    fun `data items chunk, reassemble out of order, reject stale and corrupt`() {
        val payload = ByteArray(40_000) { (it % 251).toByte() }
        val frames = DataItemCodec.chunk("/calendar/snapshot", 7L, payload)
        assertTrue(frames.size > 1)
        val reassembler = DataReassembler()
        var completed: ByteArray? = null
        // Reverse order: still completes only at the last missing chunk.
        for (frame in frames.reversed()) {
            val done = reassembler.onFrame(frame)
            if (done != null) completed = done
        }
        assertNotNull(completed)
        assertTrue(payload.contentEquals(completed))
        // Stale generation is ignored.
        val stale = DataItemCodec.chunk("/calendar/snapshot", 6L, "old".encodeToByteArray())
        assertNull(reassembler.onFrame(stale.first()))
        // Corrupted chunk (flip a byte) never completes.
        val badFrames = DataItemCodec.chunk("/other", 1L, payload)
        val corrupted = badFrames.first().let {
            val chunk = DataChunk.decodeBody(it.body)!!
            val bytes = chunk.bytes.copyOf().also { b -> b[0] = (b[0] + 1).toByte() }
            it.copy(body = chunk.copy(bytes = bytes).encodeBody())
        }
        val r2 = DataReassembler()
        var badCompleted: ByteArray? = null
        for (frame in listOf(corrupted) + badFrames.drop(1)) {
            r2.onFrame(frame)?.let { badCompleted = it }
        }
        assertNull(badCompleted)
    }

    @Test
    fun `stream receiver verifies hash and sender resumes from ack offset`() {
        val data = ByteArray(50_000) { (it % 241).toByte() }
        val header = StreamHeader(data.size.toLong(), Sha256.hex(data))
        val sender = StreamSender(header, data.size.toLong())
        val receiver = StreamReceiver(header)
        val collected = ByteArrayOutputStream()
        while (true) {
            val offset = sender.nextOffset
            val chunk = sender.nextChunk { off, len ->
                data.copyOfRange(off.toInt(), off.toInt() + len)
            } ?: break
            assertTrue(receiver.onChunk(offset, chunk))
            collected.write(chunk)
        }
        assertTrue(sender.complete)
        assertTrue(receiver.verifyComplete())
        assertTrue(data.contentEquals(collected.toByteArray()))

        // Resume: receiver kept first 20_000 bytes, sender seeks.
        val prefix = data.copyOfRange(0, 20_000)
        val resumedReceiver = StreamReceiver(header, 20_000, java.io.ByteArrayInputStream(prefix))
        assertEquals(20_000L, resumedReceiver.ackOffset)
        val resumedSender = StreamSender(header, data.size.toLong())
        resumedSender.seekTo(resumedReceiver.ackOffset)
        while (true) {
            val offset = resumedSender.nextOffset
            val chunk = resumedSender.nextChunk { off, len ->
                data.copyOfRange(off.toInt(), off.toInt() + len)
            } ?: break
            assertTrue(resumedReceiver.onChunk(offset, chunk))
        }
        assertTrue(resumedReceiver.verifyComplete())

        // Tamper fails verification.
        val badReceiver = StreamReceiver(header)
        val badChunk = data.copyOfRange(0, 100).also { it[0] = (it[0] + 1).toByte() }
        assertTrue(badReceiver.onChunk(0, badChunk))
        assertFalse(badReceiver.verifyComplete())
        // Out-of-order chunk aborts.
        val ooo = StreamReceiver(header)
        assertFalse(ooo.onChunk(100, data.copyOfRange(100, 200)))
        assertTrue(ooo.failed)
    }

    @Test
    fun `backoff doubles caps resets and trackers are bounded`() {
        val backoff = WearLinkBackoff(1_000, 30_000)
        assertEquals(1_000L, backoff.nextDelayMillis())
        assertEquals(2_000L, backoff.nextDelayMillis())
        assertEquals(4_000L, backoff.nextDelayMillis())
        repeat(10) { backoff.nextDelayMillis() }
        assertEquals(30_000L, backoff.nextDelayMillis())
        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMillis())

        val tracker = RequestTracker<String>()
        tracker.register(1, "a")
        assertEquals("a", tracker.complete(1))
        assertNull(tracker.complete(1))

        val seen = SeenMessageIds(capacity = 2)
        assertTrue(seen.markIfNew(1))
        assertFalse(seen.markIfNew(1))
        assertTrue(seen.markIfNew(2))
        assertTrue(seen.markIfNew(3)) // evicts 1
        assertTrue(seen.markIfNew(1))
    }

    @Test
    fun `in-memory pipe transports a framed message end to end`() {
        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient)
        val frame = WearLinkFrame(FrameType.MESSAGE, 9, "/contacts/handoff", "x".encodeToByteArray())
        FrameCodec.encode(frame, serverToClient)
        assertEquals(frame, FrameCodec.decode(clientInput))
    }
}
