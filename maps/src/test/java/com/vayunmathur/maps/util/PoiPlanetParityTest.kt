package com.vayunmathur.maps.util

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * POI parity: the same queries through the production reader
 * ([PoiIndex.reloadArchive] -> [PoiArchive]) against a baseline archive and
 * a candidate must answer identically — search hits, nearest/viewport hits,
 * boundary links, the "City, State, Country" locality line and place
 * attributes.
 *
 * By default both archives are the small synthetic fixture
 * ([PoiSyntheticArchive]), the candidate with its attrs/region sections
 * block-compressed (DEFLATE ZSEC) and the baseline raw — the planet5 format
 * change this test exists to pin — so it runs hermetically in CI instead of
 * skipping. When both env vars name real archives the same comparison runs
 * against them at planet scale:
 *
 * ```
 * MAMAPS_BASELINE_ARCHIVE=planet5-rawpoi.mamaps MAMAPS_REAL_ARCHIVE=planet5.mamaps \
 *     ./gradlew :maps:testDevUnitTest --tests '*PoiPlanetParityTest*'
 * ```
 */
class PoiPlanetParityTest {

    private fun envFile(env: String): File? {
        val path = System.getenv(env)?.takeIf { it.isNotBlank() } ?: return null
        return File(path).takeIf { it.isFile }
    }

    /** Everything a user sees from the POI index for the fixed query set, plus timings. */
    private data class Answers(val lines: List<String>, val timings: Map<String, Pair<Double, Double>>)

    /** Cold/warm timings for [pass], keeping only the cold run's output lines. */
    private fun timedPair(lines: ArrayList<String>, n: Int, pass: () -> Unit): Pair<Double, Double> {
        val cold = nanosPer(n, pass)
        val keep = lines.size
        val warm = nanosPer(n, pass)
        while (lines.size > keep) lines.removeAt(lines.size - 1)
        return cold to warm
    }

    private fun nanosPer(n: Int, block: () -> Unit): Double {
        val t = System.nanoTime()
        block()
        return (System.nanoTime() - t) / 1_000.0 / n
    }

    private fun probeAnswers(m: PoiIndex.Mapped, planet: Boolean, lines: ArrayList<String>): Answers {
        val rnd = Random(SEED)
        val probes = if (planet) ATTR_PROBES else m.count
        val ords = IntArray(probes) { if (planet) rnd.nextInt(m.count) else it }
        fun attrPass(): Unit = ords.forEach { o -> lines.add("attrs $o: ${PoiIndex.attributesAt(o)}") }
        val attrCold = nanosPer(ords.size) { attrPass() }
        val keep3 = lines.size
        val attrWarm = nanosPer(ords.size) { attrPass() }
        while (lines.size > keep3) lines.removeAt(lines.size - 1)
        val timings = LinkedHashMap<String, Pair<Double, Double>>()
        timings["attributesAt random (us/call)"] = attrCold to attrWarm
        val regionProbes = if (planet) REGION_PROBES else m.count
        val ords2 = IntArray(regionProbes) { if (planet) rnd.nextInt(m.count) else it }
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

    private fun answers(file: File): Answers {
        assertTrue(PoiIndex.reloadArchive(file), "the app could not map POI sections from $file")
        val m = kotlin.test.assertNotNull(PoiIndex.mappedForQuery())
        val planet = m.count > 1_000
        val lines = ArrayList<String>()
        val timings = LinkedHashMap<String, Pair<Double, Double>>()
        fun row(r: PoiIndex.PoiRecord) =
            "${r.ordinal}|${r.name}|${r.latE7},${r.lonE7}|t${r.type}|reg${r.regionId}|" +
                "${PoiIndex.localityAt(r.ordinal, english = true)}"

        val cities = if (planet) CITIES else PoiSyntheticArchive.RECORDS.take(4)
            .map { Triple(it.name, it.lat, it.lon) }
        val queries = if (planet) QUERIES else listOf(
            Triple("Golden Gate Bridge", 37.8199, -122.4783),
            Triple("Tartine Bakery", 37.7614, -122.4241),
            Triple("Golden Gate Park", 37.7694, -122.4862),
            Triple("Oakland", 37.8044, -122.2712),
            Triple("Cafe", 37.7749, -122.4194),
        )

        // Name search, City/State/Country line and attributes of each hit.
        fun searchPass(): Unit = queries.forEach { (q, lat, lon) ->
            PoiIndex.searchByName(q, lat, lon, limit = 10).forEach { lines.add("search $q: ${row(it)}") }
        }
        timings["search (us/query)"] = timedPair(lines, queries.size) { searchPass() }

        // Spatial: nearest + viewport in each city (each record carries its regionId).
        fun spatialPass(): Unit = cities.forEach { (c, lat, lon) ->
            PoiIndex.nearest(lat, lon, limit = 20, maxMeters = 500.0).forEach { lines.add("near $c: ${row(it)}") }
            val box = PoiIndex.inViewport(lon - 0.01, lat - 0.01, lon + 0.01, lat + 0.01)
            lines.add("viewport $c: ${box.size} regionsum ${box.sumOf { it.regionId }}")
        }
        timings["nearest+viewport (us/city)"] = timedPair(lines, cities.size) { spatialPass() }

        val probed = probeAnswers(m, planet, lines)
        timings.putAll(probed.timings)
        return Answers(lines, timings)
    }

    @Test
    fun `block compressed POI sections answer exactly like raw ones`() {
        val baselineFile = envFile(BASELINE_ENV)
        val candidateFile = envFile(CANDIDATE_ENV)
        val baseline = baselineFile ?: PoiSyntheticArchive.write("poibase", blockCompressed = false)
        val candidate = candidateFile ?: PoiSyntheticArchive.write("poicand", blockCompressed = true)
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
        if (baselineFile != null && candidateFile != null) {
            assertTrue(a.lines.any { it.contains("United States") }, "no City/State/Country line resolved")
        } else {
            // Fixture admin hierarchy: Paris under France for every record.
            assertTrue(a.lines.any { it.contains("Paris, France") }, "no locality line resolved: ${a.lines.take(5)}")
        }
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
