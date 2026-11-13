package com.vayunmathur.maps.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The locality line ("City, State, Country") read from `poi_admin.bin` — the layout
 * `osm_ingest/src/poi_admin.rs` writes and pins from its side.
 */
class PoiIndexAdminTest {

    private var temp: File? = null

    @AfterTest
    fun tearDown() {
        temp?.deleteRecursively()
        temp = null
    }

    private class Entry(val tagged: Long, val parent: Long, val level: Int, val name: String, val en: String? = null)

    private fun tag(relation: Long): Long = (relation shl 2) or 3L

    /** Serialise [entries] exactly as `write_admin` does (sorted, 32-byte entries, NUL pool). */
    private fun table(entries: List<Entry>): AdminTable {
        val sorted = entries.sortedBy { it.tagged }
        val pool = ByteArrayOutputStream()
        val offsets = HashMap<String, Int>()
        fun intern(s: String): Int = offsets.getOrPut(s) {
            pool.size().also { pool.write(s.toByteArray(Charsets.UTF_8)); pool.write(0) }
        }
        val body = ByteBuffer.allocate(32 * sorted.size).order(ByteOrder.LITTLE_ENDIAN)
        for (e in sorted) {
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
        header.putInt(sorted.size)
        header.putInt(pool.size())
        val dir = Files.createTempDirectory("poiadmin").toFile()
        temp = dir
        val file = File(dir, ADMIN_FILE)
        file.writeBytes(header.array() + body.array() + pool.toByteArray())
        return assertNotNull(adminFromBuffer(PoiIndex.mapReadOnly(file)))
    }

    private val us = Entry(tag(148_838), 0, 2, "United States")
    private val texas = Entry(tag(114_690), us.tagged, 4, "Texas")
    private val travis = Entry(tag(1_837_698), texas.tagged, 6, "Travis County")
    private val austin = Entry(tag(113_314), travis.tagged, 8, "Austin")
    private val downtown = Entry(tag(9_000_001), austin.tagged, 10, "Downtown")
    private val france = Entry(tag(2_202_162), 0, 2, "France")
    private val metropole = Entry(tag(1_403_916), france.tagged, 3, "France métropolitaine")
    private val paris = Entry(tag(71_525), metropole.tagged, 8, "Paris")
    private val koeln = Entry(tag(62_578), tag(51_477), 6, "Köln", en = "Cologne")
    private val germany = Entry(tag(51_477), 0, 2, "Deutschland", en = "Germany")

    @Test
    fun `a POI walks its boundary up to city, state and country`() {
        val t = table(listOf(us, texas, travis, austin, downtown))
        // The district below the city and the county above it are skipped.
        assertEquals("Austin, Texas, United States", t.localityOf(downtown.tagged, english = false))
        assertEquals("Austin, Texas, United States", t.localityOf(austin.tagged, english = false))
    }

    @Test
    fun `state appears only where the country has one`() {
        val t = table(listOf(france, metropole, paris))
        // France has no level-4 boundary in this table: the level-3 region stands in for it.
        assertEquals("Paris, France métropolitaine, France", t.localityOf(paris.tagged, english = false))
        val noState = table(listOf(france, Entry(paris.tagged, france.tagged, 8, "Paris")))
        assertEquals("Paris, France", noState.localityOf(paris.tagged, english = false))
    }

    @Test
    fun `english names are preferred when asked for and present`() {
        val t = table(listOf(germany, koeln))
        assertEquals("Köln, Deutschland", t.localityOf(koeln.tagged, english = false))
        assertEquals("Cologne, Germany", t.localityOf(koeln.tagged, english = true))
    }

    @Test
    fun `an unknown boundary has no locality and a cycle does not spin`() {
        val t = table(listOf(Entry(tag(1), tag(2), 8, "A"), Entry(tag(2), tag(1), 8, "B")))
        assertNull(t.localityOf(tag(99), english = false))
        assertEquals("A", t.localityOf(tag(1), english = false))
    }

    @Test
    fun `joinLocality drops blanks and consecutive repeats`() {
        assertEquals("Singapore", joinLocality("Singapore", null, "Singapore"))
        assertEquals("Monaco, France", joinLocality("Monaco", " ", "France"))
        assertNull(joinLocality(null, "", null))
    }

    @Test
    fun `a malformed table is refused`() {
        val dir = Files.createTempDirectory("poiadminbad").toFile()
        temp = dir
        val file = File(dir, ADMIN_FILE)
        file.writeBytes("PAD1".toByteArray() + ByteArray(12) { if (it == 0) 1 else if (it == 4) 5 else 0 }) // v1, count 5, no entries
        assertNull(adminFromBuffer(PoiIndex.mapReadOnly(file)))
    }
}
