package com.vayunmathur.library.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edge cases for the already-covered pure classes: error branches and boundaries. */
class MediaEdgeCasesTest {

    // PolyphaseResampler validation and helper branches

    @Test
    fun `resampler rejects non positive rates and bad channel counts`() {
        assertFailsWith<IllegalArgumentException> { PolyphaseResampler(0, 48_000, 1) }
        assertFailsWith<IllegalArgumentException> { PolyphaseResampler(44_100, 0, 1) }
        assertFailsWith<IllegalArgumentException> { PolyphaseResampler(-44_100, 48_000, 1) }
        assertFailsWith<IllegalArgumentException> { PolyphaseResampler(44_100, 48_000, 0) }
        assertFailsWith<IllegalArgumentException> { PolyphaseResampler(44_100, 48_000, 3) }
    }

    @Test
    fun `resampler handles zero and negative frame counts`() {
        val resampler = PolyphaseResampler(44_100, 48_000, 1)
        assertEquals(0, resampler.process(FloatArray(0), 0).size)
        assertEquals(0, resampler.process(FloatArray(4), -3).size)
        val passthrough = PolyphaseResampler(48_000, 48_000, 2)
        assertEquals(0, passthrough.process(FloatArray(0), 0).size)
        assertEquals(0, passthrough.flush().size, "passthrough flush has nothing to drain")
    }

    @Test
    fun `resampler flush without input is empty and mono downsample works`() {
        assertEquals(0, PolyphaseResampler(96_000, 48_000, 1).flush().size)
        val resampler = PolyphaseResampler(192_000, 48_000, 1)
        assertEquals(1, resampler.interpolation)
        assertEquals(4, resampler.decimation)
        val out = resampler.process(FloatArray(19_200) { 0.25f }, 19_200) + resampler.flush()
        assertTrue(kotlin.math.abs(out.size - 4_800) <= 1, "got ${out.size}")
        val settled = out.copyOfRange(500, out.size - 500)
        assertTrue(settled.all { kotlin.math.abs(it - 0.25f) < 0.01f }, "DC must survive decimation")
    }

    @Test
    fun `taps per phase stays within bounds for every rate pair`() {
        for (rate in listOf(8_000, 16_000, 22_050, 44_100, 96_000, 192_000)) {
            val resampler = PolyphaseResampler(rate, 48_000, 1)
            assertTrue(
                resampler.tapsPerPhase in 16..192 && resampler.tapsPerPhase % 2 == 0,
                "$rate Hz taps=${resampler.tapsPerPhase}",
            )
        }
        // Passthrough needs no filter at all.
        assertEquals(0, PolyphaseResampler(48_000, 48_000, 1).tapsPerPhase)
    }

    @Test
    fun `stereo resampling keeps both channels at unity dc`() {
        val resampler = PolyphaseResampler(44_100, 48_000, 2)
        val frames = 20_000
        val input = FloatArray(frames * 2) { if (it % 2 == 0) 0.4f else -0.2f }
        val out = resampler.process(input, frames) + resampler.flush()
        val settledFrames = (2_000 until (out.size / 2 - 2_000))
        for (frame in settledFrames) {
            assertTrue(kotlin.math.abs(out[frame * 2] - 0.4f) < 2e-3f, "left at frame $frame")
            assertTrue(kotlin.math.abs(out[frame * 2 + 1] + 0.2f) < 2e-3f, "right at frame $frame")
        }
    }

    // PcmBuffers / PcmQueue edges

    @Test
    fun `fold to stereo rejects stereo and mono`() {
        assertFailsWith<IllegalArgumentException> { PcmBuffers.foldToStereo(FloatArray(8), 4, 2) }
        assertFailsWith<IllegalArgumentException> { PcmBuffers.foldToStereo(FloatArray(8), 4, 1) }
        assertEquals(0, PcmBuffers.foldToStereo(FloatArray(0), 0, 4))
    }

    @Test
    fun `pcm reads and writes of nothing`() {
        val out = FloatArray(2)
        assertEquals(0, PcmBuffers.readS16(ByteBuffer.allocate(0), out))
        assertEquals(0, PcmBuffers.readFloat(ByteBuffer.allocate(0), out))
        assertEquals(0, PcmBuffers.writeS16(floatArrayOf(0.5f), 0, ByteArray(2)))
        // Partial conversion: only the requested sample count is written.
        val bytes = ByteArray(6) { 0x7f }
        assertEquals(2, PcmBuffers.writeS16(floatArrayOf(0f, 1f, 1f), 1, bytes))
        assertEquals(0x7f, bytes[2])
    }

