package com.vayunmathur.auto.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameReaderTest {

    private val writer = FrameWriter()

    private fun payload(size: Int) = Random(size).nextBytes(size)

    @Test
    fun `a single-frame message comes back whole`() {
        val body = payload(64)
        val reader = FrameReader()

        val messages = reader.offer(writer.frame(2, body).single())

        val message = messages.single()
        assertEquals(2, message.channelId)
        assertFalse(message.isControl)
        assertContentEquals(body, message.payload)
    }

    @Test
    fun `a fragmented message is reassembled in order`() {
        val body = payload(50_000)
        val frames = writer.frame(5, body)
        assertTrue(frames.size > 1, "expected this to fragment")

        val reader = FrameReader()
        val collected = frames.flatMap { reader.offer(it) }

        assertContentEquals(body, collected.single().payload)
        assertFalse(reader.hasPartialMessages)
    }

    @Test
    fun `a message split across arbitrary read boundaries still reassembles`() {
        // The transport hands over whatever it has; a read can end mid-header.
        val body = payload(40_000)
        val wire = writer.frame(3, body).reduce { a, b -> a + b }

        val reader = FrameReader()
        val collected = mutableListOf<ReassembledMessage>()
        var offset = 0
        val chunk = 777 // deliberately not a frame or header multiple
        while (offset < wire.size) {
            val count = minOf(chunk, wire.size - offset)
            collected += reader.offer(wire, offset, count)
            offset += count
        }

        assertContentEquals(body, collected.single().payload)
    }

    @Test
    fun `a fragmented message fed one byte at a time still reassembles`() {
        // Regression: the first fragment has an 8-byte long header, so a
        // one-byte feed passes through 4-7 buffered bytes, where reading the
        // header used to throw instead of waiting for the rest of it.
        val body = payload(50_000)
        val wire = writer.frame(3, body).reduce { a, b -> a + b }

        val reader = FrameReader()
        val collected = mutableListOf<ReassembledMessage>()
        for (byte in wire) {
            collected += reader.offer(byteArrayOf(byte))
        }

        assertContentEquals(body, collected.single().payload)
    }

    @Test
    fun `all fragments delivered in one read reassemble without another read`() {
        // Regression: offer() stopped draining after a non-final fragment
        // (it conflated "fragment consumed" with "need more bytes"), so a
        // fragmented message delivered whole in one read only completed
        // when yet another read arrived; at end of stream it stalled.
        val body = payload(50_000)
        val wire = writer.frame(3, body).reduce { a, b -> a + b }

        val collected = FrameReader().offer(wire)

        assertContentEquals(body, collected.single().payload)
    }

    @Test
    fun `a hostile total length hint neither throws nor breaks reassembly`() {
        // Regression: totalLength is peer-controlled. A negative value made
        // ByteArrayOutputStream(capacity) throw out of the pump loop, and a
        // huge one attempted a gigabyte-scale up-front allocation. The hint
        // must be ignored (the stream grows) while reassembly still works.
        val first = payload(10)
        val rest = payload(5)
        fun frame(flags: Int, total: Int?, body: ByteArray): ByteArray {
            val headerSize = if (total != null) 8 else 4
            val out = java.nio.ByteBuffer.allocate(headerSize + body.size)
            out.put(3.toByte()).put(flags.toByte()).putShort(body.size.toShort())
            if (total != null) out.putInt(total)
            out.put(body)
            return out.array()
        }
        for (hostile in listOf(-1, Int.MAX_VALUE)) {
            val reader = FrameReader()
            val wire = frame(FrameFlags.FIRST, hostile, first) +
                frame(FrameFlags.LAST, null, rest)
            val collected = reader.offer(wire)
            assertContentEquals(first + rest, collected.single().payload)
        }
    }

    @Test
    fun `one byte at a time still reassembles`() {
        val body = payload(200)
        val wire = writer.frame(1, body).single()

        val reader = FrameReader()
        val collected = mutableListOf<ReassembledMessage>()
        for (byte in wire) {
            collected += reader.offer(byteArrayOf(byte))
        }

        assertContentEquals(body, collected.single().payload)
    }

    @Test
    fun `several messages in one read all come back`() {
        val first = payload(10)
        val second = payload(20)
        val wire = writer.frame(1, first).single() + writer.frame(2, second).single()

        val messages = FrameReader().offer(wire)

        assertEquals(2, messages.size)
        assertContentEquals(first, messages[0].payload)
        assertContentEquals(second, messages[1].payload)
        assertEquals(listOf(1, 2), messages.map { it.channelId })
    }

    @Test
    fun `fragmented messages on different channels interleave without mixing`() {
        val reader = FrameReader()
        val a = payload(30_000)
        val b = payload(30_000)
        val framesA = writer.frame(3, a)
        val framesB = writer.frame(4, b)

        val collected = mutableListOf<ReassembledMessage>()
        // Interleave: a0 b0 a1 b1 ...
        for (i in 0 until maxOf(framesA.size, framesB.size)) {
            framesA.getOrNull(i)?.let { collected += reader.offer(it) }
            framesB.getOrNull(i)?.let { collected += reader.offer(it) }
        }

        assertEquals(2, collected.size)
        assertContentEquals(a, collected.single { it.channelId == 3 }.payload)
        assertContentEquals(b, collected.single { it.channelId == 4 }.payload)
    }

    @Test
    fun `the control flag survives the round trip`() {
        val message = FrameReader()
            .offer(writer.frame(0, payload(8), isControl = true).single())
            .single()
        assertTrue(message.isControl)
    }

    @Test
    fun `encrypted frames are decrypted per frame before reassembly`() {
        // A stand-in that changes length, which is the property that matters: the header
        // counts ciphertext while the reassembly buffer accumulates plaintext.
        val pad = byteArrayOf(0x7F)
        val writer = FrameWriter(encrypt = { pad + it })
        val reader = FrameReader(decrypt = { it.copyOfRange(1, it.size) })

        val body = payload(40_000)
        val collected = writer
            .frame(2, body, encrypted = true)
            .flatMap { reader.offer(it) }

        assertContentEquals(body, collected.single().payload)
    }

    @Test
    fun `an empty message round-trips`() {
        val message = FrameReader().offer(writer.frame(1, ByteArray(0)).single()).single()
        assertEquals(0, message.payload.size)
    }

    @Test
    fun `a partial frame yields nothing and leaves the reader waiting`() {
        val wire = writer.frame(1, payload(100)).single()
        val reader = FrameReader()

        assertTrue(reader.offer(wire, 0, 3).isEmpty(), "header incomplete")
        assertTrue(reader.offer(wire, 3, 50).isEmpty(), "payload incomplete")
        assertEquals(1, reader.offer(wire, 53, wire.size - 53).size)
    }

    @Test
    fun `an undecryptable frame fires the teardown hook and is dropped`() {
        // Corrupt ciphertext is malformed input (`rtr` answers 0xFFFF and tears
        // down): the reader drops the frame and fires the hook rather than
        // throwing out of the pump loop or feeding garbage to reassembly.
        var fired = 0
        val reader = FrameReader(
            decrypt = { error("corrupt record") },
            onFramingError = { fired++ },
        )
        val wire = writer.frame(1, payload(64), encrypted = true).single()

        assertTrue(reader.offer(wire).isEmpty(), "the bad frame is dropped")
        assertEquals(1, fired)
    }

    @Test
    fun `plaintext frames never touch the teardown hook`() {
        var fired = 0
        val reader = FrameReader(onFramingError = { fired++ })

        val message = reader.offer(writer.frame(1, payload(16)).single()).single()

        assertEquals(16, message.payload.size)
        assertEquals(0, fired)
    }
}
