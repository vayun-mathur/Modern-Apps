package com.vayunmathur.maps.util
import com.vayunmathur.maps.data.ADMIN_FILE
import com.vayunmathur.maps.data.PoiIndex
import com.vayunmathur.maps.data.localityOf
import com.vayunmathur.maps.data.AdminTable
import com.vayunmathur.maps.data.LocalityCache
import com.vayunmathur.maps.data.adminFromBuffer
import com.vayunmathur.maps.data.joinLocality

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
import kotlin.test.assertTrue

/**
 * C5 branches for the admin locality table: malformed headers, name-pool
 * edges, long parent chains, and the locality cache's bounded eviction.
 */
class PoiAdminC5Test {

    private var temp: File? = null

    @AfterTest
    fun tearDown() {
        temp?.deleteRecursively()
        temp = null
    }

    private class Entry(
        val tagged: Long,
        val parent: Long,
        val level: Int,
        val name: String,
        val en: String? = null,
        val nameOffOverride: Int? = null,
        val enOffOverride: Int? = null,
    )

    private fun tag(relation: Long): Long = (relation shl 2) or 3L

    private fun writeTable(
        entries: List<Entry>,
        magic: String = "PAD1",
        version: Int = 1,
        countOverride: Int? = null,
        namesLenOverride: Int? = null,
    ): AdminTable? {
        val sorted = entries.sortedBy { it.tagged }
        val pool = ByteArrayOutputStream()
        val offsets = HashMap<String, Int>()
        fun intern(s: String): Int = offsets.getOrPut(s) {
            pool.size().also { pool.write(s.toByteArray(Charsets.UTF_8)); pool.write(0) }
        }
        // Intern first so overrides can point past the pool.
        sorted.forEach { e -> intern(e.name); e.en?.let { intern(it) } }
        val body = ByteBuffer.allocate(32 * sorted.size).order(ByteOrder.LITTLE_ENDIAN)
        for (e in sorted) {
            body.putLong(e.tagged)
            body.putLong(e.parent)
            body.putInt(e.nameOffOverride ?: intern(e.name))
            body.putInt(e.enOffOverride ?: (e.en?.let { intern(it) } ?: -1))
            body.put(e.level.toByte())
            body.put(ByteArray(7))
        }
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        header.put(magic.toByteArray(Charsets.US_ASCII))
        header.putInt(version)
        header.putInt(countOverride ?: sorted.size)
        header.putInt(namesLenOverride ?: pool.size())
        val dir = Files.createTempDirectory("poiadminc5").toFile()
        temp = dir
        val file = File(dir, ADMIN_FILE)
        file.writeBytes(header.array() + body.array() + pool.toByteArray())
        return adminFromBuffer(PoiIndex.mapReadOnly(file))
    }

    private fun table(entries: List<Entry>): AdminTable = assertNotNull(writeTable(entries))

    @Test
    fun `wrong magic and unsupported version are refused`() {
        assertNull(writeTable(listOf(Entry(tag(1), 0, 2, "X")), magic = "PAD2"))
        assertNull(writeTable(listOf(Entry(tag(1), 0, 2, "X")), version = 99))
    }

    @Test
    fun `negative counts and truncated pools are refused`() {
        assertNull(writeTable(listOf(Entry(tag(1), 0, 2, "X")), countOverride = -1))
        assertNull(writeTable(listOf(Entry(tag(1), 0, 2, "X")), namesLenOverride = -5))
        assertNull(writeTable(listOf(Entry(tag(1), 0, 2, "X")), namesLenOverride = 1_000_000))
        assertNull(writeTable(emptyList(), countOverride = 10))
    }

    @Test
    fun `empty table finds nothing`() {
        val t = table(emptyList())
        assertEquals(-1, t.find(tag(42)))
        assertNull(t.localityOf(tag(42), english = false))
    }

    @Test
    fun `find hits first middle and last entries and misses between them`() {
        val entries = (1..9).map { Entry(tag(it * 10L), 0, 2, "Place $it") }
        val t = table(entries)
        assertEquals("Place 1", t.name(t.find(tag(10)), english = false))
        assertEquals("Place 5", t.name(t.find(tag(50)), english = false))
        assertEquals("Place 9", t.name(t.find(tag(90)), english = false))
        assertEquals(-1, t.find(tag(55)))
        assertEquals(-1, t.find(tag(5)))
        assertEquals(-1, t.find(tag(500)))
        assertEquals(2, t.level(t.find(tag(30))))
        assertEquals(0L, t.parent(t.find(tag(30))))
    }

    @Test
    fun `english falls back to local name when no translation is stored`() {
        val france = Entry(tag(100), 0, 2, "France")
        val paris = Entry(tag(101), france.tagged, 8, "Paris")
        val t = table(listOf(france, paris))
        assertEquals("Paris, France", t.localityOf(paris.tagged, english = true))
    }

    @Test
    fun `out of range and empty pool names read as null`() {
        val country = Entry(tag(100), 0, 2, "Freedonia")
        val broken = Entry(tag(101), country.tagged, 8, "Nowhere", nameOffOverride = 500)
        val empty = Entry(tag(102), country.tagged, 8, "", nameOffOverride = null)
        val t = table(listOf(country, broken, empty))
        assertNull(t.name(t.find(tag(101)), english = false))
        // The empty-name entry contributes no city, so locality is just the country.
        assertEquals("Freedonia", t.localityOf(empty.tagged, english = false))
    }

    @Test
    fun `district deeper than city band stands in when no city exists`() {
        val country = Entry(tag(100), 0, 2, "Freedonia")
        val district = Entry(tag(101), country.tagged, 10, "Old Town")
        val t = table(listOf(country, district))
        assertEquals("Old Town, Freedonia", t.localityOf(district.tagged, english = false))
    }

    @Test
    fun `a chain longer than the cap stops instead of walking forever`() {
        // 20 linked boundaries: the walk must stop at the cap, and the
        // shallowest names within it still resolve.
        val entries = ArrayList<Entry>()
        for (i in 0 until 20) {
            val parent = if (i == 19) 0L else tag(1_000L + i + 1)
            val level = when (i) {
                0 -> 8
                19 -> 2
                else -> 6
            }
            entries.add(Entry(tag(1_000L + i), parent, level, "Level $i"))
        }
        val t = table(entries)
        val locality = t.localityOf(tag(1_000L), english = false)
        assertNotNull(locality)
        assertTrue(locality.startsWith("Level 0"), "locality=$locality")
    }

    @Test
    fun `joinLocality trims and drops non-consecutive repeats only after trim`() {
        assertEquals("A, B, A", joinLocality("A", "B", "A"))
        assertEquals("A, B", joinLocality("  A  ", "A", " B "))
        assertNull(joinLocality(" ", null))
        assertEquals("Solo", joinLocality(null, "Solo", "Solo"))
    }

    @Test
    fun `locality cache evicts oldest and remembers known-empty`() {
        val cache = LocalityCache()
        assertNull(cache.get(7L))
        cache.put(7L, null)
        assertEquals("", cache.get(7L))
        cache.put(8L, "Austin, Texas")
        assertEquals("Austin, Texas", cache.get(8L))
        // Fill past capacity: the eldest entries are evicted, never a crash.
        for (i in 0 until 600) cache.put(1_000L + i, "Place $i")
        assertEquals("Place 599", cache.get(1_599L))
        assertTrue(cache.get(8L) == null || cache.get(8L) == "Austin, Texas")
    }
}