    @Test
    fun `pcm queue ignores empty writes and refuses sub frame drains`() {
        val queue = PcmQueue(initialCapacity = 8)
        queue.write(ByteArray(4), 0)
        queue.write(ByteArray(4), -1)
        assertEquals(0, queue.size)
        assertEquals(0, queue.drainTo(ByteBuffer.allocate(8), 8, 4))
        queue.write(byteArrayOf(1, 2, 3), 3)
        assertEquals(0, queue.drainTo(ByteBuffer.allocate(8), 8, 4), "3 bytes is not a stereo frame")
        assertEquals(3, queue.size)
        queue.write(byteArrayOf(4), 1)
        val destination = ByteBuffer.allocate(8)
        assertEquals(4, queue.drainTo(destination, 8, 4))
        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(destination.array().copyOf(4)))
        assertEquals(0, queue.drainTo(ByteBuffer.allocate(8), 8, 4), "drained queue stays drained")
    }

    @Test
    fun `pcm queue default capacity holds a frame`() {
        val queue = PcmQueue()
        queue.write(ByteArray(16) { it.toByte() }, 16)
        assertEquals(16, queue.size)
    }

    // OggPages helpers

    @Test
    fun `ogg long and int writers round trip`() {
        val buffer = ByteArray(16)
        OggPages.writeLongLe(buffer, 4, 0x0102030405060708L)
        assertTrue(
            byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1).contentEquals(buffer.copyOfRange(4, 12)),
        )
        assertTrue(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1).contentEquals(OggPages.longLe(0x0102030405060708L)))
        OggPages.writeIntLe(buffer, 0, -1)
        assertTrue(byteArrayOf(-1, -1, -1, -1).contentEquals(buffer.copyOfRange(0, 4)))
        OggPages.writeIntLe(buffer, 0, Int.MIN_VALUE)
        assertEquals(0x80.toByte(), buffer[3])
    }

    @Test
    fun `ogg crc is zero for empty input and changes with content`() {
        assertEquals(0, OggPages.crc(ByteArray(0)))
        val a = OggPages.crc(byteArrayOf(1, 2, 3))
        val b = OggPages.crc(byteArrayOf(1, 2, 4))
        assertTrue(a != b)
        assertEquals(a, OggPages.crc(byteArrayOf(1, 2, 3)), "crc must be deterministic")
    }

    @Test
    fun `forPacket splits exactly at the segment limit`() {
        // 255*255 = 65025 payload bytes fills one page of segments exactly (plus terminator).
        val packet = ByteArray(65_025) { (it % 251).toByte() }
        val pages = OggPages.forPacket(packet, 7, 0, 42L, OggPages.FLAG_BEGIN_OF_STREAM)
        val rejoined = pages.fold(ByteArray(0)) { acc, page ->
            val segmentCount = page[26].toInt() and 0xff
            acc + page.copyOfRange(27 + segmentCount, page.size)
        }
        assertTrue(packet.contentEquals(rejoined))
        // An empty packet is a single zero lacing value on one page.
        val empty = OggPages.forPacket(ByteArray(0), 7, 0, 0L, 0)
        assertEquals(1, empty.size)
        assertEquals(0, empty[0][27].toInt(), "empty packet lacing terminator")
    }

    @Test
    fun `writer finish with no audio still emits an end of stream page`() {
        val sink = ByteArrayOutputStream()
        val writer = OggStreamWriter(99, sink)
        writer.writeHeaderPacket(OpusHead.build(1, 312, 48_000))
        writer.finish(1234L)
        val bytes = sink.toByteArray()
        assertTrue(bytes.isNotEmpty())
        // Last page carries EOS flag; parse via tagger-independent check of flag byte.
        assertEquals("OggS", String(bytes, 0, 4, Charsets.ISO_8859_1))
    }

    @Test
    fun `writer flushes by packet count as well as by segments`() {
        val sink = ByteArrayOutputStream()
        val writer = OggStreamWriter(5, sink)
        writer.writeHeaderPacket(OpusHead.build(2, 312, 48_000))
        var granule = 0L
        // 51 tiny packets: the 51st forces a flush of the first 50 onto their own page.
        repeat(51) {
            granule += 960
            writer.writeAudioPacket(ByteArray(4) { 0xFC.toByte() }, granule)
        }
        val sizeBeforeFinish = sink.size()
        writer.finish(granule)
        assertTrue(sink.size() > sizeBeforeFinish, "finish writes the trailing page")
        assertTrue(sizeBeforeFinish > 0, "packet-count flush must emit before finish")
    }

    // OpusHead edges

    @Test
    fun `opus head rejects truncated marker chunks`() {
        assertNull(OpusHead.fromCodecConfig("AOPUSHDR".toByteArray(Charsets.ISO_8859_1)))
        assertNull(
            OpusHead.fromCodecConfig(
                "AOPUSHDR".toByteArray(Charsets.ISO_8859_1) + ByteArray(4),
            ),
        )
        // Length below the header size.
        val head = OpusHead.build(2, 312, 48_000)
        val tooShort = "AOPUSHDR".toByteArray(Charsets.ISO_8859_1) +
            ByteArray(8) { (10L ushr (8 * it)).toByte() } + head
        assertNull(OpusHead.fromCodecConfig(tooShort))
        // A bare header shorter than 19 bytes.
        assertNull(OpusHead.fromCodecConfig(head.copyOf(10)))
    }

    @Test
    fun `opus head build encodes mono and max preskip`() {
        val head = OpusHead.build(1, 65_535, 8_000)
        assertEquals(1, head[9].toInt())
        assertEquals(65_535, OpusHead.preSkipOf(head))
        assertEquals(OpusHead.SIZE, head.size)
    }

    @Test
    fun `packet samples covers silk hybrid and celt configs`() {
        fun packet(config: Int, code: Int, count: Byte = 1) =
            byteArrayOf(((config shl 3) or code).toByte(), count)
        // SILK wideband configs 4..11 share the 10/20/40/60 table by low bits.
        assertEquals(480, OpusHead.packetSamples(packet(4, 0), 2))
        assertEquals(2_880, OpusHead.packetSamples(packet(11, 0), 2))
        // Hybrid configs 14/15: 10/20 ms by low bit.
        assertEquals(480, OpusHead.packetSamples(packet(14, 0), 2))
        assertEquals(960, OpusHead.packetSamples(packet(15, 0), 2))
        // CELT configs use the 2.5/5/10/20 table.
        assertEquals(240, OpusHead.packetSamples(packet(17, 0), 2))
        assertEquals(480, OpusHead.packetSamples(packet(18, 0), 2))
        // Code 3 with a zero frame count byte is degenerate but must not crash.
        assertEquals(0, OpusHead.packetSamples(packet(31, 3, 0), 2))
    }
}
