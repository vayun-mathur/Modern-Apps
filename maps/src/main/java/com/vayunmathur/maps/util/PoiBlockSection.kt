package com.vayunmathur.maps.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Random-access bytes of one POI sidecar: the mapped section itself (planet4, and every
 * section planet5 leaves raw) or a planet5 block-compressed section ([PoiBlockSection]).
 * Positions are byte offsets into the section's *raw* layout, so the readers keep their
 * offsets and bounds checks unchanged; little-endian like every POI file.
 */
internal interface PoiBytes {
    /** The raw section length in bytes. */
    val size: Int

    fun get(pos: Int): Byte

    fun getShort(pos: Int): Short

    fun getInt(pos: Int): Int

    fun getLong(pos: Int): Long

    /** `len` bytes at `pos`, copied out. */
    fun bytes(pos: Int, len: Int): ByteArray

    companion object {
        /**
         * [buf] as [PoiBytes]: a block-compressed section (it starts with the `ZSEC` magic)
         * is opened through [PoiBlockSection], anything else is read in place. Null for a
         * block section whose table does not validate (the caller treats it as absent).
         */
        fun of(buf: ByteBuffer): PoiBytes? {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            return if (PoiBlockSection.isBlockSection(buf)) PoiBlockSection.open(buf) else MappedPoiBytes(buf)
        }
    }
}

/** An uncompressed section, read straight out of its mapping. */
internal class MappedPoiBytes(private val buf: ByteBuffer) : PoiBytes {
    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
    }

    override val size: Int get() = buf.capacity()

    override fun get(pos: Int): Byte = buf.get(pos)

    override fun getShort(pos: Int): Short = buf.getShort(pos)

    override fun getInt(pos: Int): Int = buf.getInt(pos)

    override fun getLong(pos: Int): Long = buf.getLong(pos)

    override fun bytes(pos: Int, len: Int): ByteArray {
        val out = ByteArray(len)
        // Absolute bulk read through a duplicate: the shared buffer's position never moves.
        val dup = buf.duplicate()
        dup.position(pos)
        dup.get(out, 0, len)
        return out
    }
}

/**
 * A planet5 block-compressed POI section (the attrs and region sidecars; the layout is
 * tilecodec `zsec.rs`, DEFLATE codec):
 *
 * ```
 * [magic "ZSEC"][block_size u32][raw_len u64][block_count u32][codec u32 = 1]
 * [offsets u64 x (block_count + 1)]   from the section start; the last is its end
 * [raw DEFLATE block]...
 * ```
 *
 * Each 64 KiB block of the raw file is an independent raw-DEFLATE stream, inflated with
 * `java.util.zip.Inflater` (`nowrap`) on first touch and kept in a small LRU, so a lookup
 * costs at most one block inflate (~0.1-0.3 ms on a phone) and repeated or neighbouring
 * lookups none. Nothing is ever inflated whole. Thread-safe: queries run on any thread.
 */
