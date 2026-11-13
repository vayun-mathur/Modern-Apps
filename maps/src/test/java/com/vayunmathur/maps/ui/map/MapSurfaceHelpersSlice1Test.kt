package com.vayunmathur.maps.ui.map

import androidx.compose.ui.graphics.toArgb
import com.vayunmathur.library.map.GeoBounds
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ipc.FamilyMember
import com.vayunmathur.maps.ui.FAMILY_LOCATION_LAYER_ID
import com.vayunmathur.maps.ui.PARKING_PIN_LAYER_ID
import com.vayunmathur.maps.ui.SAVED_PLACE_LAYER_ID
import com.vayunmathur.maps.ui.SEARCH_RESULT_LAYER_ID
import com.vayunmathur.maps.ui.theme.mapTokens
import com.vayunmathur.maps.util.OfflineRouterTraffic
import com.vayunmathur.maps.util.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): the pure helpers behind [MapSurface] — traffic table
 * bucketing, the tappable pin set and the region mask.
 *
 * Plain JVM: colours compare through [toArgb], whose only Android call sits
 * behind `returnDefaultValues` — consistent within the test, which is all the
 * bucketing assertions need.
 */
class MapSurfaceHelpersSlice1Test {

    private val tokens = mapTokens(isDark = true)

    private fun components(vararg pct: Int, base: Long = 7L) =
        OfflineRouterTraffic.TrafficComponents(
            LongArray(pct.size) { base + it },
            ByteArray(pct.size) { pct[it].toByte() },
        )

    @Test
    fun `traffic ratios bucket like the route colours`() {
        val table = assertNotNull(buildTrafficColorTable(components(20, 70, 95), tokens))
        assertEquals(listOf(7L, 8L, 9L), table.ids.toList())
        assertEquals(
            listOf(
                tokens.traffic.jam.toArgb(),
                tokens.traffic.slow.toArgb(),
                tokens.traffic.free.toArgb(),
            ),
            table.argb.toList(),
        )
    }

    @Test
    fun `a zero ratio is no data and an empty table is null`() {
        // Only a no-data component: nothing left to draw.
        assertNull(buildTrafficColorTable(components(0), tokens))
        assertNull(buildTrafficColorTable(OfflineRouterTraffic.TrafficComponents.EMPTY, tokens))
        // Mixed: the no-data id is dropped, the rest survive.
        val table = assertNotNull(buildTrafficColorTable(components(0, 100), tokens))
        assertEquals(listOf(8L), table.ids.toList())
    }

    @Test
    fun `a session-sized traffic table builds identically to the per-component reference`() {
        // One entry per road piece in every square visited: a metro session reaches the
        // hundreds of thousands. Rebuilt on every landing fetch, so it now runs off the main
        // thread (MapSurface's produceState) with its three colours resolved once.
        val n = 600_000
        val big = OfflineRouterTraffic.TrafficComponents(
            LongArray(n) { it.toLong() },
            ByteArray(n) { ((it * 37) % 101).toByte() },
        )
        fun reference(c: OfflineRouterTraffic.TrafficComponents): Pair<List<Long>, List<Int>> {
            val ids = ArrayList<Long>()
            val argb = ArrayList<Int>()
            for (i in c.ids.indices) {
                val pct = c.ratioPct[i].toInt() and 0xFF
                if (pct == 0) continue
                val ratio = pct / 100.0
                ids += c.ids[i]
                argb += when {
                    ratio < 0.5 -> tokens.traffic.jam
                    ratio < 0.9 -> tokens.traffic.slow
                    else -> tokens.traffic.free
                }.toArgb()
            }
            return ids to argb
        }
        val (refIds, refArgb) = reference(big)
        val table = assertNotNull(buildTrafficColorTable(big, tokens))
        assertEquals(refIds, table.ids.toList())
        assertEquals(refArgb, table.argb.toList())
        // Timing for the report (best of five, after the warm-up above).
        val millis = (1..5).minOf {
            val t = System.nanoTime()
            buildTrafficColorTable(big, tokens)
            (System.nanoTime() - t) / 1e6
        }
        println("traffic table: $n components built in ${"%.2f".format(millis)} ms (now off the main thread)")
    }

