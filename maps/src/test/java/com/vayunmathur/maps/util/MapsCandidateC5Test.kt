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
 * C5 candidate-collection scale branches: a survivor set too large to
 * place whole falls back to the regional Morton window, and a driver
 * token too common to materialise is collected by streaming rings.
 * Fixtures mirror PoiIndexSmartTest's synthetic side files.
 */
class MapsCandidateC5Test {

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
        val dir = Files.createTempDirectory("poicand5").toFile()
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
        val out = java.io.ByteArrayOutputStream()
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
            words(p.name).indices.take(255).forEach { wordIdx -> entries.add(ordinal to wordIdx) }
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

    @Test
    fun `survivor set beyond the global cap uses the regional window`() {
        // 2,000 matches in New York, searched with a limit no ring walk can
        // fill from the bias point: placement falls back to the regional
        // Morton window around the bias.
        val pois = (0 until 2_000).map { i ->
            Poi(
                407_128_000 + (i % 50) * 1_000,
                -740_060_000 + (i / 50) * 1_000,
                1,
                "Cafe Regional $i",
            )
        }
        load(pois)
        val got = PoiIndex.searchByName("cafe regional", 40.7128, -74.006, limit = 5_000)
        assertTrue(got.isNotEmpty(), "regional search returned nothing")
        assertTrue(got.size <= 1_500, "regional window is capped, got ${got.size}")
        assertTrue(got.all { it.name.startsWith("Cafe Regional") })
    }

    @Test
    fun `driver token beyond the materialise cap streams by rings`() {
        // 260k records sharing the driver word "cafe": too many to
        // materialise, so collection streams ring by ring near the bias.
        val pois = (0 until 260_000).map { i ->
            Poi(
                377_749_000 + (i % 700) * 100,
                -1_224_194_000 + (i / 700) * 100,
                1,
                "Cafe Stream $i",
            )
        }
        load(pois)
        val got = PoiIndex.searchByName("cafe", 37.7749, -122.4194, limit = 20)
        assertEquals(20, got.size)
        assertTrue(got.all { it.name.startsWith("Cafe Stream") })
    }
}
