package com.vayunmathur.maps.util

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [PoiArchive] against the side files it replaces.
 *
 * The oracle is the multi-file path itself: the same payload bytes answer the
 * same query through [PoiIndex.reload] and through [PoiIndex.reloadArchive],
 * so a divergence is a container bug rather than a judgement call. Payloads
 * are built by hand (two records, no optional sections), which is also what
 * keeps this test independent of the Rust writer: if the two drift, the
 * section validators fail here first.
 */
class PoiArchiveTest {

    private var temp: File? = null

    @AfterTest
    fun tearDown() {
        temp?.deleteRecursively()
        temp = null
    }

    private val buildId = 0x0123_4567_89AB_CDEFL

    /** Two-name pool: Cafe@0, Mall@5. */
    private fun namesBytes(): ByteArray = "Cafe\u0000Mall\u0000".toByteArray(Charsets.UTF_8)

    /** Whole archive file: tile prefix + payloads 8-aligned in kind order + dir + footer. */
    private fun archive(payloads: List<Triple<Int, ByteArray, Long>>): ByteArray {
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
        footer.putLong(buildId)
        out.write(footer.array())
        // Tile header over the prefix: magic + version + build_id@16 + file_len@24.
        val bytes = out.toByteArray()
        val head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        head.put("MAMAPS".toByteArray(Charsets.US_ASCII))
        head.put(0.toByte())
        head.put(7.toByte())
        head.putShort(128.toShort())
        head.putShort(0.toShort())
        head.put(0.toByte()) // compression = none
        head.put(12.toByte()) // layer_count
        head.put(0.toByte()) // min_zoom
        head.put(14.toByte()) // max_zoom
        head.putLong(buildId)
        head.putLong(bytes.size.toLong())
        return bytes
    }

    private fun writeArchive(payloads: List<Triple<Int, ByteArray, Long>>): File {
        val dir = Files.createTempDirectory("poiarchive").toFile()
        temp = dir
        val f = File(dir, "test.mamaps")
        f.writeBytes(archive(payloads))
        return f
    }

    private fun indexPayload(): Triple<Int, ByteArray, Long> {
        // Fix the name offsets: Cafe@0, Mall@5.
        val buf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(377_749_050)
        buf.putInt(-1_224_194_000)
        buf.putInt(0)
        buf.putShort(2.toShort())
        buf.putInt(377_749_000)
        buf.putInt(-1_224_194_000)
        buf.putInt(5)
        buf.putShort(1.toShort())
        return Triple(PoiArchive.KIND_INDEX, buf.array(), 0L)
    }

    @Test
    fun `archive answers the same nearest query as side files`() {
        val f = writeArchive(
            listOf(
                indexPayload(),
                Triple(PoiArchive.KIND_NAMES, namesBytes(), 0L),
            ),
        )
        assertTrue(PoiIndex.reloadArchive(f), "archive did not map")
        assertEquals(2, PoiIndex.recordCount)
        val hits = PoiIndex.nearest(37.7749050, -122.4194000)
        assertEquals(2, hits.size)
        assertEquals("Cafe", hits[0].name)
        assertEquals("Mall", hits[1].name)
        assertEquals(0, hits[0].ordinal)
        assertEquals(1, hits[1].ordinal)
    }

    @Test
    fun `archive without optional sections still answers by scan and walk`() {
        val f = writeArchive(
            listOf(
                indexPayload(),
                Triple(PoiArchive.KIND_NAMES, namesBytes(), 0L),
            ),
        )
        assertTrue(PoiIndex.reloadArchive(f), "archive did not map")
        assertFalse(PoiIndex.attributesAvailable, "no attrs section, no attributes")
        val found = PoiIndex.searchByName("mall", 37.7749, -122.4194)
        assertEquals(listOf("Mall"), found.map { it.name })
        val box = PoiIndex.inViewport(-122.43, 37.77, -122.41, 37.78)
        assertEquals(2, box.size)
    }

