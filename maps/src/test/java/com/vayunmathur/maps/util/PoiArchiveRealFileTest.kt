package com.vayunmathur.maps.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * The app's POI reader against a REAL planet archive (`planet4.mamaps` or its
 * byte-identical local build). Skipped unless `MAMAPS_REAL_ARCHIVE` names the
 * file, so CI and normal unit-test runs never need the 80 GB input:
 *
 * ```
 * MAMAPS_REAL_ARCHIVE=/path/to/world.mamaps ./gradlew :maps:testDevUnitTest \
 *     --tests '*PoiArchiveRealFileTest*'
 * ```
 *
 * It goes through exactly the production entry point ([PoiIndex.reloadArchive]
 * -> [PoiArchive]) and checks every POI section the app reads: index + names
 * (kinds 8/9), attributes incl. rating / review count / summary (10), the
 * spatial grid (11), the word index (12) and the boundary links (15).
 */
class PoiArchiveRealFileTest {

    private fun archive(): File {
        val path = System.getenv(ENV)
        Assume.assumeTrue("$ENV not set; real-archive test skipped", !path.isNullOrBlank())
        val file = File(path!!)
        Assume.assumeTrue("$ENV=$path does not exist", file.isFile)
        return file
    }

    private fun load(): PoiIndex.Mapped {
        val file = archive()
        assertTrue(PoiIndex.reloadArchive(file), "the app could not map POI sections from $file")
        return assertNotNull(PoiIndex.mappedForQuery())
    }

    @Test
    fun `every POI section maps and agrees on the record count`() {
        val m = load()
        println("POI records=${m.count} names=${m.namesLen}B cells=${m.cellCount} words=${m.entryCount}")
        assertTrue(m.count > MIN_PLANET_RECORDS, "only ${m.count} records: not a planet index")
        assertNotNull(m.attrs, "attrs section (kind 10) refused")
        assertNotNull(m.spatial, "spatial section (kind 11) refused")
        assertTrue(m.cellCount > 0)
        assertNotNull(m.nameIdx, "word index (kind 12) refused")
        assertTrue(m.entryCount > m.count, "word index should hold >1 word per record on average")
        assertNotNull(m.region, "region section (kind 15) refused")
    }

    @Test
    fun `nearest and viewport queries answer in four cities`() {
        load()
        for ((city, lat, lon) in CITIES) {
            val near = PoiIndex.nearest(lat, lon, limit = 20, maxMeters = 500.0)
            val box = PoiIndex.inViewport(lon - 0.01, lat - 0.01, lon + 0.01, lat + 0.01)
            println("$city nearest=${near.size} first=${near.firstOrNull()?.name} viewport=${box.size}")
            assertTrue(near.isNotEmpty(), "$city: no POI within 500 m")
            assertTrue(box.isNotEmpty(), "$city: empty viewport")
            assertTrue(near.all { it.name.isNotEmpty() }, "$city: a nearest hit has no name")
        }
    }

    @Test
    fun `name search finds landmarks and the new type-51 place labels`() {
        load()
        for ((query, lat, lon) in LANDMARKS) {
            val hits = PoiIndex.searchByName(query, lat, lon, limit = 10)
            println("search '$query' -> ${hits.take(3).map { "${it.name}(${it.type})" }}")
            assertTrue(hits.isNotEmpty(), "no hit for '$query'")
            val d = PoiIndex.distanceSq(hits[0].lat, hits[0].lon, lat, lon)
            assertTrue(d < NEAR_DEG_SQ, "top hit for '$query' is far away: ${hits[0]}")
        }
        // Place labels became searchable records (type 51) in planet4.
        for ((query, lat, lon) in PLACES) {
            val hits = PoiIndex.searchByName(query, lat, lon, limit = 20)
            val place = hits.firstOrNull { it.type == TYPE_PLACE && it.name.equals(query, ignoreCase = true) }
            println("place '$query' -> $place")
            assertNotNull(place, "'$query' has no type-51 place label among ${hits.map { it.name to it.type }}")
            assertEquals("Place", PoiCategories.label(place.type))
            assertTrue(place.regionId != 0L, "'$query' place label carries no boundary link")
            // Tagged OSM id: low 2 bits are the id space (2 = way, 3 = relation).
            assertTrue((place.regionId and SPACE_MASK) in 2L..3L, "bad tagged id ${place.regionId}")
        }
    }

