package com.vayunmathur.maps.util

import com.vayunmathur.maps.util.RouteService.Route
import com.vayunmathur.maps.util.RouteService.Step
import com.vayunmathur.library.map.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-Kotlin geometry + progress engine for navigation.
 *
 * All distances are in meters. Coordinates are lon-first per the [GeoPoint]
 * convention.
 *
 * There is no Android dependency here so the file is trivially unit-testable
 * and is shared between the foreground service ([NavigationSessionManager])
 * and any UI that needs to derive per-step progress.
 */

private const val EARTH_RADIUS_M = 6_371_008.8
private const val DEG_TO_RAD = PI / 180.0
/** Windowed-snap miss threshold (m): fall back to a full scan beyond this. */
private const val WINDOW_FALLBACK_M = 200.0

/**
 * Cheap equirectangular distance approximation. Plenty accurate for the
 * route-snapping workload (a few thousand vertices at <= ~tens of km,
 * sampled at 1 Hz). Avoids the trig overhead of full haversine.
 */
fun equirectangularDistance(a: GeoPoint, b: GeoPoint): Double {
    val midLatRad = (a.latitude + b.latitude) * 0.5 * DEG_TO_RAD
    val dxMeters = (b.longitude - a.longitude) * DEG_TO_RAD * EARTH_RADIUS_M * cos(midLatRad)
    val dyMeters = (b.latitude - a.latitude) * DEG_TO_RAD * EARTH_RADIUS_M
    return sqrt(dxMeters * dxMeters + dyMeters * dyMeters)
}

/** Project [p] onto segment [a]-[b] in local-meters tangent plane. */
fun projectOntoSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): SegmentProjection {
    // Use a tangent plane centered on segment midpoint.
    val midLatRad = (a.latitude + b.latitude) * 0.5 * DEG_TO_RAD
    val mPerLon = DEG_TO_RAD * EARTH_RADIUS_M * cos(midLatRad)
    val mPerLat = DEG_TO_RAD * EARTH_RADIUS_M

    val ax = 0.0
    val ay = 0.0
    val bx = (b.longitude - a.longitude) * mPerLon
    val by = (b.latitude - a.latitude) * mPerLat
    val px = (p.longitude - a.longitude) * mPerLon
    val py = (p.latitude - a.latitude) * mPerLat

    val segLenSq = bx * bx + by * by
    val fraction = if (segLenSq <= 0.0) 0.0
    else ((px - ax) * bx + (py - ay) * by) / segLenSq
    val clamped = fraction.coerceIn(0.0, 1.0)

    val projX = ax + clamped * bx
    val projY = ay + clamped * by
    val dx = px - projX
    val dy = py - projY
    val perpDistMeters = sqrt(dx * dx + dy * dy)

    val projLon = a.longitude + (projX / mPerLon)
    val projLat = a.latitude + (projY / mPerLat)
    return SegmentProjection(
        fraction = clamped,
        projectedPosition = GeoPoint(projLon, projLat),
        perpendicularDistance = perpDistMeters
    )
}

/** Immutable value describing segment projection. */
data class SegmentProjection(
    val fraction: Double,
    val projectedPosition: GeoPoint,
    val perpendicularDistance: Double,
)

/**
 * Precomputed prefix sums and per-step index ranges for a [Route]. Built once
 * per route so the per-GPS-fix snap is O(window) instead of O(full route).
 */
class PolylineIndex(val route: Route) {
    /** Number of vertices in the full route polyline. */
    val vertexCount: Int = route.polyline.size

    /** Number of segments = vertexCount - 1 (or 0 for degenerate routes). */
    val segmentCount: Int = max(0, vertexCount - 1)

    /** Cumulative distance to the start of each vertex; [0] = 0.0. */
    val cumulativeDistances: DoubleArray = DoubleArray(vertexCount)

    /** Length of each segment (segment i goes from polyline[i] to polyline[i+1]). */
    private val segmentLengths: DoubleArray = DoubleArray(segmentCount)

    /** For each [Step], the vertex range it occupies in the full polyline. */
    val stepRanges: List<IntRange>

