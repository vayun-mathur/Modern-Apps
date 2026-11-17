package com.vayunmathur.maps.util
import com.vayunmathur.maps.domain.spatialFromE7
import com.vayunmathur.maps.data.localityAt
import com.vayunmathur.maps.data.ADMIN_FILE
import com.vayunmathur.maps.data.KEY_CITY
import com.vayunmathur.maps.data.REGION_FILE
import com.vayunmathur.maps.data.PoiIndex

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C5 locality integration: a full synthetic side-file stack (index, names,
 * spatial grid, word index, region links, attribute blobs, admin table)
 * driving [PoiIndex.localityAt] through the hierarchy walk, the
 * boundary-city vote, and the own-address fallback.
 */
class MapsPoiLocalityC5Test {

    private var temp: File? = null

    @AfterTest
    fun tearDown() {
        temp?.deleteRecursively()
        temp = null
    }

    private data class Poi(
        val latE7: Int,
        val lonE7: Int,
        val type: Int,
        val name: String,
        val region: Long,
        val city: String?,
    )

    private val cellE7 = 200_000

    private fun tag(relation: Long): Long = (relation shl 2) or 3L

    private fun axis(value: Int, origin: Int): Int {
        val d = value.toLong() - origin.toLong()
        return if (d <= 0) 0 else (d / cellE7).toInt()
    }

    private fun words(name: String): List<String> =
        name.split(' ', '\t', '\n', '\r', '', '').filter { it.isNotEmpty() }

    // Boundary ids: a district inside a city inside a state inside a country,
    // plus a second boundary with no admin row at all (vote fallback).
    private val district = tag(100)
    private val city = tag(101)
    private val state = tag(102)
    private val country = tag(103)
    private val votable = tag(200)

    private fun load(pois: List<Poi>) {
        val dir = Files.createTempDirectory("poiloc5").toFile()
        temp = dir
        val sorted = pois.sortedWith { a, b ->
            java.lang.Long.compareUnsigned(
                spatialFromE7(a.latE7, a.lonE7),
                spatialFromE7(b.latE7, b.lonE7),
            )
        }
        val nameOff = writeNames(dir, sorted)
        writeIndex(dir, sorted, nameOff)
        writeWordIndex(dir, sorted)
        writeGrid(dir, sorted)
        writeRegion(dir, sorted)
        writeAttrs(dir, sorted)
        writeAdmin(dir)
        assertTrue(PoiIndex.reload(dir), "fixture did not map")
    }

    private fun writeNames(dir: File, sorted: List<Poi>): Map<String, Int> {
        val nameOff = LinkedHashMap<String, Int>()
        val out = ByteArrayOutputStream()
        for (p in sorted) {
            if (p.name !in nameOff) {
                nameOff[p.name] = out.size()
                out.write(p.name.toByteArray(Charsets.UTF_8))
                out.write(0)
            }
        }
        File(dir, PoiIndex.NAMES_FILE).writeBytes(out.toByteArray())
        return nameOff
    }

    private fun writeIndex(dir: File, sorted: List<Poi>, nameOff: Map<String, Int>) {
        val index = ByteBuffer.allocate(sorted.size * 14).order(ByteOrder.LITTLE_ENDIAN)
        for (p in sorted) {
            index.putInt(p.latE7)
            index.putInt(p.lonE7)
            index.putInt(nameOff.getValue(p.name))
            index.putShort(p.type.toShort())
        }
        File(dir, PoiIndex.INDEX_FILE).writeBytes(index.array())
    }

