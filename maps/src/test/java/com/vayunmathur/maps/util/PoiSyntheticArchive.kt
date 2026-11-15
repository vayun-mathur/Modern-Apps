package com.vayunmathur.maps.util

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * A small synthetic `.mamaps` archive holding a handful of POI records with
 * every POI section the app reads (index, names, attrs, spatial grid, word
 * index, region links and the admin hierarchy), built in the exact on-disk
 * formats the production readers validate.
 *
 * This is the hermetic stand-in for the 50-80 GB planet archive: the reader
 * paths under test ([PoiIndex.reloadArchive] -> [PoiArchive], nearest /
 * viewport / name search / attributes / locality) are the production ones,
 * only the input size differs. Real-archive tests keep their env-gated
 * planet assertions and fall back to this fixture by default, so CI runs
 * the same assertions instead of skipping them.
 */
internal object PoiSyntheticArchive {

    data class Record(val name: String, val lat: Double, val lon: Double, val type: Int)

    /** The boundary every fixture record links to (relation 71525, "Paris"). */
    val PARIS_TAGGED: Long = (71_525L shl 2) or 3L
    private val FRANCE_TAGGED: Long = (2_202_162L shl 2) or 3L

    val RECORDS: List<Record> = listOf(
        Record("Golden Gate Bridge", 37.8199, -122.4783, 20),
        Record("Tartine Bakery", 37.7614, -122.4241, 0),
        Record("Golden Gate Park", 37.7694, -122.4862, 25),
        Record("Oakland", 37.8044, -122.2712, 51),
        Record("Cafe", 37.7749, -122.4194, 1),
        Record("Mall", 37.7750, -122.4194, 10),
    )

    const val BUILD_ID: Long = 0x0123_4567_89AB_CDEFL

    /** Write the fixture archive into a fresh temp dir and return the file. */
    fun write(dirName: String = "poisynth", blockCompressed: Boolean = false): File {
        val dir = Files.createTempDirectory(dirName).toFile()
        val f = File(dir, "test.mamaps")
        f.writeBytes(bytes(blockCompressed))
        return f
    }

    fun bytes(blockCompressed: Boolean = false): ByteArray {
        val payloads = listOf(
            Triple(PoiArchive.KIND_INDEX, indexBytes(), 0L),
            Triple(PoiArchive.KIND_NAMES, namesBytes(), 0L),
            Triple(PoiArchive.KIND_ATTRS, maybeCompress(attrsBytes(), blockCompressed), 0L),
            Triple(PoiArchive.KIND_SPATIAL, spatialBytes(), 0L),
            Triple(PoiArchive.KIND_WORDS, wordsBytes(), 0L),
            Triple(PoiArchive.KIND_REGION, maybeCompress(regionBytes(), blockCompressed), 0L),
            Triple(PoiArchive.KIND_ADMIN, adminBytes(), 0L),
        )
        return container(payloads)
    }

    private fun maybeCompress(raw: ByteArray, compress: Boolean): ByteArray =
        if (compress) zsecDeflate(raw, 5) else raw