    @Test
    fun `attributes decode incl rating review count and summary`() {
        val m = load()
        var withAttrs = 0
        var rated = 0
        var counted = 0
        var summarised = 0
        var example: PoiIndex.PoiAttributes? = null
        // Stride across the whole index: cheap, and covers every region of the file.
        val stride = (m.count / ATTR_SAMPLES).coerceAtLeast(1)
        var i = 0
        while (i < m.count) {
            val a = PoiIndex.attributesAt(i)
            if (a != null) {
                withAttrs++
                if (a.rating != null) {
                    rated++
                    assertTrue(a.rating in 1.0..5.0)
                }
                if (a.reviewCount != null) counted++
                if (a.reviewSummary != null) {
                    summarised++
                    if (example == null) example = a
                }
            }
            i += stride
        }
        println("attrs sampled=${m.count / stride} with=$withAttrs rating=$rated count=$counted summary=$summarised")
        println("example with summary: $example")
        assertTrue(withAttrs > 0, "no attributes decoded")
        assertTrue(rated > 0, "no rating (KEY_RATING) decoded")
        assertTrue(counted > 0, "no review count (KEY_REVIEW_COUNT) decoded")
        assertTrue(summarised > 0, "no review summary (KEY_REVIEW_SUMMARY) decoded")
    }

    @Test
    fun `well known places show archive rating count and summary`() {
        load()
        var rated = 0
        var summarised = 0
        for ((query, lat, lon) in FAMOUS) {
            val hit = PoiIndex.searchByName(query, lat, lon, limit = 5).firstOrNull()
            val attrs = hit?.let { PoiIndex.attributesAt(it.ordinal) }
            println(
                "review '$query' -> ${hit?.name} rating=${attrs?.rating} count=${attrs?.reviewCount} " +
                    "summary=${attrs?.reviewSummary?.take(SUMMARY_PREVIEW)}",
            )
            if (attrs?.rating != null) rated++
            if (attrs?.rating != null && attrs.reviewCount != null && attrs.reviewSummary != null) summarised++
        }
        // Coverage depends on the review table (reviews_archive.tsv); decoding is what is
        // pinned here, so a couple of fully reviewed landmarks are enough.
        assertTrue(rated >= 2, "only $rated of ${FAMOUS.size} famous places carry an archive rating")
        assertTrue(summarised >= 1, "no famous place carries rating + count + summary")
    }

    @Test
    fun `boundary links cover most records`() {
        val m = load()
        var linked = 0
        val stride = (m.count / ATTR_SAMPLES).coerceAtLeast(1)
        var sampled = 0
        var i = 0
        while (i < m.count) {
            if (m.regionId(i) != 0L) linked++
            sampled++
            i += stride
        }
        println("region links $linked/$sampled sampled")
        assertTrue(linked * 2 > sampled, "fewer than half the sampled POIs link to a boundary")
    }

    private companion object {
        const val ENV = "MAMAPS_REAL_ARCHIVE"
        const val MIN_PLANET_RECORDS = 10_000_000
        const val TYPE_PLACE = 51
        const val SPACE_MASK = 3L
        const val ATTR_SAMPLES = 200_000
        /** ~0.5 degrees squared: "in the same metro". */
        const val NEAR_DEG_SQ = 0.25

        val CITIES = listOf(
            Triple("SF", 37.7749, -122.4194),
            Triple("NYC", 40.7580, -73.9855),
            Triple("London", 51.5074, -0.1278),
            Triple("Tokyo", 35.6812, 139.7671),
        )
        val LANDMARKS = listOf(
            Triple("Golden Gate Bridge", 37.7749, -122.4194),
            Triple("Empire State Building", 40.7580, -73.9855),
            Triple("British Museum", 51.5074, -0.1278),
            Triple("Tokyo Station", 35.6812, 139.7671),
        )
        const val SUMMARY_PREVIEW = 80
        val FAMOUS = listOf(
            Triple("Tartine Bakery", 37.7614, -122.4241),
            Triple("Golden Gate Park", 37.7694, -122.4862),
            Triple("Katz's Delicatessen", 40.7223, -73.9874),
            Triple("Central Park", 40.7829, -73.9654),
            Triple("British Museum", 51.5194, -0.1270),
            Triple("Borough Market", 51.5055, -0.0910),
            Triple("Senso-ji", 35.7148, 139.7967),
            Triple("Tokyo Tower", 35.6586, 139.7454),
        )
        val PLACES = listOf(
            Triple("Oakland", 37.80, -122.27),
            Triple("Brooklyn", 40.65, -73.95),
            Triple("Camden Town", 51.539, -0.1426),
        )
    }
}
