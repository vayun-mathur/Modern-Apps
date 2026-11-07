package com.vayunmathur.maps.util

import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Slice 3/3 (root package files M-Z): the pure-Kotlin geometry + progress
 * engine in `PolylineProgress.kt` shared by [NavigationSessionManager] and
 * the nav UI.
 *
 * No Android dependency: distances, segment projection, the per-route index
 * (prefix sums, step ranges, windowed snap with full-scan fallback) and the
 * [deriveProgress] summary are all pinned against hand-computed values.
 */
class PolylineProgressSlice3Test {

    // GeoPoint is lon-first (GeoJSON order).
    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun step(vararg pts: GeoPoint) = RouteService.Step(
        distanceMeters = 0.0,
        staticDuration = 60.seconds,
        polyline = pts.toList(),
        navInstruction = RouteService.API.NavInstruction(),
        travelMode = RouteService.TravelMode.DRIVE,
    )

    private fun route(vararg pts: GeoPoint, steps: List<RouteService.Step>? = null): RouteService.Route {
        val poly = pts.toList()
        return RouteService.Route(
            duration = 10.minutes,
            distanceMeters = 0.0,
            polyline = poly,
            step = steps ?: listOf(step(*pts)),
        )
    }

    @Test
    fun `same point has zero distance`() {
        assertEquals(0.0, equirectangularDistance(pt(0.0, 0.0), pt(0.0, 0.0)))
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val d = equirectangularDistance(pt(0.0, 0.0), pt(0.0, 1.0))
        assertTrue(d > 111_000.0 && d < 111_400.0, "got $d")
    }

    @Test
    fun `one degree of longitude shrinks with latitude`() {
        val equator = equirectangularDistance(pt(0.0, 0.0), pt(1.0, 0.0))
        val high = equirectangularDistance(pt(0.0, 60.0), pt(1.0, 60.0))
        assertTrue(equator > 111_000.0, "got $equator")
        // cos(60°) = 0.5.
        assertTrue(high > 55_000.0 && high < 56_000.0, "got $high")
    }

    @Test
    fun `mid-segment projection splits the fraction and has no offset`() {
        val proj = projectOntoSegment(pt(0.5, 0.0), pt(0.0, 0.0), pt(1.0, 0.0))
        assertTrue(proj.fraction > 0.49 && proj.fraction < 0.51, "got ${proj.fraction}")
        assertTrue(proj.perpendicularDistance < 1.0, "got ${proj.perpendicularDistance}")
    }

    @Test
    fun `projection clamps past the ends`() {
        val before = projectOntoSegment(pt(-1.0, 0.0), pt(0.0, 0.0), pt(1.0, 0.0))
        assertEquals(0.0, before.fraction)
        val after = projectOntoSegment(pt(2.0, 0.0), pt(0.0, 0.0), pt(1.0, 0.0))
        assertEquals(1.0, after.fraction)
    }

    @Test
    fun `degenerate zero-length segment projects at zero`() {
        val proj = projectOntoSegment(pt(1.0, 1.0), pt(0.0, 0.0), pt(0.0, 0.0))
        assertEquals(0.0, proj.fraction)
        assertTrue(proj.perpendicularDistance > 100_000.0)
    }

    @Test
    fun `index counts vertices and segments`() {
        val index = PolylineIndex(route(pt(0.0, 0.0), pt(0.0, 1.0), pt(0.0, 2.0)))
        assertEquals(3, index.vertexCount)
        assertEquals(2, index.segmentCount)
        assertTrue(index.totalDistance > 222_000.0, "got ${index.totalDistance}")
    }

    @Test
    fun `degenerate single-point route has no segments`() {
        val index = PolylineIndex(route(pt(1.0, 2.0)))
        assertEquals(1, index.vertexCount)
        assertEquals(0, index.segmentCount)
        assertEquals(0.0, index.totalDistance)
    }

    @Test
    fun `degenerate snap returns the only point with zero progress`() {
        val only = pt(1.0, 2.0)
        val index = PolylineIndex(route(only))
        val snap = index.snap(pt(9.0, 9.0))
        assertEquals(0, snap.segmentIndex)
        assertEquals(only, snap.snappedPosition)
        assertEquals(0.0, snap.distanceAlongRoute)
    }