    @Test
    fun `pin features cover every pin kind in probe order`() {
        val pins = pinFeatures(
            searchResults = listOf(SearchResult("s", "Cafe", null, 37.71, -122.41, null)),
            savedPlaces = listOf(SavedPlace("Home", 37.72, -122.42)),
            parkingSpot = ParkingSpot(37.7, -122.4, 0L),
            familyMembers = listOf(FamilyMember(9L, "Ada", 37.73, -122.43, 0L, -1f)),
        )
        // Parking first so it wins the probe order.
        assertEquals(
            listOf(
                PARKING_PIN_LAYER_ID,
                SEARCH_RESULT_LAYER_ID,
                SAVED_PLACE_LAYER_ID,
                FAMILY_LOCATION_LAYER_ID,
            ),
            pins.map { it.layerId },
        )
    }

    @Test
    fun `an empty pin set builds no markers`() {
        val (markers, hits) = buildMarkers(emptyList(), emptyList(), null, emptyList())
        assertTrue(markers.isEmpty())
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `a small viewport spans few squares and a world one bails`() {
        // Enumeration guard, stated without touching native: a settled
        // viewport is a handful of squares, well under the cap. (floors of
        // negative longitudes widen the span by one — pinned, not assumed.)
        val cells = cellsFor(GeoBounds(west = -122.5, south = 37.0, east = -122.0, north = 37.5))
        assertEquals(2L, cells)
        val world = cellsFor(GeoBounds(west = -180.0, south = -60.0, east = 180.0, north = 70.0))
        assertTrue(world > TRAFFIC_MAX_SQUARES)
    }

    @Test
    fun `region mask covers every admin level and nothing else`() {
        val paris = GeoPoint(2.35, 48.85)
        assertEquals(
            1L,
            regionMaskFor(SpecificFeature.Admin0Label(name = "F", position = paris, regionId = 1L)),
        )
        assertEquals(
            2L,
            regionMaskFor(SpecificFeature.Admin1Label(name = "G", position = paris, regionId = 2L)),
        )
        assertNull(
            regionMaskFor(SpecificFeature.Admin0Label(name = "F", position = null, regionId = 1L)),
        )
        assertNull(
            regionMaskFor(
                SpecificFeature.GenericPlace(
                    name = "Cafe", phone = null, website = null, openingHours = null,
                    position = paris,
                ),
            ),
        )
        // A region picked from search carries the index hit's boundary link,
        // so it masks without an admin-label upgrade.
        assertEquals(
            7L,
            regionMaskFor(
                SpecificFeature.GenericPlace(
                    name = "Testville", phone = null, website = null, openingHours = null,
                    position = paris, regionId = 7L,
                ),
            ),
        )
    }

    @Test
    fun `the traffic tuning constants match their documented policy`() {
        assertEquals(400L, TRAFFIC_PREFETCH_DEBOUNCE_MS)
        assertEquals(11.0, TRAFFIC_MIN_ZOOM)
        assertEquals(16L, TRAFFIC_MAX_SQUARES)
    }

    /** The cell count [prefetchTrafficSquares] guards on, factored for tests. */
    private fun cellsFor(bounds: GeoBounds): Long {
        val lonMin = kotlin.math.floor(bounds.west).toInt()
        val lonMax = kotlin.math.floor(bounds.east).toInt()
        val latMin = kotlin.math.floor(bounds.south).toInt()
        val latMax = kotlin.math.floor(bounds.north).toInt()
        return (lonMax - lonMin + 1).toLong() * (latMax - latMin + 1).toLong()
    }
}