    /** Total route distance in meters. */
    val totalDistance: Double
        get() = if (vertexCount > 0) cumulativeDistances.last() else 0.0

    init {
        for (i in 0 until segmentCount) {
            val len = equirectangularDistance(route.polyline[i], route.polyline[i + 1])
            segmentLengths[i] = len
            cumulativeDistances[i + 1] = cumulativeDistances[i] + len
        }

        // Match each step's polyline back to the full polyline by walking
        // forward — the existing route builder concatenates steps end-to-end
        // and deduplicates shared endpoints (see OfflineRouter.getRoute).
        //
        // INVARIANT: route.polyline MUST be the concatenation of
        // step[i].polyline with shared endpoints deduplicated. OfflineRouter
        // produces this (including getRouteMulti) so the index ranges below
        // are valid for all offline routes.
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        for (step in route.step) {
            val stepLen = step.polyline.size
            if (stepLen == 0) {
                ranges.add(cursor..cursor)
                continue
            }
            val first = cursor
            val last = min(vertexCount - 1, cursor + stepLen - 1)
            ranges.add(first..last)
            // The next step's first vertex is the previous step's last vertex
            // (shared join point), so advance by stepLen - 1.
            cursor = last
        }
        stepRanges = ranges
    }

    /**
     * Snap [position] onto the route. [lastSegmentIndex] biases the search to
     * the segments around the last known snap so we don't accidentally jump
     * back to a closer-but-stale earlier segment when the route doubles back
     * or loops. Falls back to a full search if the windowed search produces
     * a clearly-bad result (perpendicular distance > 200 m AND the window
     * didn't reach a boundary).
     */
    fun snap(
        position: GeoPoint,
        lastSegmentIndex: Int = 0,
        windowRadius: Int = 30,
    ): SnapResult {
        if (segmentCount == 0) {
            return SnapResult(
                segmentIndex = 0,
                fraction = 0.0,
                snappedPosition = route.polyline.firstOrNull() ?: position,
                distanceOffRoute = 0.0,
                distanceAlongRoute = 0.0,
            )
        }
        val start = max(0, lastSegmentIndex - windowRadius)
        val end = min(segmentCount - 1, lastSegmentIndex + windowRadius)
        val windowed = bestSnapInRange(position, start, end)
        val touchesBoundary = (start == 0) || (end == segmentCount - 1)
        return if (!touchesBoundary && windowed.distanceOffRoute > WINDOW_FALLBACK_M) {
            // Window probably missed the actual nearest segment (long
            // tunnel, big U-turn, GPS jump). Fall back to a full scan.
            bestSnapInRange(position, 0, segmentCount - 1)
        } else {
            windowed
        }
    }

    private fun bestSnapInRange(position: GeoPoint, from: Int, to: Int): SnapResult {
        var bestIdx = from
        var bestProj = projectOntoSegment(position, route.polyline[from], route.polyline[from + 1])
        for (i in (from + 1)..to) {
            val proj = projectOntoSegment(position, route.polyline[i], route.polyline[i + 1])
            if (proj.perpendicularDistance < bestProj.perpendicularDistance) {
                bestIdx = i
                bestProj = proj
            }
        }
        val along = cumulativeDistances[bestIdx] + bestProj.fraction * segmentLengths[bestIdx]
        return SnapResult(
            segmentIndex = bestIdx,
            fraction = bestProj.fraction,
            snappedPosition = bestProj.projectedPosition,
            distanceOffRoute = bestProj.perpendicularDistance,
            distanceAlongRoute = along,
        )
    }

    /**
     * Find the index of the [Step] that the given **segment** belongs to.
     *
     * Care is required: [stepRanges] are *closed vertex ranges with shared
     * endpoints* (step i ends at the same vertex step i+1 starts on). A
     * segment with index `s` runs from vertex `s` to vertex `s + 1`, so:
     *  - step `i` (for i < last step) owns segments `range.first .. range.last - 1`
     *  - the last step owns segments `range.first .. range.last`
     *
     * If we instead asked "which vertex range contains segment s", the shared
     * boundary vertex would attribute the first segment of every step to the
     * previous step — producing a one-segment lag on every step transition
     * (maneuver card reads "0 m, turn left" while the user is already on the
     * new road, TTS step-transition cue fires late, etc.).
     */
    fun stepIndexForSegment(segmentIndex: Int): Int {
        for ((i, range) in stepRanges.withIndex()) {
            val ownedLast = if (i == stepRanges.lastIndex) range.last else range.last - 1
            if (segmentIndex <= ownedLast) return i
        }
        return stepRanges.lastIndex.coerceAtLeast(0)
    }