    @Test
    fun `snap finds the nearest segment and distance along route`() {
        val index = PolylineIndex(route(pt(0.0, 0.0), pt(0.0, 1.0), pt(0.0, 2.0)))
        // Just east of the midpoint of the second segment.
        val snap = index.snap(pt(0.001, 1.5))
        assertEquals(1, snap.segmentIndex)
        val half = index.totalDistance / 2.0
        assertTrue(snap.distanceAlongRoute > half - 100.0, "got ${snap.distanceAlongRoute}")
        assertTrue(snap.distanceOffRoute < 500.0, "got ${snap.distanceOffRoute}")
    }

    @Test
    fun `snap far outside the window falls back to a full scan`() {
        val pts = (0..100).map { pt(0.0, it * 0.001) }.toTypedArray()
        val index = PolylineIndex(route(*pts))
        // Windowed mid-route with a tiny radius, but the point sits at the
        // far end: the windowed best is kilometres off and the window touches
        // neither route boundary, so it rescans fully.
        val snap = index.snap(pt(0.0, 0.1), lastSegmentIndex = 50, windowRadius = 2)
        assertEquals(99, snap.segmentIndex)
    }

    @Test
    fun `snap in a boundary-touching window keeps the windowed best`() {
        val pts = (0..100).map { pt(0.0, it * 0.001) }.toTypedArray()
        val index = PolylineIndex(route(*pts))
        // Same far point, but the window is pinned at the route start: it
        // touches the boundary, so no rescan — the nearest windowed segment.
        val snap = index.snap(pt(0.0, 0.1), lastSegmentIndex = 0, windowRadius = 2)
        assertEquals(2, snap.segmentIndex)
    }

    @Test
    fun `shared step boundary attributes the join segment to the next step`() {
        val a = pt(0.0, 0.0)
        val b = pt(0.0, 1.0)
        val c = pt(0.0, 2.0)
        val index = PolylineIndex(
            route(a, b, c, steps = listOf(step(a, b), step(b, c))),
        )
        assertEquals(listOf(0..1, 1..2), index.stepRanges)
        // Segment 0 belongs to step 0; segment 1 (starting on the shared
        // vertex) belongs to step 1 — no one-segment lag at the transition.
        assertEquals(0, index.stepIndexForSegment(0))
        assertEquals(1, index.stepIndexForSegment(1))
    }

    @Test
    fun `empty step polyline collapses to a point range`() {
        val a = pt(0.0, 0.0)
        val b = pt(0.0, 1.0)
        val index = PolylineIndex(
            route(a, b, steps = listOf(step(), step(a, b))),
        )
        assertEquals(0..0, index.stepRanges[0])
        assertEquals(0..1, index.stepRanges[1])
        // The empty step owns no segment: the only segment belongs to the
        // step that actually carries geometry.
        assertEquals(1, index.stepIndexForSegment(0))
    }

    @Test
    fun `step start and end distances bracket the route`() {
        val a = pt(0.0, 0.0)
        val b = pt(0.0, 1.0)
        val c = pt(0.0, 2.0)
        val index = PolylineIndex(
            route(a, b, c, steps = listOf(step(a, b), step(b, c))),
        )
        assertEquals(0.0, index.cumulativeDistanceToStepStart(0))
        assertEquals(index.totalDistance, index.cumulativeDistanceToStepEnd(1))
        assertEquals(index.totalDistance, index.cumulativeDistanceToStepStart(99))
    }

    @Test
    fun `deriveProgress reports remaining distance and ETA`() {
        val index = PolylineIndex(route(pt(0.0, 0.0), pt(0.0, 1.0)))
        val snap = index.snap(pt(0.0, 0.5))
        val progress = index.deriveProgress(snap, courseOverGround = 90f, nowEpochMs = 1_000_000L)
        assertEquals(0, progress.currentStepIndex)
        assertTrue(progress.distanceRemaining > 50_000.0, "got ${progress.distanceRemaining}")
        // Halfway through a 10-minute route: ~5 minutes remain.
        val remaining = progress.etaEpochMs - 1_000_000L
        assertTrue(remaining > 4.minutes.inWholeMilliseconds, "got $remaining")
        assertTrue(progress.fractionComplete > 0.4 && progress.fractionComplete < 0.6)
        assertEquals(90f, progress.courseOverGround)
    }

    @Test
    fun `deriveProgress at the destination completes the route`() {
        val index = PolylineIndex(route(pt(0.0, 0.0), pt(0.0, 1.0)))
        val snap = index.snap(pt(0.0, 1.0))
        val progress = index.deriveProgress(snap, courseOverGround = 0f, nowEpochMs = 0L)
        assertEquals(0.0, progress.distanceRemaining)
        assertEquals(1.0, progress.fractionComplete)
        assertEquals(0.0, progress.distanceToNextManeuver)
    }
}
