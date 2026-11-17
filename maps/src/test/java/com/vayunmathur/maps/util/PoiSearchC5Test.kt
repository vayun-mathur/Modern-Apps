package com.vayunmathur.maps.util
import com.vayunmathur.maps.domain.spatialFromE7
import com.vayunmathur.maps.data.PoiIndex

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C5 candidate-collection branches: a survivor set too large to place whole
 * (regional window), a cancelled search, absent words, unscanned short
 * tokens, and name-scan/typo fallbacks over a synthetic index.
 */
class PoiSearchC5Test {

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

    private fun load(pois: List<Poi>) {
        val dir = Files.createTempDirectory("poic5").toFile()
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

    private val sfLat = 37.7749
    private val sfLon = -122.4194

    @Test
    fun `a huge far away survivor set falls back to the regional window`() {
        // 1,600 same-token places around New York, searched from San
        // Francisco: too many to place whole, none inside the SF rings, so
        // the regional (file-order) window must still return a page.
        val pois = (0 until 1_600).map { i ->
            Poi(
                407_128_000 + (i % 40) * 1_000,
                -740_060_000 + (i / 40) * 1_000,
                1,
                "Cafe Regional $i",
            )
        } + Poi(377_749_000, -1_224_194_000, 2, "Zebra Stand")
        // The extra SF record spans the spatial grid to the bias point, so
        // the rings around SF visit real (empty-for-cafe) cells instead of
        // clamping onto the New York corner of a NYC-only grid.
        load(pois)
        val got = PoiIndex.searchByName("cafe", sfLat, sfLon, limit = 20)
        assertEquals(20, got.size)
        assertTrue(got.all { it.name.startsWith("Cafe Regional") })
    }

    @Test
    fun `many nearby matches fill the page from the rings`() {
        val pois = (0 until 120).map { i ->
            Poi(
                377_749_000 + (i % 12) * 500,
                -1_224_194_000 + (i / 12) * 500,
                1,
                "Bakery Ring $i",
            )
        }
        load(pois)
        val got = PoiIndex.searchByName("bakery", sfLat, sfLon, limit = 10)
        assertEquals(10, got.size)
        assertTrue(got.all { it.name.startsWith("Bakery Ring") })
    }

    @Test
    fun `a cancelled search returns nothing`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Blue Bottle Coffee"),
                Poi(377_749_100, -1_224_194_100, 2, "Red Door Espresso"),
            )
        )
        val got = PoiIndex.searchByName("blue", sfLat, sfLon, cancelled = { true })
        assertTrue(got.isEmpty(), "cancelled search returned $got")
    }

    @Test
    fun `an absent word and a blank query return nothing`() {
        load(listOf(Poi(377_749_000, -1_224_194_000, 1, "Blue Bottle Coffee")))
        assertTrue(PoiIndex.searchByName("zzzzqqq", sfLat, sfLon).isEmpty())
        assertTrue(PoiIndex.searchByName("", sfLat, sfLon).isEmpty())
        assertTrue(PoiIndex.searchByName("   ", sfLat, sfLon).isEmpty())
    }

    @Test
    fun `a multi word query with a short tail still matches by name`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "123 Market St Bakery"),
                Poi(377_749_100, -1_224_194_100, 2, "456 Market Road Diner"),
            )
        )
        val got = PoiIndex.searchByName("market st", sfLat, sfLon).map { it.name }
        assertEquals(listOf("123 Market St Bakery"), got)
    }

    @Test
    fun `word order does not matter for token matches`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Bottle Blue Cafe"),
                Poi(377_749_100, -1_224_194_100, 2, "Blue Door Cafe"),
            )
        )
        val got = PoiIndex.searchByName("blue bottle", sfLat, sfLon).map { it.name }
        assertEquals(listOf("Bottle Blue Cafe"), got)
    }

    @Test
    fun `a mid word substring finds the nearby name scan fallback`() {
        load(
            listOf(
                Poi(377_749_000, -1_224_194_000, 1, "Blue Bottle Coffee"),
                Poi(377_749_100, -1_224_194_100, 2, "Red Door Espresso"),
            )
        )
        // "ottle" is no word's prefix: only the nearby name scan can match it.
        val got = PoiIndex.searchByName("ottle", sfLat, sfLon).map { it.name }
        assertEquals(listOf("Blue Bottle Coffee"), got)
    }

    @Test
    fun `limit is respected for exact matches`() {
        val pois = (0 until 30).map { i ->
            Poi(377_749_000 + i * 300, -1_224_194_000 + i * 300, 1, "Deli Limit $i")
        }
        load(pois)
        assertEquals(5, PoiIndex.searchByName("deli", sfLat, sfLon, limit = 5).size)
        assertEquals(1, PoiIndex.searchByName("deli", sfLat, sfLon, limit = 1).size)
    }
}