    /**
     * Distance from the start of the route to the start of step [stepIndex]
     * (i.e. cumulative distance to that step's first vertex).
     */
    fun cumulativeDistanceToStepStart(stepIndex: Int): Double {
        val range = stepRanges.getOrNull(stepIndex) ?: return totalDistance
        return cumulativeDistances.getOrNull(range.first) ?: totalDistance
    }

    /**
     * Distance from start of route to the END of step [stepIndex] (= start
     * of step + step.distanceMeters approximation via the index ranges).
     */
    fun cumulativeDistanceToStepEnd(stepIndex: Int): Double {
        val range = stepRanges.getOrNull(stepIndex) ?: return totalDistance
        return cumulativeDistances.getOrNull(range.last) ?: totalDistance
    }
}

/** Single per-fix snap result. */
data class SnapResult(
    val segmentIndex: Int,
    val fraction: Double,
    val snappedPosition: GeoPoint,
    val distanceOffRoute: Double,
    val distanceAlongRoute: Double,
)

/**
 * Everything a UI / notification needs to render the current state of an
 * in-progress navigation session.
 */
data class NavigationProgress(
    val snappedPosition: GeoPoint,
    val segmentIndex: Int,
    val currentStepIndex: Int,
    val distanceAlongRoute: Double,
    val distanceRemaining: Double,
    val distanceToNextManeuver: Double,
    val fractionComplete: Double,
    /** ETA as Unix epoch milliseconds. */
    val etaEpochMs: Long,
    val courseOverGround: Float,
    val distanceOffRoute: Double,
    /** Current GPS ground speed in meters/second (0 when unknown/stationary). */
    val speedMps: Float = 0f,
)

/**
 * Build a [NavigationProgress] from a [SnapResult] and the static [Route].
 * [courseOverGround] is the bearing the camera should face (GPS course when
 * moving, compass when stationary — picked upstream).
 */
fun PolylineIndex.deriveProgress(
    snap: SnapResult,
    courseOverGround: Float,
    nowEpochMs: Long = System.currentTimeMillis(),
    speedMps: Float = 0f,
): NavigationProgress {
    val currentStepIndex = stepIndexForSegment(snap.segmentIndex)
    val nextStepStart = if (currentStepIndex + 1 < route.step.size) {
        cumulativeDistanceToStepStart(currentStepIndex + 1)
    } else {
        totalDistance
    }
    val distanceToNextManeuver = (nextStepStart - snap.distanceAlongRoute).coerceAtLeast(0.0)
    val distanceRemaining = (totalDistance - snap.distanceAlongRoute).coerceAtLeast(0.0)
    val fractionComplete = if (totalDistance > 0.0) {
        (snap.distanceAlongRoute / totalDistance).coerceIn(0.0, 1.0)
    } else {
        0.0
    }
    // ETA from the remaining fraction of the route's static duration. Cheap
    // approximation that doesn't require traffic data here.
    val remainingDurationMs = if (totalDistance > 0.0) {
        (route.duration.inWholeMilliseconds * (1.0 - fractionComplete)).toLong()
    } else {
        0L
    }
    return NavigationProgress(
        snappedPosition = snap.snappedPosition,
        segmentIndex = snap.segmentIndex,
        currentStepIndex = currentStepIndex,
        distanceAlongRoute = snap.distanceAlongRoute,
        distanceRemaining = distanceRemaining,
        distanceToNextManeuver = distanceToNextManeuver,
        fractionComplete = fractionComplete,
        etaEpochMs = nowEpochMs + remainingDurationMs,
        courseOverGround = courseOverGround,
        distanceOffRoute = snap.distanceOffRoute,
        speedMps = speedMps,
    )
}