internal class PoiBlockSection private constructor(
    private val buf: ByteBuffer,
    override val size: Int,
    private val blockSize: Int,
    private val blockCount: Int,
) : PoiBytes {
    private val cache = object : LinkedHashMap<Int, ByteArray>(CACHE_BLOCKS * 2, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean =
            this.size > CACHE_BLOCKS
    }

    /** Blocks inflated so far (tests and the real-archive timing read it). */
    @Volatile
    internal var inflated: Int = 0
        private set

    private fun offset(i: Int): Long = buf.getLong(HEADER_BYTES + OFFSET_BYTES * i)

    private fun block(b: Int): ByteArray {
        if (b !in 0 until blockCount) throw IndexOutOfBoundsException("POI block $b of $blockCount")
        synchronized(cache) { cache[b]?.let { return it } }
        val start = offset(b)
        val end = offset(b + 1)
        val want = minOf(blockSize.toLong(), size.toLong() - b.toLong() * blockSize).toInt()
        val input = ByteArray((end - start).toInt())
        val dup = buf.duplicate()
        dup.position(start.toInt())
        dup.get(input)
        val out = ByteArray(want)
        val inflater = INFLATER.get()!!
        inflater.reset()
        inflater.setInput(input)
        var n = 0
        try {
            while (n < want) {
                val got = inflater.inflate(out, n, want - n)
                if (got == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                n += got
            }
        } catch (e: DataFormatException) {
            throw IllegalArgumentException("POI block $b will not inflate", e)
        }
        require(n == want) { "POI block $b inflated to $n of $want bytes" }
        inflated++
        synchronized(cache) { cache[b] = out }
        return out
    }

    override fun get(pos: Int): Byte {
        checkRange(pos, 1)
        return block(pos / blockSize)[pos % blockSize]
    }

    override fun getShort(pos: Int): Short = (littleEndian(pos, 2)).toInt().toShort()

    override fun getInt(pos: Int): Int = littleEndian(pos, 4).toInt()

    override fun getLong(pos: Int): Long = littleEndian(pos, 8)

    override fun bytes(pos: Int, len: Int): ByteArray {
        checkRange(pos, len)
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val p = pos + done
            val blk = block(p / blockSize)
            val off = p % blockSize
            val n = minOf(len - done, blk.size - off)
            System.arraycopy(blk, off, out, done, n)
            done += n
        }
        return out
    }

    /** `width` bytes at `pos` as a little-endian integer; one block unless it straddles two. */
    private fun littleEndian(pos: Int, width: Int): Long {
        checkRange(pos, width)
        val off = pos % blockSize
        val blk = block(pos / blockSize)
        val src: ByteArray
        val at: Int
        if (off + width <= blk.size) {
            src = blk
            at = off
        } else {
            src = bytes(pos, width)
            at = 0
        }
        var v = 0L
        for (i in width - 1 downTo 0) v = (v shl BYTE_BITS) or (src[at + i].toLong() and BYTE_MASK)
        return v
    }

    private fun checkRange(pos: Int, len: Int) {
        if (pos < 0 || len < 0 || pos > size - len) {
            throw IndexOutOfBoundsException("POI block section read $pos+$len of $size")
        }
    }

    internal companion object {
        private val MAGIC = byteArrayOf('Z'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte())
        const val HEADER_BYTES = 24
        const val OFFSET_BYTES = 8
        const val CODEC_DEFLATE = 1
        private const val BLOCK_SIZE_OFF = 4
        private const val RAW_LEN_OFF = 8
        private const val BLOCK_COUNT_OFF = 16
        private const val CODEC_OFF = 20
        /** 64 blocks x 64 KiB = 4 MiB per section, at most. */
        const val CACHE_BLOCKS = 64
        private const val LOAD_FACTOR = 0.75f
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xFFL
        private const val MAX_BLOCK = 1 shl 20

        private val INFLATER = ThreadLocal.withInitial { Inflater(true) }

        fun isBlockSection(buf: ByteBuffer): Boolean =
            buf.capacity() >= HEADER_BYTES && MAGIC.indices.all { buf.get(it) == MAGIC[it] }

        /** The section, or null when its header or offset table does not validate. */
        fun open(buf: ByteBuffer): PoiBlockSection? {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            if (!isBlockSection(buf) || buf.getInt(CODEC_OFF) != CODEC_DEFLATE) return null
            val blockSize = buf.getInt(BLOCK_SIZE_OFF)
            val rawLen = buf.getLong(RAW_LEN_OFF)
            val blockCount = buf.getInt(BLOCK_COUNT_OFF)
            if (blockSize !in 1..MAX_BLOCK || rawLen !in 0..Int.MAX_VALUE.toLong() || blockCount < 0) return null
            if (blockCount.toLong() != (rawLen + blockSize - 1) / blockSize) return null
            val tableEnd = HEADER_BYTES.toLong() + OFFSET_BYTES.toLong() * (blockCount + 1)
            if (tableEnd > buf.capacity()) return null
            var prev = tableEnd
            for (i in 0..blockCount) {
                val o = buf.getLong(HEADER_BYTES + OFFSET_BYTES * i)
                if (o < prev || o > buf.capacity()) return null
                prev = o
            }
            if (prev != buf.capacity().toLong()) return null
            return PoiBlockSection(buf, rawLen.toInt(), blockSize, blockCount)
        }
    }
}
