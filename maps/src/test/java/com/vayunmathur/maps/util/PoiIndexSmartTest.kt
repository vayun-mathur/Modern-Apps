package com.vayunmathur.maps.util

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins intelligent search ranking in [PoiIndex]: multi-word queries, typo
 * tolerance, and recency/favorites weighting.
 *
 * Baseline behaviour ranked raw substring/prefix matches by distance only,
 * so a two-word query matched nothing on the indexed path (a word can never
 * start with a string containing a space), a one-letter typo matched nothing
 * on either path, and a place the user searches or saves weekly lost to a
 * textually identical POI a metre closer.
 */
class PoiIndexSmartTest {

    private var temp: File? = null

    @AfterTest
    fun tearDown() {
        temp?.deleteRecursively()
        temp = null
    }

    private class Poi(val latE7: Int, val lonE7: Int, val type: Int, val name: String)

    private val cellE7 = 200_000

    private fun axis(value: Int, origin: Int): Int {
        val d = value.toLong() - origin.toLong()
        return if (d <= 0) 0 else (d / cellE7).toInt()
    }

    private fun words(name: String): List<String> =
        name.split(' ', '\t', '\n', '\r', '', '').filter { it.isNotEmpty() }

    /** Minimal side files: names pool + records + word index + spatial grid. */
    private fun load(pois: List<Poi>) {
        val dir = Files.createTempDirectory("poismart").toFile()
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
        assertTrue(PoiIndex.reload(dir), "fixture did not map")
    }

    private fun writeNames(dir: File, sorted: List<Poi>): Map<String, Int> {
        val nameOff = LinkedHashMap<String, Int>()
        val namesOut = java.io.ByteArrayOutputStream()
        for (p in sorted) {
            if (p.name !in nameOff) {
                nameOff[p.name] = namesOut.size()
                namesOut.write(p.name.toByteArray(Charsets.UTF_8))
                namesOut.write(0)
            }
        }
        File(dir, PoiIndex.NAMES_FILE).writeBytes(namesOut.toByteArray())

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
            words(p.name).indices.take(255).forEach { wordIdx -> entries.add(ordinal to wordIdx) }
        }
        entries.sortWith(
            compareBy(
                { words(sorted[it.first].name)[it.second].lowercase() },
                { it.first },
                { it.second },
            )
        )
        val nameIndex = ByteBuffer.allocate(16 + 5 * entries.size).order(ByteOrder.LITTLE_ENDIAN)
        nameIndex.put("PNI1".toByteArray(Charsets.US_ASCII))
        nameIndex.putInt(1)
        nameIndex.putInt(sorted.size)
        nameIndex.putInt(entries.size)
        entries.forEach { nameIndex.putInt(it.first) }
        entries.forEach { nameIndex.put(it.second.toByte()) }
        File(dir, PoiIndex.NAME_INDEX_FILE).writeBytes(nameIndex.array())
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
        val grid = ByteBuffer
            .allocate(32 + 4 * cellIds.size + 4 * cellOff.size + 4 * pairs.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        grid.put("PSP1".toByteArray(Charsets.US_ASCII))
        grid.putInt(1)
        grid.putInt(sorted.size)
        grid.putInt(cellIds.size)
        grid.putInt(lat0)
        grid.putInt(lon0)
        grid.putInt(cellE7)
        grid.putInt(cols)
        cellIds.forEach { grid.putInt(it) }
        cellOff.forEach { grid.putInt(it) }
        pairs.forEach { grid.putInt(it.second) }
        File(dir, PoiIndex.SPATIAL_FILE).writeBytes(grid.array())
    }

    @Test
    fun `a two-word query matches the phrase`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Blue Bottle Coffee"),
                Poi(377_749_100, -1_224_194_100, 2, "Red Door Espresso"),
            )
        )
        val got = PoiIndex.searchByName("blue bottle", 37.7749, -122.4194).map { it.name }
        assertEquals(listOf("Blue Bottle Coffee"), got)
    }

    @Test
    fun `a one-letter typo still finds the word`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Pizza Hut"),
                Poi(377_749_100, -1_224_194_100, 2, "Sushi Place"),
            )
        )
        val got = PoiIndex.searchByName("piza", 37.7749, -122.4194).map { it.name }
        assertEquals(listOf("Pizza Hut"), got)
    }

    @Test
    fun `a boosted name outranks a closer unboosted one`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Cafe Alpha"),
                Poi(377_760_000, -1_224_194_000, 2, "Cafe Beta"),
            )
        )
        val nearLat = 37.7749
        val nearLon = -122.4194
        assertEquals(
            listOf("Cafe Alpha", "Cafe Beta"),
            PoiIndex.searchByName("cafe", nearLat, nearLon).map { it.name },
        )
        assertEquals(
            listOf("Cafe Beta", "Cafe Alpha"),
            PoiIndex.searchByName("cafe", nearLat, nearLon, boost = mapOf("cafe beta" to 10.0))
                .map { it.name },
        )
    }

    @Test
    fun `a more recent query outranks a less recent one`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Cafe Alpha"),
                Poi(377_749_100, -1_224_194_100, 2, "Cafe Beta"),
            )
        )
        val got = PoiIndex.searchByName(
            "cafe",
            37.7749,
            -122.4194,
            boost = mapOf("cafe alpha" to 1.0, "cafe beta" to 2.0),
        ).map { it.name }
        assertEquals(listOf("Cafe Beta", "Cafe Alpha"), got)
    }

    @Test
    fun `a boosted typo still loses to an unboosted exact match`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Pizza Hut"),
                Poi(377_749_100, -1_224_194_100, 2, "Piza Kitchen"),
            )
        )
        // Boost reorders, but the weighting is deliberately smaller than a rank
        // step: exactness still beats familiarity.
        val got = PoiIndex.searchByName(
            "pizza",
            37.7749,
            -122.4194,
            boost = mapOf("piza kitchen" to 0.5),
        ).map { it.name }
        assertEquals(listOf("Pizza Hut", "Piza Kitchen"), got)
    }

    @Test
    fun `buildSearchBoost favors saved places over recents`() {
        val boost = buildSearchBoost(
            recentQueries = listOf("Cafe Beta", "Cafe Alpha"),
            savedNames = listOf("Cafe Alpha"),
        )
        // Saved outranks everything; recents decay most-recent-first.
        assertTrue(boost.getValue("cafe alpha") > boost.getValue("cafe beta"))
        assertTrue(boost.getValue("cafe beta") > 0.0)
    }

    @Test
    fun `buildSearchBoost is empty when there is no signal`() {
        assertTrue(buildSearchBoost(emptyList(), emptyList()).isEmpty())
    }
}