    /** Name pool in record order. */
    fun namesBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (r in RECORDS) out.write(r.name.toByteArray(Charsets.UTF_8) + byteArrayOf(0))
        return out.toByteArray()
    }

    private fun nameOffsets(): List<Int> {
        val out = ArrayList<Int>(RECORDS.size)
        var off = 0
        for (r in RECORDS) {
            out += off
            off += r.name.toByteArray(Charsets.UTF_8).size + 1
        }
        return out
    }

    private fun indexBytes(): ByteArray {
        val offs = nameOffsets()
        val buf = ByteBuffer.allocate(RECORDS.size * 14).order(ByteOrder.LITTLE_ENDIAN)
        RECORDS.forEachIndexed { i, r ->
            buf.putInt((r.lat * 1e7).toInt())
            buf.putInt((r.lon * 1e7).toInt())
            buf.putInt(offs[i])
            buf.putShort(r.type.toShort())
        }
        return buf.array()
    }

    /** Every record carries rating + review count + summary (plus phone/city). */
    private fun attrsBytes(): ByteArray {
        val bodies = RECORDS.mapIndexed { i, r ->
            val fields = java.io.ByteArrayOutputStream()
            fun field(key: Int, value: String) {
                val v = value.toByteArray(Charsets.UTF_8)
                fields.write(key)
                fields.write((v.size and 0xFF))
                fields.write((v.size shr 8 and 0xFF))
                fields.write(v)
            }
            field(KEY_RATING, "4.${5 + i % 5}")
            field(KEY_REVIEW_COUNT, "${120 + i * 37}")
            field(KEY_REVIEW_SUMMARY, "Loved by locals near ${r.name}")
            field(KEY_PHONE, "+1-555-010$i")
            field(KEY_CITY, "San Francisco")
            val body = fields.toByteArray()
            ByteBuffer.allocate(2 + body.size).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(body.size.toShort()).put(body).array()
        }
        val blobStart = 12 + 4 * RECORDS.size
        var off = 0
        val buf = ByteBuffer.allocate(blobStart + bodies.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("MAPA".toByteArray(Charsets.US_ASCII)).putInt(1).putInt(RECORDS.size)
        for (b in bodies) {
            buf.putInt(off)
            off += b.size
        }
        for (b in bodies) buf.put(b)
        return buf.array()
    }

    /** One tagged boundary id per record. */
    private fun regionBytes(): ByteArray {
        val buf = ByteBuffer.allocate(12 + 8 * RECORDS.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("PRG1".toByteArray(Charsets.US_ASCII)).putInt(1).putInt(RECORDS.size)
        repeat(RECORDS.size) { buf.putLong(PARIS_TAGGED) }
        return buf.array()
    }

    /** Paris (level 8) under France (level 2), as in PoiArchiveTest. */
    private fun adminBytes(): ByteArray {
        val pool = "Paris\u0000France\u0000".toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(16 + 64 + pool.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("PAD1".toByteArray(Charsets.US_ASCII)).putInt(1).putInt(2).putInt(pool.size)
        buf.putLong(PARIS_TAGGED).putLong(FRANCE_TAGGED).putInt(0).putInt(-1).put(8.toByte()).put(ByteArray(7))
        buf.putLong(FRANCE_TAGGED).putLong(0L).putInt(6).putInt(-1).put(2.toByte()).put(ByteArray(7))
        buf.put(pool)
        return buf.array()
    }

    /** CSR grid: 0.1-degree cells over the fixture's bay-area extent. */
    private fun spatialBytes(): ByteArray {
        val lat0 = 37_6500_000
        val lon0 = -122_5500_000
        val cell = 1_000_000
        val cols = 10
        fun cellId(r: Record): Int {
            val row = ((r.lat * 1e7).toInt() - lat0) / cell
            val col = ((r.lon * 1e7).toInt() - lon0) / cell
            return row * cols + col
        }
        val byCell = RECORDS.indices.groupBy { cellId(RECORDS[it]) }.toSortedMap()
        val cellIds = byCell.keys.toIntArray()
        val offsets = IntArray(cellIds.size + 1)
        val ordinals = ArrayList<Int>()
        cellIds.forEachIndexed { ci, id ->
            offsets[ci] = ordinals.size
            ordinals += byCell.getValue(id).sorted()
        }
        offsets[cellIds.size] = ordinals.size
        val buf = ByteBuffer.allocate(32 + 4 * (cellIds.size + cellIds.size + 1 + ordinals.size))
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put("PSP1".toByteArray(Charsets.US_ASCII)).putInt(1).putInt(RECORDS.size)
        buf.putInt(cellIds.size).putInt(lat0).putInt(lon0).putInt(cell).putInt(cols)
        cellIds.forEach { buf.putInt(it) }
        offsets.forEach { buf.putInt(it) }
        ordinals.forEach { buf.putInt(it) }
        return buf.array()
    }

    /** Word index: one entry per whitespace-separated word, sorted by word. */
    private fun wordsBytes(): ByteArray {
        data class Entry(val word: String, val ordinal: Int, val wordIdx: Int)
        val entries = ArrayList<Entry>()
        RECORDS.forEachIndexed { ord, r ->
            r.name.split(" ").forEachIndexed { wi, w -> entries += Entry(w.lowercase(), ord, wi) }
        }
        entries.sortWith(compareBy({ it.word }, { it.ordinal }, { it.wordIdx }))
        val buf = ByteBuffer.allocate(16 + 4 * entries.size + entries.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("PNI1".toByteArray(Charsets.US_ASCII)).putInt(1).putInt(RECORDS.size).putInt(entries.size)
        entries.forEach { buf.putInt(it.ordinal) }
        entries.forEach { buf.put(it.wordIdx.toByte()) }
        return buf.array()
    }

    /** Tile prefix + 8-aligned payloads in kind order + directory + MAMA8 footer. */
    private fun container(payloads: List<Triple<Int, ByteArray, Long>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(ByteArray(4096))
        val entries = ArrayList<ByteArray>()
        for ((kind, bytes, extra) in payloads) {
            while (out.size() % 8 != 0) out.write(0)
            val offset = out.size().toLong()
            out.write(bytes)
            val e = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            e.put(kind.toByte())
            e.put(byteArrayOf(0, 0, 0))
            e.putInt(0)
            e.putLong(offset)
            e.putLong(bytes.size.toLong())
            e.putLong(extra)
            entries.add(e.array())
        }
        while (out.size() % 8 != 0) out.write(0)
        val dirOffset = out.size().toLong()
        val dir = ByteBuffer.allocate(4 + entries.size * 32 + 7).order(ByteOrder.LITTLE_ENDIAN)
        dir.putInt(entries.size)
        for (e in entries) dir.put(e)
        while (dir.position() % 8 != 0) dir.put(0.toByte())
        out.write(dir.array(), 0, dir.position())
        val footer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        footer.put("MAMA8".toByteArray(Charsets.US_ASCII))
        footer.put(byteArrayOf(0, 0, 0))
        footer.putLong(dirOffset)
        footer.putLong(dir.position().toLong())
        footer.putLong(BUILD_ID)
        out.write(footer.array())
        val bytes = out.toByteArray()
        val head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        head.put("MAMAPS".toByteArray(Charsets.US_ASCII))
        head.put(0.toByte())
        head.put(7.toByte())
        head.putShort(128.toShort())
        head.putShort(0.toShort())
        head.put(0.toByte())
        head.put(12.toByte())
        head.put(0.toByte())
        head.put(14.toByte())
        head.putLong(BUILD_ID)
        head.putLong(bytes.size.toLong())
        return bytes
    }
}
