package com.vayunmathur.maps.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A DEFLATE-codec ZSEC section of [raw], built the way tilecodec `write_zsec_deflate` builds
 * it (raw DEFLATE per block, absolute u64 offsets, codec 1), for the planet5 POI reader tests.
 */
internal fun zsecDeflate(raw: ByteArray, blockSize: Int, codec: Int = PoiBlockSection.CODEC_DEFLATE): ByteArray {
    val blocks = (0 until raw.size step blockSize).map { start ->
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(raw, start, minOf(blockSize, raw.size - start))
        d.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        out.toByteArray()
    }
    val tableEnd = PoiBlockSection.HEADER_BYTES + PoiBlockSection.OFFSET_BYTES * (blocks.size + 1)
    val total = tableEnd + blocks.sumOf { it.size }
    val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    b.put("ZSEC".toByteArray(Charsets.US_ASCII)).putInt(blockSize).putLong(raw.size.toLong())
    b.putInt(blocks.size).putInt(codec)
    var at = tableEnd.toLong()
    for (blk in blocks) {
        b.putLong(at)
        at += blk.size
    }
    b.putLong(at)
    for (blk in blocks) b.put(blk)
    return b.array()
}

class PoiBlockSectionTest {
    private val raw = ByteArray(10_000) { i -> ((i * 31) % 251 xor (i shr 7)).toByte() }
    private val plain = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

    private fun section(blockSize: Int): PoiBytes =
        assertNotNull(PoiBytes.of(ByteBuffer.wrap(zsecDeflate(raw, blockSize))), "block section refused")

    @Test
    fun `every read matches the raw bytes, across block edges`() {
        for (bs in listOf(7, 64, 1000, 65_536)) {
            val s = section(bs)
            assertTrue(s is PoiBlockSection)
            assertEquals(raw.size, s.size)
            val rnd = Random(bs)
            repeat(2_000) {
                val p = rnd.nextInt(raw.size - 8)
                assertEquals(plain.get(p), s.get(p), "get $p bs $bs")
                assertEquals(plain.getShort(p), s.getShort(p), "getShort $p bs $bs")
                assertEquals(plain.getInt(p), s.getInt(p), "getInt $p bs $bs")
                assertEquals(plain.getLong(p), s.getLong(p), "getLong $p bs $bs")
            }
            assertContentEquals(raw.copyOfRange(990, 2_345), s.bytes(990, 1_355))
            assertContentEquals(raw, s.bytes(0, raw.size))
        }
    }

    @Test
    fun `an uncompressed section reads in place`() {
        val s = assertNotNull(PoiBytes.of(ByteBuffer.wrap(raw)))
        assertTrue(s is MappedPoiBytes)
        assertEquals(plain.getLong(123), s.getLong(123))
    }

    @Test
    fun `a malformed or foreign block section is refused`() {
        val good = zsecDeflate(raw, 64)
        // Truncated: the last offset no longer names the end.
        assertNull(PoiBytes.of(ByteBuffer.wrap(good.copyOf(good.size - 1))))
        // A zstd-codec section (transit's) is not for this reader.
        assertNull(PoiBytes.of(ByteBuffer.wrap(zsecDeflate(raw, 64, codec = 0))))
        // Block count disagrees with raw_len / block_size.
        val bad = good.copyOf()
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 3)
        assertNull(PoiBytes.of(ByteBuffer.wrap(bad)))
    }

    @Test
    fun `reads outside the section throw instead of wrapping`() {
        val s = section(64)
        assertTrue(runCatching { s.getLong(raw.size - 4) }.exceptionOrNull() is IndexOutOfBoundsException)
        assertTrue(runCatching { s.get(-1) }.exceptionOrNull() is IndexOutOfBoundsException)
    }

    @Test
    fun `repeated reads inflate each block once`() {
        val s = section(1000) as PoiBlockSection
        repeat(50) { s.getInt(1_500) }
        assertEquals(1, s.inflated)
        s.getInt(998) // straddles blocks 0 and 1
        assertEquals(2, s.inflated)
    }
}
