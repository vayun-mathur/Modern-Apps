package com.vayunmathur.maps.wear.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class WearMapsProtocolTest {

    @Test
    fun goldenSnapshotJson() {
        // Must match the phone copy's golden string byte-for-byte.
        val snapshot = WearMapSnapshot(
            generatedAtMillis = 1_000L,
            centerLatE7 = 340_522_350,
            centerLonE7 = -1_182_436_830,
            lines = listOf(WearMapLine(WearLineKind.ROAD, listOf(0, 0, 10, 0))),
            points = listOf(WearMapPoint(0, "Cafe", 5, -3)),
        )
        assertEquals(
            """{"generatedAtMillis":1000,"centerLatE7":340522350,"centerLonE7":-1182436830,""" +
                """"lines":[{"kind":"ROAD","pts":[0,0,10,0]}],""" +
                """"points":[{"name":"Cafe","dx":5,"dy":-3}]}""",
            WearMapsProtocol.json.encodeToString(snapshot),
        )
    }

    @Test
    fun snapshotAndRouteRoundTrip() {
        val snapshot = WearMapSnapshot(9L, 10, 20, saved = listOf(WearSavedPlace("Home", 1, 2)))
        assertEquals(
            snapshot,
            WearMapsProtocol.json.decodeFromString<WearMapSnapshot>(
                WearMapsProtocol.json.encodeToString(snapshot),
            ),
        )
        val route = WearRouteState(active = true, destinationName = "Work", routePts = listOf(0, 0, 5, 5))
        assertEquals(
            route,
            WearMapsProtocol.json.decodeFromString<WearRouteState>(
                WearMapsProtocol.json.encodeToString(route),
            ),
        )
    }

    @Test
    fun quantizationRoundTripWithinHalfStep() {
        for (meters in listOf(0.0, 1.0, 3.0, 57.0, -123.0, 599.0)) {
            val back = wearDequantize(wearQuantize(meters))
            assertTrue(kotlin.math.abs(back - meters) <= WearMapsProtocol.QUANT_STEP_M / 2 + 1e-9)
        }
    }

    @Test
    fun projectionCentersNorthUpAndFitsRadius() {
        val projection = WearMapProjection(viewportWidthPx = 400f, viewportHeightPx = 400f, radiusM = 600)
        assertEquals(200f to 200f, projection.project(0.0, 0.0))
        // North is up: +north metres -> smaller y, same x.
        val (nx, ny) = projection.project(0.0, 300.0)
        assertEquals(200f, nx)
        assertTrue(ny < 200f)
        // East edge of the region lands on the viewport edge.
        val (ex, _) = projection.project(600.0, 0.0)
        assertEquals(400f, ex)
        assertTrue(projection.insideRegion(600.0, -600.0))
        assertFalse(projection.insideRegion(601.0, 0.0))
    }

    @Test
    fun projectionRoundTripAndPanAndFollow() {
        val base = WearMapProjection(viewportWidthPx = 300f, viewportHeightPx = 400f, radiusM = 600)
        val (x, y) = base.project(120.0, -80.0)
        val (east, north) = base.unproject(x, y)
        assertTrue(kotlin.math.abs(east - 120.0) < 1e-6 && kotlin.math.abs(north + 80.0) < 1e-6)
        // Following the user recentres: user offset projects to viewport centre.
        val follow = base.copy(centerEastM = 120.0, centerNorthM = -80.0)
        assertEquals(150f to 200f, follow.project(120.0, -80.0))
        // Pan shifts everything by the pan amount.
        val panned = base.copy(panEastM = 50.0)
        val (px, _) = panned.project(0.0, 0.0)
        assertTrue(px > 150f)
    }

    @Test
    fun routePointConversionAlignsOrigins() {
        val snapshot = WearMapSnapshot(centerLatE7 = 0, centerLonE7 = 0, radiusM = 600)
        val route = WearRouteState(
            active = true,
            routeOriginLatE7 = 0,
            routeOriginLonE7 = (0.001 * 1e7).toInt(), // ~111 m east
        )
        val (east, north) = wearRoutePointInSnapshot(snapshot, route, dxUnits = 0, dyUnits = 0)
        assertTrue(east in 100.0..125.0 && north == 0.0, "east=$east")
        // A route with the same origin as the snapshot maps 1:1 after dequantize.
        val same = wearRoutePointInSnapshot(snapshot, route.copy(routeOriginLonE7 = 0), 10, -5)
        assertEquals(20.0 to -10.0, same)
    }

    @Test
    fun staleAfterWindowAndWhenMissing() {
        val snapshot = WearMapSnapshot(generatedAtMillis = 1_000L)
        assertFalse(wearIsStale(snapshot, 1_000L + WearMapsProtocol.STALE_AFTER_MS))
        assertTrue(wearIsStale(snapshot, 1_001L + WearMapsProtocol.STALE_AFTER_MS))
        assertTrue(wearIsStale(null, 0L))
    }

    @Test
    fun routeOverlayLifecycle() {
        val active = WearRouteState(active = true, destinationName = "Work", nextText = "Turn left")
        val applied = wearApplyRouteState(null, active)
        assertEquals(active, applied)
        // A newer active state replaces; an inactive state clears and stays cleared.
        val newer = active.copy(nextText = "Turn right")
        assertEquals(newer, wearApplyRouteState(applied, newer))
        assertEquals(null, wearApplyRouteState(newer, WearRouteState(active = false)))
        assertEquals(null, wearApplyRouteState(null, WearRouteState(active = false)))
    }

    @Test
    fun savedDistanceUsesUserPosition() {
        val home = WearSavedPlace("Home", latE7 = 0, lonE7 = (0.01 * 1e7).toInt()) // ~1.1 km east
        val distance = wearDistanceM(0, 0, home)
        assertTrue(distance in 1_000.0..1_200.0, "distance=$distance")
    }
}
