package com.vayunmathur.maps.util

import com.vayunmathur.library.map.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Diversity gating for alternative routes.
 *
 * Audit (2026-11): the native road router (`findRouteNative`) and the
 * RAPTOR transit planner each return exactly **one** route per mode, so
 * the only "alternatives" the sheet showed were the four travel modes —
 * there was no within-mode candidate list and no duplicate filtering at
 * all. Candidates are now generated on top of those single-route
 * planners (a via-detour second road plan; a later-departure second
 * RAPTOR plan) and every candidate must pass this gate before it is
 * shown. If nothing passes, exactly one route — the primary — is shown.
 *
 * Thresholds, tuned to what the planners actually produce:
 * - [MAX_SHARED_GEOMETRY_RATIO]: road alternatives that share 70% or
 *   more of the primary's geometry (by length) are the same corridor
 *   with a one-street swap, not an alternative.
 * - [MAX_WALK_SHARE]: a transit journey that is more than 45% walking
 *   by distance is a walk with a ride in it, not a transit alternative.
 * - Transit legs are compared by **signature** (the boarded line +
 *   board/alight stops of each ride leg): an alternative whose leg
 *   sequence equals, or is a subset/truncation of, an already-shown
 *   journey's is the same ride taken less far (early alight + longer
 *   walk), however it is re-padded.
 * - Extra transfers are a cost: a transit candidate with more
 *   transfers than a shown journey is rejected when it re-covers
 *   that journey's corridor/stops (served-stop overlap >= 0.67 or
 *   shared geometry >= 0.70 — the early-alight + parallel-bus
 *   rejoin), and otherwise must arrive at least 5 minutes earlier
 *   or use a genuinely different corridor (both overlaps < 0.5).
 *
 * The primary is always kept, and candidates keep their input order
 * (fastest first as generated), so ETA ordering among shown routes
 * and the primary choice are never degraded by gating.
 */
object AlternativeRoutes {

    /** Above this shared-geometry ratio a road candidate is a near-duplicate. */
    const val MAX_SHARED_GEOMETRY_RATIO = 0.70

    /** Above this walking share a transit candidate is not a transit route. */
    const val MAX_WALK_SHARE = 0.45

    /**
     * Minimum arrival saving that can pay for an extra transfer: 5 whole
     * minutes of journey duration. Feed headways on the corridors the pack
     * covers are typically 6-15 minutes, so a saving smaller than one
     * headway step is timetable noise, not a reason to change vehicles.
     */
    const val EXTRA_TRANSFER_MIN_SAVING_MINUTES = 5

    /**
     * At/above this served-stop overlap an added transfer merely re-covers
     * the corridor (shared origin/destination alone reach only 0.5 against
     * a two-stop primary, so the bar sits at two-thirds).
     */
    const val RE_COVER_STOP_OVERLAP = 0.67

    /** Below both overlaps a corridor counts as genuinely different. */
    const val DIFFERENT_CORRIDOR_MAX_OVERLAP = 0.5

    /** Geometry match tolerance (m) for the shared-length measurement. */
    private const val MATCH_TOLERANCE_M = 30.0

    /** One boarded ride of a transit journey, for signature comparison. */
    data class TransitLegKey(val line: String, val board: String, val alight: String)

    /** The boarded-service sequence of [route]; empty for non-transit routes. */
    fun transitSignature(route: RouteService.Route): List<TransitLegKey> =
        route.step.mapNotNull { step ->
            val details = step.transitDetails ?: return@mapNotNull null
            TransitLegKey(
                line = details.transitLine.nameShort ?: details.transitLine.name,
                board = details.stopDetails.departureStop.name,
                alight = details.stopDetails.arrivalStop.name,
            )
        }

    /** Walking share of [route] by distance (0 when the route is empty). */
    fun walkShare(route: RouteService.Route): Double {
        if (route.distanceMeters <= 0.0) return 0.0
        val walk = route.step
            .filter { it.travelMode == RouteService.TravelMode.WALK }
            .sumOf { it.distanceMeters }
        return walk / route.distanceMeters
    }

    /**
     * Shared length of [candidate] with [primary], as a ratio of the
     * primary's length: each candidate segment whose midpoint lies within
     * [MATCH_TOLERANCE_M] of the primary's polyline counts as shared.
     */
    fun sharedGeometryRatio(primary: RouteService.Route, candidate: RouteService.Route): Double {
        val primaryLength = polylineLength(primary.polyline)
        if (primaryLength <= 0.0 || candidate.polyline.size < 2) return 0.0
        var shared = 0.0
        for (i in 0 until candidate.polyline.size - 1) {
            val a = candidate.polyline[i]
            val b = candidate.polyline[i + 1]
            val mid = GeoPoint((a.longitude + b.longitude) / 2, (a.latitude + b.latitude) / 2)
            if (distanceToPolylineM(mid, primary.polyline) <= MATCH_TOLERANCE_M) {
                shared += haversineM(a, b)
            }
        }
        return min(1.0, shared / primaryLength)
    }