    private fun writeWordIndex(dir: File, sorted: List<Poi>) {
        val entries = ArrayList<Pair<Int, Int>>()
        sorted.forEachIndexed { ordinal, p ->
            words(p.name).indices.take(255).forEach { entries.add(ordinal to it) }
        }
        entries.sortWith(
            compareBy(
                { words(sorted[it.first].name)[it.second].lowercase() },
                { it.first },
                { it.second },
            ),
        )
        val buf = ByteBuffer.allocate(16 + 5 * entries.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("PNI1".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.putInt(sorted.size)
        buf.putInt(entries.size)
        entries.forEach { buf.putInt(it.first) }
        entries.forEach { buf.put(it.second.toByte()) }
        File(dir, PoiIndex.NAME_INDEX_FILE).writeBytes(buf.array())
    }

    private fun writeGrid(dir: File, sorted: List<Poi>) {
        val lat0 = sorted.minOfOrNull { it.latE7 } ?: 0
        val lon0 = sorted.minOfOrNull { it.lonE7 } ?: 0
        val cols = if (sorted.isEmpty()) 0 else axis(sorted.maxOf { it.lonE7 }, lon0) + 1
        val pairs = sorted.mapIndexed { ordinal, p ->
            axis(p.latE7, lat0) * cols + axis(p.lonE7, lon0).coerceAtMost(cols - 1) to ordinal
        }.sortedWith(compareBy({ it.first }, { it.second }))
        val cellIds = ArrayList<Int>()
        val cellOff = ArrayList<Int>()
        pairs.forEachIndexed { i, (cell, _) ->
            if (cellIds.lastOrNull() != cell) {
                cellIds.add(cell)
                cellOff.add(i)
            }
        }
        cellOff.add(pairs.size)
        val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        header.put("PSP1".toByteArray(Charsets.US_ASCII))
        header.putInt(1)
        header.putInt(sorted.size)
        header.putInt(cellIds.size)
        header.putInt(lat0)
        header.putInt(lon0)
        header.putInt(cellE7)
        header.putInt(cols)
        val cells = ByteBuffer.allocate(4 * cellIds.size).order(ByteOrder.LITTLE_ENDIAN)
        cellIds.forEach { cells.putInt(it) }
        val offs = ByteBuffer.allocate(4 * cellOff.size).order(ByteOrder.LITTLE_ENDIAN)
        cellOff.forEach { offs.putInt(it) }
        val ords = ByteBuffer.allocate(4 * pairs.size).order(ByteOrder.LITTLE_ENDIAN)
        pairs.forEach { ords.putInt(it.second) }
        File(dir, PoiIndex.SPATIAL_FILE)
            .writeBytes(header.array() + cells.array() + offs.array() + ords.array())
    }

    private fun writeRegion(dir: File, sorted: List<Poi>) {
        val buf = ByteBuffer.allocate(12 + 8 * sorted.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("PRG1".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.putInt(sorted.size)
        sorted.forEach { buf.putLong(it.region) }
        File(dir, REGION_FILE).writeBytes(buf.array())
    }

    private fun writeAttrs(dir: File, sorted: List<Poi>) {
        // Blob: per record with attrs, a u16 body length then
        // (u8 key, u16 len, value) fields; only KEY_CITY is written.
        val blob = ByteArrayOutputStream()
        val offsets = IntArray(sorted.size) { -1 }
        sorted.forEachIndexed { ordinal, p ->
            val cityName = p.city ?: return@forEachIndexed
            val value = cityName.toByteArray(Charsets.UTF_8)
            val body = ByteArrayOutputStream()
            body.write(KEY_CITY)
            body.write(value.size and 0xFF)
            body.write((value.size ushr 8) and 0xFF)
            body.write(value)
            val bytes = body.toByteArray()
            offsets[ordinal] = blob.size()
            blob.write(bytes.size and 0xFF)
            blob.write((bytes.size ushr 8) and 0xFF)
            blob.write(bytes)
        }
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.put("MAPA".toByteArray(Charsets.US_ASCII))
        header.put(1.toByte())
        header.put(ByteArray(3))
        header.putInt(sorted.size)
        val offs = ByteBuffer.allocate(4 * sorted.size).order(ByteOrder.LITTLE_ENDIAN)
        offsets.forEach { offs.putInt(it) }
        File(dir, PoiIndex.ATTRS_FILE)
            .writeBytes(header.array() + offs.array() + blob.toByteArray())
    }

    private data class AdminEntry(val tagged: Long, val parent: Long, val level: Int, val name: String, val en: String? = null)

    private fun writeAdmin(dir: File) {
        val entries = listOf(
            AdminEntry(district, city, 10, "C5 District"),
            AdminEntry(city, state, 8, "San Francisco", "San Francisco EN"),
            AdminEntry(state, country, 4, "California", null),
            AdminEntry(country, 0, 2, "United States", null),
        ).sortedBy { it.tagged }
        val pool = ByteArrayOutputStream()
        val offsets = HashMap<String, Int>()
        fun intern(s: String): Int = offsets.getOrPut(s) {
            pool.size().also { pool.write(s.toByteArray(Charsets.UTF_8)); pool.write(0) }
        }
        entries.forEach { intern(it.name); it.en?.let { e -> intern(e) } }
        val body = ByteBuffer.allocate(32 * entries.size).order(ByteOrder.LITTLE_ENDIAN)
        for (e in entries) {
            body.putLong(e.tagged)
            body.putLong(e.parent)
            body.putInt(intern(e.name))
            body.putInt(e.en?.let { intern(it) } ?: -1)
            body.put(e.level.toByte())
            body.put(ByteArray(7))
        }
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        header.put("PAD1".toByteArray(Charsets.US_ASCII))
        header.putInt(1)
        header.putInt(entries.size)
        header.putInt(pool.size())
        File(dir, ADMIN_FILE).writeBytes(header.array() + body.array() + pool.toByteArray())
    }

    private val sfLat = 377_749_000
    private val sfLon = -1_224_194_000

    @Test
    fun `locality walks district city state country and prefers english names`() {
        load(
            listOf(
                Poi(sfLat, sfLon, 1, "C5 Cafe Locality", district, null),
                Poi(sfLat + 100, sfLon + 100, 1, "C5 Other", district, null),
            ),
        )
        assertEquals(
            "San Francisco, California, United States",
            PoiIndex.localityAt(0, english = false),
        )
        assertEquals(
            "San Francisco EN, California, United States",
            PoiIndex.localityAt(1, english = true),
        )
        assertNull(PoiIndex.localityAt(99, english = false))
        assertNull(PoiIndex.localityAt(-1, english = false))
    }

    @Test
    fun `boundary without admin row falls back to the voted city`() {
        // Every POI of this boundary carries the same addr:city, but the
        // asked POI itself has none: the vote of its neighbours answers.
        load(
            listOf(
                Poi(sfLat, sfLon, 1, "C5 Voted One", votable, "Vote City"),
                Poi(sfLat + 50, sfLon + 50, 1, "C5 Voted Two", votable, "Vote City"),
                Poi(sfLat + 90, sfLon + 90, 1, "C5 Asked", votable, null),
            ),
        )
        assertEquals("Vote City", PoiIndex.localityAt(2, english = false))
    }

    @Test
    fun `own city attribute is used when there is no boundary at all`() {
        load(listOf(Poi(sfLat, sfLon, 1, "C5 Solo", 0L, "Solo City")))
        assertEquals("Solo City", PoiIndex.localityAt(0, english = false))
    }
}
