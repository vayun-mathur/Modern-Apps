package com.vayunmathur.maps.util
import com.vayunmathur.maps.data.PoiIndex
import com.vayunmathur.maps.domain.latLngToSpatial
import com.vayunmathur.maps.domain.spatialFromE7
import com.vayunmathur.maps.domain.spatialRangeForBbox

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the Kotlin Morton key against the Rust one.
 *
 * `poi_index.bin` is sorted by a key the Rust writer computes and never stores, so the reader
 * recomputes it to binary-search the file. Nothing checks the two agree at runtime: a
 * disagreement does not throw, it silently returns records from the wrong part of the planet.
 * The expected values come from `latlng_to_spatial` in `scripts/maps/osm_ingest/src/spatial.rs`,
 * not from what this implementation happens to produce.
 */
class SpatialKeyTest {

    /**
     * Four of the seven exceed `Long.MAX_VALUE`, which is the point of including them: they are
     * ordinary northern-hemisphere coordinates, so anything comparing these keys signed is wrong
     * for over half the planet.
     */
    private val vectors = listOf(
        Vector(37.7749, -122.4194, 10260008668490545080uL),   // San Francisco
        Vector(0.0, 0.0, 4611686018427387903uL),              // null island
        Vector(-33.8688, 151.2093, 8426202144485016706uL),    // Sydney
        Vector(51.5074, -0.1278, 13103069469376416030uL),     // London
        Vector(-89.9999, -179.9999, 9594441uL),               // near the SW corner
        Vector(89.9999, 179.9999, 18446744073699957169uL),    // near the NE corner
        Vector(35.6762, 139.6503, 16000615434097125079uL),    // Tokyo
    )

    private class Vector(val lat: Double, val lon: Double, val key: ULong)

    @Test
    fun `matches the rust writer bit for bit`() {
        for (v in vectors) {
            assertEquals(v.key, latLngToSpatial(v.lat, v.lon).toULong(), "(${v.lat}, ${v.lon})")
        }
    }

    @Test
    fun `four of the vectors do not fit in a signed Long`() {
        val big = vectors.count { it.key > Long.MAX_VALUE.toULong() }
        assertEquals(4, big, "the unsigned cases are what make this suite worth having")
    }

    /**
     * `spatial_from_e7` is the form the writer actually sorts by, and it feeds the reader's
     * binary search one stored record at a time. It multiplies by `1e-7` rather than dividing by
     * `1e7`; these vectors were generated through that arithmetic.
     */
    @Test
    fun `agrees with the writer for stored e7 coordinates`() {
        assertEquals(10260008668490545080uL, spatialFromE7(377_749_000, -1_224_194_000).toULong())
        assertEquals(4611686018427387903uL, spatialFromE7(0, 0).toULong())
        assertEquals(8426202144485016706uL, spatialFromE7(-338_688_000, 1_512_093_000).toULong())
        assertEquals(13103069469376416030uL, spatialFromE7(515_074_000, -1_278_000).toULong())
    }

    /** The corners of the domain, where a saturating-cast mistake would show up first. */
    @Test
    fun `saturates at the corners instead of wrapping`() {
        assertEquals(0uL, latLngToSpatial(-90.0, -180.0).toULong())
        // Out of range in both axes: Rust's `f64 as u32` clamps, so this must not wrap to a
        // small key. Kotlin's Double.toInt() would have stopped at Int.MAX_VALUE.
        assertEquals(latLngToSpatial(90.0, 180.0), latLngToSpatial(1000.0, 1000.0))
        assertEquals(latLngToSpatial(-90.0, -180.0), latLngToSpatial(-1000.0, -1000.0))
    }

    @Test
    fun `is monotonic in each axis independently`() {
        val a = latLngToSpatial(37.0, -122.0)
        val b = latLngToSpatial(37.0, -121.0)
        assertTrue(
            java.lang.Long.compareUnsigned(a, b) < 0,
            "increasing longitude must increase the key",
        )
        val c = latLngToSpatial(38.0, -122.0)
        assertTrue(
            java.lang.Long.compareUnsigned(a, c) < 0,
            "increasing latitude must increase the key",
        )
    }

    /**
     * The containment property [spatialRangeForBbox] relies on, checked against random boxes
     * rather than asserted in a comment.
     *
     * Every point inside a box must have a key inside the box corners' key interval. If this
     * were ever false, `PoiIndex` would stop early and drop POIs — silently, and only for some
     * coordinates, which is the worst way for a spatial index to be wrong.
     */
    @Test
    fun `every point in a bbox has a key inside the corner interval`() {
        val rng = Random(20260824)
        repeat(20_000) {
            val lat0 = rng.nextInt(-900_000_000, 900_000_000)
            val lon0 = rng.nextInt(-1_800_000_000, 1_800_000_000)
            val minLatE7 = lat0
            val maxLatE7 = (lat0.toLong() + rng.nextInt(0, 10_000_000)).coerceAtMost(900_000_000L).toInt()
            val minLonE7 = lon0
            val maxLonE7 = (lon0.toLong() + rng.nextInt(0, 10_000_000)).coerceAtMost(1_800_000_000L).toInt()
            val (first, last) = spatialRangeForBbox(minLatE7, maxLatE7, minLonE7, maxLonE7)
            val latE7 = rng.nextInt(minLatE7, maxLatE7 + 1)
            val lonE7 = rng.nextInt(minLonE7, maxLonE7 + 1)
            val key = spatialFromE7(latE7, lonE7)
            assertTrue(
                java.lang.Long.compareUnsigned(first, key) <= 0 &&
                    java.lang.Long.compareUnsigned(key, last) <= 0,
                "($latE7, $lonE7) fell outside the interval for " +
                    "[$minLatE7..$maxLatE7] x [$minLonE7..$maxLonE7]",
            )
        }
    }
}