    /**
     * The genuinely different alternatives to [primary] among
     * [candidates], in input order. [transit] selects the transit gates
     * (signature + walk share); otherwise the corridor-overlap gate
     * applies, which also covers walk/cycle candidates.
     */
    fun diverseAlternatives(
        primary: RouteService.Route,
        candidates: List<RouteService.Route>,
        transit: Boolean,
    ): List<RouteService.Route> {
        val accepted = mutableListOf(primary)
        val out = mutableListOf<RouteService.Route>()
        for (candidate in candidates) {
            if (candidate === primary) continue
            val passes = if (transit) {
                transitPasses(candidate, accepted)
            } else {
                accepted.all { sharedGeometryRatio(it, candidate) < MAX_SHARED_GEOMETRY_RATIO }
            }
            if (passes) {
                accepted.add(candidate)
                out.add(candidate)
            }
        }
        return out
    }

    private fun transitPasses(
        candidate: RouteService.Route,
        accepted: List<RouteService.Route>,
    ): Boolean {
        val signature = transitSignature(candidate)
        if (signature.isEmpty()) return false
        if (walkShare(candidate) > MAX_WALK_SHARE) return false
        return accepted.none { other ->
            val otherSignature = transitSignature(other)
            // Same boarded-service sequence is the same ride, however the
            // stops are re-cut (early alight, board one stop later): the
            // leg-key subset check below catches truncations, and this
            // line-sequence check catches the same services with
            // different board/alight stops.
            val lines = signature.map { it.line }
            val otherLines = otherSignature.map { it.line }
            if (otherSignature == signature || isSubsetOrTruncation(signature, otherSignature) ||
                lines == otherLines || lines.toSet().all { it in otherLines.toSet() }
            ) {
                return@none true
            }
            // Transfer count is a cost, not a difference. A candidate with
            // more transfers than an accepted journey must either arrive
            // meaningfully earlier (>= EXTRA_TRANSFER_MIN_SAVING_MINUTES of
            // duration) or be a genuinely different corridor — and one that
            // re-covers the accepted corridor/stop sequence via an added
            // transfer (early alight, parallel bus, rejoin) is neither,
            // however its service sequence is dressed up.
            val extraTransfers = signature.size > otherSignature.size
            if (!extraTransfers) return@none false
            val stopOverlap = servedStopOverlap(signature, otherSignature)
            val geometryOverlap = sharedGeometryRatio(other, candidate)
            val reCovers = stopOverlap >= RE_COVER_STOP_OVERLAP ||
                geometryOverlap >= MAX_SHARED_GEOMETRY_RATIO
            if (reCovers) return@none true
            val savingMinutes = (other.duration - candidate.duration).inWholeMinutes
            val corridorDifferent = stopOverlap < DIFFERENT_CORRIDOR_MAX_OVERLAP &&
                geometryOverlap < DIFFERENT_CORRIDOR_MAX_OVERLAP
            savingMinutes < EXTRA_TRANSFER_MIN_SAVING_MINUTES && !corridorDifferent
        }
    }

    /** Board+alight stops a signature serves, as a set. */
    private fun servedStops(signature: List<TransitLegKey>): Set<String> =
        signature.flatMapTo(mutableSetOf()) { listOf(it.board, it.alight) }

    /** Shared served stops over the smaller journey's stop count. */
    private fun servedStopOverlap(a: List<TransitLegKey>, b: List<TransitLegKey>): Double {
        val aStops = servedStops(a)
        val bStops = servedStops(b)
        val smaller = minOf(aStops.size, bStops.size)
        if (smaller == 0) return 0.0
        return aStops.intersect(bStops).size.toDouble() / smaller
    }

    /** True when [a]'s leg set is contained in [b]'s, or [a] is a prefix of [b]. */
    private fun isSubsetOrTruncation(a: List<TransitLegKey>, b: List<TransitLegKey>): Boolean {
        if (a.size <= b.size && b.subList(0, a.size) == a) return true
        return a.toSet().all { it in b.toSet() }
    }

    private fun polylineLength(points: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) total += haversineM(points[i], points[i + 1])
        return total
    }

    private fun distanceToPolylineM(point: GeoPoint, line: List<GeoPoint>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            best = min(best, distanceToSegmentM(point, line[i], line[i + 1]))
        }
        return best
    }

    /** Point-to-segment distance in metres, equirectangular around [p]. */
    private fun distanceToSegmentM(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val kx = 111_320.0 * cos(Math.toRadians(p.latitude))
        val ky = 110_540.0
        val px = 0.0
        val py = 0.0
        val ax = (a.longitude - p.longitude) * kx
        val ay = (a.latitude - p.latitude) * ky
        val bx = (b.longitude - p.longitude) * kx
        val by = (b.latitude - p.latitude) * ky
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt((px - cx).pow(2) + (py - cy).pow(2))
    }

    private fun haversineM(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }
}
