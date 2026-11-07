package com.vayunmathur.maps.ui.map

import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.map.GeoBounds
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.ui.theme.mapTokens
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.RouteService
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Slice 1/3 second pass (maps): native-adjacent probes and route-overlay
 * colour decisions the first pass left — traffic prefetch over a real
 * viewport and transit line colours (valid, blank, malformed)
 * through the public overlay builder.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1Pass2ProbesTest {

    @Test
    fun `traffic prefetch enumerates a small viewport and bails on huge ones`() {
        // one 1° square: the native dedup makes repeats free; on the host the
        // call must simply not throw (host Rust libs are on the library path)
        prefetchTrafficSquares(GeoBounds(west = -122.4, south = 37.7, east = -122.3, north = 37.8))
        // antimeridian-wide and inverted boxes are skipped before any native call
        prefetchTrafficSquares(GeoBounds(west = -180.0, south = -80.0, east = 180.0, north = 80.0))
        prefetchTrafficSquares(GeoBounds(west = 10.0, south = 50.0, east = 9.0, north = 49.0))
    }

    private fun transitStep(color: String?) = RouteService.Step(
        distanceMeters = 100.0,
        staticDuration = 1.minutes,
        polyline = listOf(GeoPoint(-122.4, 37.7), GeoPoint(-122.3, 37.8)),
        navInstruction = RouteService.API.NavInstruction(),
        travelMode = RouteService.TravelMode.TRANSIT,
        transitDetails = RouteService.API.TransitDetails(
            headsign = "Downtown",
            stopCount = 3,
            transitLine = RouteService.API.TransitLine("N", "N", color ?: ""),
            stopDetails = RouteService.API.StopDetails(
                "17:00", "17:10",
                RouteService.API.Stop("A"), RouteService.API.Stop("B"),
            ),
        ),
    )

    private fun driveStep(ratio: Double) = RouteService.Step(
        distanceMeters = 100.0,
        staticDuration = 1.minutes,
        polyline = listOf(GeoPoint(-122.4, 37.7), GeoPoint(-122.3, 37.8)),
        navInstruction = RouteService.API.NavInstruction(),
        travelMode = RouteService.TravelMode.DRIVE,
        speedRatio = ratio,
    )

    private fun routeOf(vararg steps: RouteService.Step): RouteService.Route {
        val full = steps.flatMapIndexed { i, s ->
            if (i == 0) s.polyline else s.polyline.drop(1)
        }
        return RouteService.Route(10.minutes, 500.0, full, steps.toList())
    }

    @Test
    fun `transit line colours parse, fall back on blank and malformed`() {
        val tokens = mapTokens(isDark = false)
        val valid = buildRouteOverlay(routeOf(transitStep("#FF8800")), null, tokens)
        assertNotNull(valid)
        assertEquals(Color(0xFFFF8800), valid.segments.first().color)
        val blank = buildRouteOverlay(routeOf(transitStep("")), null, tokens)
        assertEquals(tokens.routeTransitFallback, blank?.segments?.first()?.color)
        val malformed = buildRouteOverlay(routeOf(transitStep("not-a-color")), null, tokens)
        assertEquals(tokens.routeTransitFallback, malformed?.segments?.first()?.color)
        val noDetails = buildRouteOverlay(
            routeOf(transitStep(null).copy(transitDetails = null)), null, tokens,
        )
        assertEquals(tokens.routeTransitFallback, noDetails?.segments?.first()?.color)
    }

    @Test
    fun `drive ratios and walk colour through the overlay builder`() {
        val tokens = mapTokens(isDark = true)
        val jam = buildRouteOverlay(routeOf(driveStep(0.2)), null, tokens)
        assertEquals(tokens.traffic.jam, jam?.segments?.first()?.color)
        val slow = buildRouteOverlay(routeOf(driveStep(0.7)), null, tokens)
        assertEquals(tokens.traffic.slow, slow?.segments?.first()?.color)
        val free = buildRouteOverlay(routeOf(driveStep(1.0)), null, tokens)
        assertEquals(tokens.traffic.free, free?.segments?.first()?.color)
        val walk = buildRouteOverlay(
            routeOf(driveStep(1.0).copy(travelMode = RouteService.TravelMode.WALK)),
            null, tokens,
        )
        assertEquals(tokens.routeInert, walk?.segments?.first()?.color)
        // a route whose steps have no drawable polyline builds no overlay
        val empty = buildRouteOverlay(
            routeOf(driveStep(1.0).copy(polyline = listOf(GeoPoint(0.0, 0.0)))),
            null, tokens,
        )
        assertNull(empty)
    }

    @Test
    fun `navigating overlay greys the travelled portion`() {
        val tokens = mapTokens(isDark = false)
        val route = routeOf(driveStep(1.0), driveStep(1.0), driveStep(1.0))
        val progress = NavigationProgress(
            snappedPosition = GeoPoint(-122.35, 37.75),
            segmentIndex = 1,
            currentStepIndex = 1,
            distanceAlongRoute = 150.0,
            distanceRemaining = 350.0,
            distanceToNextManeuver = 50.0,
            fractionComplete = 0.3,
            etaEpochMs = 0L,
            courseOverGround = 0f,
            distanceOffRoute = 0.0,
        )
        val overlay = buildRouteOverlay(route, progress, tokens)
        assertNotNull(overlay)
        assertTrue(overlay.segments.isNotEmpty())
        assertEquals(tokens.traffic.traveled, overlay.segments.first().color)
        assertEquals(tokens.traffic.free, overlay.segments.last().color)
    }
}
