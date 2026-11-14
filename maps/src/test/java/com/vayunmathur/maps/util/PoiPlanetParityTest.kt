package com.vayunmathur.maps.util

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * planet5 POI parity and speed on REAL archives: the same queries through the production
 * reader ([PoiIndex.reloadArchive] -> [PoiArchive]) against a baseline archive and a
 * candidate, which must answer identically -- search hits, nearest/viewport hits, boundary
 * links, the "City, State, Country" locality line and place attributes -- and the time each
 * query path takes on both.
 *
 * Skipped unless both are named (the inputs are 60-80 GB files):
 *
 * ```
 * MAMAPS_BASELINE_ARCHIVE=planet5-rawpoi.mamaps MAMAPS_REAL_ARCHIVE=planet5.mamaps \
 *     ./gradlew :maps:testDevUnitTest --tests '*PoiPlanetParityTest*'
 * ```
 *
 * Use the same POI data in both (a raw-POI and a block-POI pack of one build), so any
 * difference is the reader's. Timings are host JVM, mapped files in the page cache, block
 * caches cold on the first pass and warm on the second.
 */
class PoiPlanetParityTest {

    private fun file(env: String): File {
        val path = System.getenv(env)
        Assume.assumeTrue("$env not set; planet parity test skipped", !path.isNullOrBlank())
        val f = File(path!!)
        Assume.assumeTrue("$env=$path does not exist", f.isFile)
        return f
    }

    /** Everything a user sees from the POI index for the fixed query set, plus timings. */
    private data class Answers(val lines: List<String>, val timings: Map<String, Pair<Double, Double>>)

    private fun nanosPer(n: Int, block: () -> Unit): Double {
        val t = System.nanoTime()
        block()
        return (System.nanoTime() - t) / 1_000.0 / n
    }

    private fun answers(file: File): Answers {
        assertTrue(PoiIndex.reloadArchive(file), "the app could not map POI sections from $file")
        val m = kotlin.test.assertNotNull(PoiIndex.mappedForQuery())
        val lines = ArrayList<String>()
        val timings = LinkedHashMap<String, Pair<Double, Double>>()
        fun row(r: PoiIndex.PoiRecord) =
            "${r.ordinal}|${r.name}|${r.latE7},${r.lonE7}|t${r.type}|reg${r.regionId}|" +
                "${PoiIndex.localityAt(r.ordinal, english = true)}"

        // Name search, City/State/Country line and attributes of each hit.
        fun searchPass(): Unit = QUERIES.forEach { (q, lat, lon) ->
            PoiIndex.searchByName(q, lat, lon, limit = 10).forEach { lines.add("search $q: ${row(it)}") }
        }
        val searchCold = nanosPer(QUERIES.size) { searchPass() }
        val keep = lines.size
        val searchWarm = nanosPer(QUERIES.size) { searchPass() }
        while (lines.size > keep) lines.removeAt(lines.size - 1)
        timings["search (us/query)"] = searchCold to searchWarm

        // Spatial: nearest + viewport in each city (each record carries its regionId).
        fun spatialPass(): Unit = CITIES.forEach { (c, lat, lon) ->
            PoiIndex.nearest(lat, lon, limit = 20, maxMeters = 500.0).forEach { lines.add("near $c: ${row(it)}") }
            val box = PoiIndex.inViewport(lon - 0.01, lat - 0.01, lon + 0.01, lat + 0.01)
            lines.add("viewport $c: ${box.size} regionsum ${box.sumOf { it.regionId }}")
        }
        val spatialCold = nanosPer(CITIES.size) { spatialPass() }
        val keep2 = lines.size
        val spatialWarm = nanosPer(CITIES.size) { spatialPass() }
        while (lines.size > keep2) lines.removeAt(lines.size - 1)
        timings["nearest+viewport (us/city)"] = spatialCold to spatialWarm

        // Place sheet: attributes of random ordinals (each one a different block when cold).
        val rnd = Random(SEED)
        val ords = IntArray(ATTR_PROBES) { rnd.nextInt(m.count) }
        fun attrPass(): Unit = ords.forEach { o -> lines.add("attrs $o: ${PoiIndex.attributesAt(o)}") }
        val attrCold = nanosPer(ords.size) { attrPass() }
        val keep3 = lines.size
        val attrWarm = nanosPer(ords.size) { attrPass() }
        while (lines.size > keep3) lines.removeAt(lines.size - 1)
        timings["attributesAt random (us/call)"] = attrCold to attrWarm

        // Boundary link + locality of random ordinals (search rows of far-apart places).
        val ords2 = IntArray(REGION_PROBES) { rnd.nextInt(m.count) }
        fun regionPass(): Unit = ords2.forEach { o ->
            lines.add("region $o: ${m.regionId(o)} ${PoiIndex.localityAt(o, english = true)}")
        }
        val regCold = nanosPer(ords2.size) { regionPass() }
        val keep4 = lines.size
        val regWarm = nanosPer(ords2.size) { regionPass() }
        while (lines.size > keep4) lines.removeAt(lines.size - 1)
        timings["regionId+locality random (us/call)"] = regCold to regWarm
        return Answers(lines, timings)
    }

