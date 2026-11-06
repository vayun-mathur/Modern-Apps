package com.vayunmathur.maps.ui.map

import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.ui.theme.mapTokens
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Slice 1/3 (maps): [buildRouteOverlay] — the mode-aware colouring that turns
 * a [RouteService.Route] into the renderer's segment list.
 *
 * Plain JVM (not Robolectric): colours are compared as [Color] values, and
 * the only Android call ([android.graphics.Color.parseColor]) sits behind
 * `returnDefaultValues`, which still distinguishes valid from invalid —
 * invalid parses to transparent black here, never to a real feed colour.
 */
class RouteOverlayBuilderSlice1Test {

    private val tokens = mapTokens(isDark = false)

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun step(
        vararg pts: GeoPoint,
        mode: RouteService.TravelMode = RouteService.TravelMode.DRIVE,
        speedRatio: Double = 1.0,
        lineColor: String? = null,
    ) = RouteService.Step(
        distanceMeters = 100.0,
        staticDuration = 1.minutes,
        polyline = pts.toList(),
        navInstruction = RouteService.API.NavInstruction(),
        travelMode = mode,
        transitDetails = lineColor?.let {
            RouteService.API.TransitDetails(
                headsign = "Downtown",
                stopCount = 3,
                transitLine = RouteService.API.TransitLine("N", "N", it),
                stopDetails = RouteService.API.StopDetails(
                    "17:00", "17:10",
                    RouteService.API.Stop("A"), RouteService.API.Stop("B"),
                ),
            )
        },
        speedRatio = speedRatio,
    )

    private fun route(vararg steps: RouteService.Step): RouteService.Route {
        val full = steps.flatMapIndexed { i, s ->
            // Steps share endpoints, like PolylineIndex's cursor walk.
            if (i == 0) s.polyline else s.polyline.drop(1)
        }
        return RouteService.Route(10.minutes, 500.0, full, steps.toList())
    }

    private val a = pt(-122.40, 37.70)
    private val b = pt(-122.41, 37.71)
    private val c = pt(-122.42, 37.72)
    private val d = pt(-122.43, 37.73)
    private val e = pt(-122.44, 37.74)

    @Test
    fun `driving colours follow the congestion ramp`() {
        val overlay = assertNotNull(
            route(
                step(a, b, speedRatio = 0.2),
                step(b, c, speedRatio = 0.7),
                step(c, d, speedRatio = 1.0),
            ).let { buildRouteOverlay(it, null, tokens) },
        )
        assertEquals(
            listOf(tokens.traffic.jam, tokens.traffic.slow, tokens.traffic.free),
            overlay.segments.map { it.color },
        )
    }

    @Test
    fun `walk and bike share the inert colour`() {
        val overlay = assertNotNull(
            route(
                step(a, b, mode = RouteService.TravelMode.WALK),
                step(b, c, mode = RouteService.TravelMode.BICYCLE),
            ).let { buildRouteOverlay(it, null, tokens) },
        )
        assertTrue(overlay.segments.all { it.color == tokens.routeInert })
    }

    @Test
    fun `a missing or malformed transit colour falls back`() {
        val overlay = assertNotNull(
            route(
                step(a, b, mode = RouteService.TravelMode.TRANSIT, lineColor = ""),
                step(b, c, mode = RouteService.TravelMode.TRANSIT),
            ).let { buildRouteOverlay(it, null, tokens) },
        )
        assertTrue(overlay.segments.all { it.color == tokens.routeTransitFallback })
    }

    @Test
    fun `degenerate steps are skipped and an empty route is null`() {
        assertNull(buildRouteOverlay(route(), null, tokens))
        assertNull(
            buildRouteOverlay(
                route(step(a, mode = RouteService.TravelMode.DRIVE)),
                null, tokens,
            ),
        )
    }

    @Test
    fun `navigation splits the active step at the snap point`() {
        val r = route(step(a, b, c), step(c, d, e))
        val progress = NavigationProgress(
            snappedPosition = pt(-122.415, 37.715),
            segmentIndex = 1,
            currentStepIndex = 0,
            distanceAlongRoute = 150.0,
            distanceRemaining = 400.0,
            distanceToNextManeuver = 50.0,
            fractionComplete = 0.25,
            etaEpochMs = 0L,
            courseOverGround = 45f,
            distanceOffRoute = 0.0,
        )
        val overlay = assertNotNull(buildRouteOverlay(r, progress, tokens))
        // Active step 0 splits (gray behind, mode colour ahead); step 1 stays mode.
        assertEquals(3, overlay.segments.size)
        assertEquals(tokens.traffic.traveled, overlay.segments[0].color)
        assertEquals(tokens.traffic.free, overlay.segments[1].color)
        assertEquals(tokens.traffic.free, overlay.segments[2].color)
        // The gray ends exactly under the user.
        assertEquals(progress.snappedPosition, overlay.segments[0].points.last())
        assertEquals(progress.snappedPosition, overlay.segments[1].points.first())
    }

    @Test
    fun `steps behind the current one draw travelled`() {
        val r = route(step(a, b), step(b, c), step(c, d))
        val progress = NavigationProgress(
            snappedPosition = b,
            segmentIndex = 1,
            currentStepIndex = 2,
            distanceAlongRoute = 250.0,
            distanceRemaining = 100.0,
            distanceToNextManeuver = 10.0,
            fractionComplete = 0.7,
            etaEpochMs = 0L,
            courseOverGround = 0f,
            distanceOffRoute = 0.0,
        )
        val overlay = assertNotNull(buildRouteOverlay(r, progress, tokens))
        assertEquals(tokens.traffic.traveled, overlay.segments[0].color)
        assertEquals(tokens.traffic.traveled, overlay.segments[1].color)
    }
}