    /**
     * Kinds 15 + 16 together: both records link to Paris (relation 71525), and the admin
     * table walks Paris up to France, so the archive alone yields the row's locality line.
     */
    @Test
    fun `region and admin sections give a locality line`() {
        val paris = (71_525L shl 2) or 3L
        val france = (2_202_162L shl 2) or 3L
        val region = ByteBuffer.allocate(12 + 16).order(ByteOrder.LITTLE_ENDIAN)
        region.put("PRG1".toByteArray(Charsets.US_ASCII)).putInt(1).putInt(2).putLong(paris).putLong(paris)
        val pool = "Paris\u0000France\u0000".toByteArray(Charsets.UTF_8)
        val admin = ByteBuffer.allocate(16 + 64 + pool.size).order(ByteOrder.LITTLE_ENDIAN)
        admin.put("PAD1".toByteArray(Charsets.US_ASCII)).putInt(1).putInt(2).putInt(pool.size)
        // Ascending tagged ids: Paris (smaller id) first.
        admin.putLong(paris).putLong(france).putInt(0).putInt(-1).put(8.toByte()).put(ByteArray(7))
        admin.putLong(france).putLong(0L).putInt(6).putInt(-1).put(2.toByte()).put(ByteArray(7))
        admin.put(pool)
        val f = writeArchive(
            listOf(
                indexPayload(),
                Triple(PoiArchive.KIND_NAMES, namesBytes(), 0L),
                Triple(PoiArchive.KIND_REGION, region.array(), 0L),
                Triple(PoiArchive.KIND_ADMIN, admin.array(), 0L),
            ),
        )
        assertTrue(PoiIndex.reloadArchive(f), "archive did not map")
        assertEquals("Paris, France", PoiIndex.localityAt(0, english = true))
        assertEquals("Paris, France", PoiIndex.localityAt(1, english = false))
    }

    @Test
    fun `bad magic is refused`() {
        val bytes = archive(
            listOf(
                indexPayload(),
                Triple(PoiArchive.KIND_NAMES, namesBytes(), 0L),
            ),
        )
        bytes[bytes.size - 32] = 'X'.code.toByte()
        val dir = Files.createTempDirectory("poiarchive").toFile()
        temp = dir
        val f = File(dir, "test.mamaps")
        f.writeBytes(bytes)
        assertFalse(PoiIndex.reloadArchive(f), "bad magic must refuse")
    }

    @Test
    fun `build id mismatch is refused`() {
        val bytes = archive(
            listOf(
                indexPayload(),
                Triple(PoiArchive.KIND_NAMES, namesBytes(), 0L),
            ),
        )
        // Flip a byte of the footer's build id (last 8 bytes).
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        val dir = Files.createTempDirectory("poiarchive").toFile()
        temp = dir
        val f = File(dir, "test.mamaps")
        f.writeBytes(bytes)
        assertFalse(PoiIndex.reloadArchive(f), "build id mismatch must refuse")
    }

    /**
     * A paired index+names override replaces the base pair: the queries
     * answer from the override's records, validated by the same validators.
     */
    @Test
    fun `paired override replaces the base pair`() {
        val f = writeArchive(
            listOf(
                indexPayload(),
                Triple(PoiArchive.KIND_NAMES, namesBytes(), 0L),
            ),
        )
        // Override dir beside the archive: overlays/sections/<kind>.section.
        val sections = File(File(f.parentFile, "overlays"), "sections")
        sections.mkdirs()
        // One-record override: Diner@0.
        val index = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        index.putInt(377_749_050)
        index.putInt(-1_224_194_000)
        index.putInt(0)
        index.putShort(2.toShort())
        File(sections, "${PoiArchive.KIND_INDEX}.section").writeBytes(index.array())
        File(sections, "${PoiArchive.KIND_NAMES}.section")
            .writeBytes("Diner\u0000".toByteArray(Charsets.UTF_8))
        assertTrue(PoiIndex.reloadArchive(f), "paired override must map")
        assertEquals(1, PoiIndex.recordCount)
        val hits = PoiIndex.nearest(37.7749050, -122.4194000)
        assertEquals(1, hits.size)
        assertEquals("Diner", hits[0].name)
    }

    /**
     * A half-paired override (index without names) falls back to the base
     * pair: mixed vintages must never join.
     */
    @Test
    fun `half paired override falls back to base`() {
        val f = writeArchive(
            listOf(
                indexPayload(),
                Triple(PoiArchive.KIND_NAMES, namesBytes(), 0L),
            ),
        )
        val sections = File(File(f.parentFile, "overlays"), "sections")
        sections.mkdirs()
        val index = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        index.putInt(377_749_050)
        index.putInt(-1_224_194_000)
        index.putInt(0)
        index.putShort(2.toShort())
        File(sections, "${PoiArchive.KIND_INDEX}.section").writeBytes(index.array())
        assertTrue(PoiIndex.reloadArchive(f), "half pair must still map (from base)")
        assertEquals(2, PoiIndex.recordCount)
        val hits = PoiIndex.nearest(37.7749050, -122.4194000)
        assertEquals("Cafe", hits[0].name)
    }
}