    @Test
    fun `block compressed POI sections answer exactly like raw ones`() {
        val baseline = file(BASELINE_ENV)
        val candidate = file(CANDIDATE_ENV)
        val a = answers(baseline)
        val b = answers(candidate)
        println("POI parity: ${a.lines.size} answer lines")
        for (k in a.timings.keys) {
            val (ac, aw) = a.timings.getValue(k)
            val (bc, bw) = b.timings.getValue(k)
            println(
                "TIMING %-36s baseline cold %9.1f warm %9.1f | candidate cold %9.1f warm %9.1f".format(k, ac, aw, bc, bw),
            )
        }
        a.lines.filter { it.startsWith("search ") }.distinctBy { it.substringBefore(":") }.forEach { println(it) }
        val diff = a.lines.indices.firstOrNull { a.lines[it] != b.lines.getOrNull(it) }
        assertEquals(a.lines.size, b.lines.size, "answer counts differ")
        assertEquals(null, diff, "first difference: ${diff?.let { a.lines[it] + " vs " + b.lines[it] }}")
        assertTrue(a.lines.any { it.contains("United States") }, "no City/State/Country line resolved")
    }

    private companion object {
        const val BASELINE_ENV = "MAMAPS_BASELINE_ARCHIVE"
        const val CANDIDATE_ENV = "MAMAPS_REAL_ARCHIVE"
        const val SEED = 5
        const val ATTR_PROBES = 2_000
        const val REGION_PROBES = 2_000
        val CITIES = listOf(
            Triple("SF", 37.7749, -122.4194),
            Triple("NYC", 40.7580, -73.9855),
            Triple("London", 51.5074, -0.1278),
            Triple("Tokyo", 35.6812, 139.7671),
            Triple("Sydney", -33.8688, 151.2093),
            Triple("Paris", 48.8566, 2.3522),
        )
        val QUERIES = listOf(
            Triple("Golden Gate Bridge", 37.7749, -122.4194),
            Triple("Space Needle", 47.6062, -122.3321),
            Triple("Empire State Building", 40.7580, -73.9855),
            Triple("Tartine Bakery", 37.7614, -122.4241),
            Triple("British Museum", 51.5194, -0.1270),
            Triple("Tour Eiffel", 48.8584, 2.2945),
            Triple("Brandenburger Tor", 52.5163, 13.3777),
            Triple("Sydney Opera House", -33.8568, 151.2153),
            Triple("Tokyo Station", 35.6812, 139.7671),
            Triple("Starbucks", 47.6062, -122.3321),
            Triple("pharmacy", 40.7580, -73.9855),
            Triple("Oakland", 37.80, -122.27),
        )
    }
}
