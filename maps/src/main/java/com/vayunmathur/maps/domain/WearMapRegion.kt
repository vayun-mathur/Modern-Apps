package com.vayunmathur.maps.domain

import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapGeometryKind
import com.vayunmathur.library.map.MapGeometryResult
import kotlinx.serialization.encodeToString
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Pure region extraction for the watch snapshot (see [WearMapsProtocol]).
 *
 * Input is whatever vector geometry the phone has cheaply: raw lines
 * (roads/paths/water/buildings, in that priority) and raw POI points.
 * Extraction, in order: project to metres around the centre, clip to the
 * region square, Douglas-Peucker simplify, quantize, then enforce
 * [WearMapsProtocol.MAX_JSON_BYTES] by an honest degradation ladder
 * (coarser simplification, then dropping low-priority kinds, then fewer
 * points) — [WearMapSnapshot.truncated]/[WearMapSnapshot.degraded] say
 * exactly what was lost. Geometry is never invented: kinds the caller
 * supplied none of are named in `degraded` by the caller, not here.
 */

/** One unsimplified line in geographic coords, as a caller supplies it. */
data class WearRawLine(
    val kind: WearLineKind,
    val points: List<GeoPoint>,
    val closed: Boolean = false,
    val name: String = "",
)

/**
 * Basemap geometry from `:library:map` ([MapGeometryResult]) as raw
 * wear lines, in the result's own priority order. A feature's class is
 * not a display name, so [WearRawLine.name] stays empty.
 */
fun MapGeometryResult.toWearRawLines(): List<WearRawLine> = features.map { feature ->
    WearRawLine(
        kind = when (feature.kind) {
            MapGeometryKind.ROAD -> WearLineKind.ROAD
            MapGeometryKind.PATH -> WearLineKind.PATH
            MapGeometryKind.WATER -> WearLineKind.WATER
            MapGeometryKind.BUILDING -> WearLineKind.BUILDING
        },
        points = feature.points,
        closed = feature.closed,
    )
}

/**
 * The `degraded` names for basemap kinds this result could not source.
 *
 * `degraded` means **unavailable**, not **empty**: a kind whose archive
 * was read but which returned no features (an ocean or desert region)
 * is genuinely absent there and is *not* named. A kind is named only
 * when it sits in [MapGeometryResult.unsourcedKinds] — no planet file,
 * an unreadable one, or no native library. Roads and paths share one
 * source layer, so either unsourced names the group "roads".
 */
fun MapGeometryResult.wearMissingKinds(): List<String> = buildList {
    if (MapGeometryKind.ROAD in unsourcedKinds || MapGeometryKind.PATH in unsourcedKinds) {
        add("roads")
    }
    if (MapGeometryKind.WATER in unsourcedKinds) add("water")
    if (MapGeometryKind.BUILDING in unsourcedKinds) add("buildings")
}

/** One POI in geographic coords, as a caller supplies it. */
data class WearRawPoint(
    val type: Int,
    val name: String,
    val point: GeoPoint,
)

/** Keep priority when the byte cap bites: route/road survive longest. */
private val LINE_PRIORITY = listOf(
    WearLineKind.ROUTE, WearLineKind.ROAD, WearLineKind.PATH,
    WearLineKind.WATER, WearLineKind.BUILDING,
)

private data class MeterPt(val x: Double, val y: Double)

/** Douglas-Peucker simplification in metre space; endpoints always kept. */
fun simplifyWearLine(points: List<GeoPoint>, origin: GeoPoint, toleranceM: Double): List<GeoPoint> {
    if (points.size <= 2) return points
    val meter = points.map {
        MeterPt(
            wearMetersEast(origin.latitude, origin.longitude, it.longitude),
            wearMetersNorth(origin.latitude, it.latitude),
        )
    }
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    simplifyRange(meter, 0, points.lastIndex, toleranceM, keep)
    return points.filterIndexed { i, _ -> keep[i] }
}

private fun simplifyRange(pts: List<MeterPt>, first: Int, last: Int, tol: Double, keep: BooleanArray) {
    if (last <= first + 1) return
    var worst = -1
    var worstDist = tol
    for (i in first + 1 until last) {
        val d = pointSegmentDistance(pts[i], pts[first], pts[last])
        if (d > worstDist) {
            worstDist = d
            worst = i
        }
    }
    if (worst < 0) return
    keep[worst] = true
    simplifyRange(pts, first, worst, tol, keep)
    simplifyRange(pts, worst, last, tol, keep)
}

private fun pointSegmentDistance(p: MeterPt, a: MeterPt, b: MeterPt): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lenSq = dx * dx + dy * dy
    if (lenSq == 0.0) return hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0.0, 1.0)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/** Clip one open polyline (metre space) to the square `[-radius, radius]^2`, splitting runs. */
private fun clipOpenLine(pts: List<MeterPt>, radius: Double): List<List<MeterPt>> {
    val runs = mutableListOf<List<MeterPt>>()
    var current = mutableListOf<MeterPt>()
    fun flush() {
        if (current.size >= 2) runs += current
        current = mutableListOf()
    }
    for (i in 0 until pts.size - 1) {
        val clipped = clipSegment(pts[i], pts[i + 1], radius)
        if (clipped == null) {
            flush()
            continue
        }
        val (a, b) = clipped
        if (current.isEmpty() || current.last() != a) {
            flush()
            current += a
        }
        current += b
    }
    flush()
    return runs
}

/** Liang-Barsky segment/square clip; null when fully outside. */
private fun clipSegment(a: MeterPt, b: MeterPt, r: Double): Pair<MeterPt, MeterPt>? {
    var t0 = 0.0
    var t1 = 1.0
    val dx = b.x - a.x
    val dy = b.y - a.y
    // p/q for the four square edges.
    val edges = arrayOf(
        doubleArrayOf(-dx, a.x + r), doubleArrayOf(dx, r - a.x),
        doubleArrayOf(-dy, a.y + r), doubleArrayOf(dy, r - a.y),
    )
    for ((p, q) in edges) {
        if (p == 0.0) {
            if (q < 0) return null
            continue
        }
        val t = q / p
        if (p < 0) {
            if (t > t1) return null
            if (t > t0) t0 = t
        } else {
            if (t < t0) return null
            if (t < t1) t1 = t
        }
    }
    if (t0 > t1) return null
    return MeterPt(a.x + t0 * dx, a.y + t0 * dy) to MeterPt(a.x + t1 * dx, a.y + t1 * dy)
}

/** Quantized flat `[dx, dy, ...]` for metre points. */
private fun quantizeFlat(pts: List<MeterPt>): List<Int> =
    pts.flatMap { listOf(wearQuantize(it.x), wearQuantize(it.y)) }

private fun toMeterPts(points: List<GeoPoint>, originLat: Double, originLon: Double): List<MeterPt> =
    points.map {
        MeterPt(
            wearMetersEast(originLat, originLon, it.longitude),
            wearMetersNorth(originLat, it.latitude),
        )
    }

/**
 * Builds a byte-capped snapshot. [missingKinds] names geometry kinds the
 * caller could not source at all (honesty: unavailable, not merely
 * empty — see [wearMissingKinds]); they are merged into
 * [WearMapSnapshot.degraded] verbatim. [sourceTruncated] says the
 * geometry source itself capped what it returned, so the snapshot is
 * flagged truncated/"detail" even when it fits under the byte cap.
 */
fun buildWearMapSnapshot(
    center: GeoPoint,
    generatedAtMillis: Long,
    rawLines: List<WearRawLine>,
    rawPoints: List<WearRawPoint>,
    saved: List<WearSavedPlace> = emptyList(),
    userBearingDeg: Int = -1,
    missingKinds: List<String> = emptyList(),
    radiusM: Int = WearMapsProtocol.REGION_RADIUS_M,
    sourceTruncated: Boolean = false,
): WearMapSnapshot {
    val tolerances = listOf(4.0, 8.0, 16.0, 32.0)
    val pointCaps = listOf(80, 60, 40, 20)
    var droppedKinds = emptyList<WearLineKind>()
    for (step in tolerances.indices) {
        val snapshot = buildAt(
            center, generatedAtMillis, rawLines, rawPoints, saved,
            userBearingDeg, missingKinds, radiusM,
            toleranceM = tolerances[step], pointCap = pointCaps[step],
            droppedKinds = droppedKinds,
            coarsened = step > 0 || sourceTruncated,
        )
        if (encodedSize(snapshot) <= WearMapsProtocol.MAX_JSON_BYTES) return snapshot
        // Next rung: also drop the lowest-priority remaining line kind.
        val remaining = LINE_PRIORITY.filter { k -> rawLines.any { it.kind == k } && k !in droppedKinds }
        if (remaining.isNotEmpty()) droppedKinds = droppedKinds + remaining.last()
    }
    // Final rung: coarsest build with whatever kinds remain; flagged truncated.
    return buildAt(
        center, generatedAtMillis, rawLines, rawPoints, saved,
        userBearingDeg, missingKinds, radiusM,
        toleranceM = tolerances.last(), pointCap = pointCaps.last(),
        droppedKinds = droppedKinds, coarsened = true,
    )
}

private fun encodedSize(snapshot: WearMapSnapshot): Int =
    WearMapsProtocol.json.encodeToString(snapshot).encodeToByteArray().size

private fun buildAt(
    center: GeoPoint,
    generatedAtMillis: Long,
    rawLines: List<WearRawLine>,
    rawPoints: List<WearRawPoint>,
    saved: List<WearSavedPlace>,
    userBearingDeg: Int,
    missingKinds: List<String>,
    radiusM: Int,
    toleranceM: Double,
    pointCap: Int,
    droppedKinds: List<WearLineKind>,
    coarsened: Boolean,
): WearMapSnapshot {
    val r = radiusM.toDouble()
    val lines = mutableListOf<WearMapLine>()
    for (raw in rawLines) {
        if (raw.kind in droppedKinds || raw.points.size < 2) continue
        val simplified = simplifyWearLine(raw.points, center, toleranceM)
        val meter = toMeterPts(simplified, center.latitude, center.longitude)
        if (raw.closed) {
            val inside = meter.filter { abs(it.x) <= r && abs(it.y) <= r }
            if (inside.size >= 3) {
                lines += WearMapLine(raw.kind, quantizeFlat(inside), closed = true, name = raw.name)
            }
        } else {
            for (run in clipOpenLine(meter, r)) {
                lines += WearMapLine(raw.kind, quantizeFlat(run), name = raw.name)
            }
        }
    }
    // Nearest points survive the cap (the watch is a glance, centre-first).
    val points = rawPoints
        .map { p ->
            val x = wearMetersEast(center.latitude, center.longitude, p.point.longitude)
            val y = wearMetersNorth(center.latitude, p.point.latitude)
            Triple(p, x, y)
        }
        .filter { abs(it.second) <= r && abs(it.third) <= r }
        .sortedBy { it.second * it.second + it.third * it.third }
        .take(pointCap)
        .map { (p, x, y) -> WearMapPoint(p.type, p.name, wearQuantize(x), wearQuantize(y)) }
    val degraded = buildList {
        addAll(missingKinds)
        if (coarsened) add("detail")
        droppedKinds.forEach { add(it.name.lowercase()) }
    }
    return WearMapSnapshot(
        generatedAtMillis = generatedAtMillis,
        centerLatE7 = (center.latitude * 1e7).toInt(),
        centerLonE7 = (center.longitude * 1e7).toInt(),
        radiusM = radiusM,
        lines = lines,
        points = points,
        saved = saved,
        truncated = coarsened || droppedKinds.isNotEmpty() || points.size < rawPoints.size,
        degraded = degraded.distinct(),
        userBearingDeg = userBearingDeg,
    )
}

/**
 * Movement hysteresis: republish only when the user is in the outer band
 * of the current snapshot, so walking around the centre does not thrash
 * the link, and leaving the region always republishes.
 */
object WearRegionHysteresis {
    /** Fraction of the radius beyond which a fix triggers a republish. */
    const val EDGE_FRACTION = 0.65

    fun shouldRepublish(distanceFromCenterM: Double, radiusM: Int): Boolean =
        distanceFromCenterM > radiusM * EDGE_FRACTION

    /** Equirectangular distance (m) between two geographic points. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dx = wearMetersEast(a.latitude, a.longitude, b.longitude)
        val dy = wearMetersNorth(a.latitude, b.latitude)
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * Builds the watch route overlay from a phone route: decimated polyline
 * ahead of [distanceAlongM], maneuvers with cumulative distances, and the
 * next-maneuver/ETA summary. Decimation coarsens until the byte cap fits.
 */
fun buildWearRouteState(
    destinationName: String,
    polyline: List<GeoPoint>,
    maneuvers: List<WearManeuver>,
    nextText: String,
    distanceToNextM: Int,
    distanceRemainingM: Int,
    etaEpochMs: Long,
    generatedAtMillis: Long,
): WearRouteState {
    if (polyline.isEmpty()) {
        return WearRouteState(
            active = true, destinationName = destinationName,
            maneuvers = maneuvers, nextText = nextText,
            distanceToNextM = distanceToNextM,
            distanceRemainingM = distanceRemainingM,
            etaEpochMs = etaEpochMs, generatedAtMillis = generatedAtMillis,
        )
    }
    val origin = polyline.first()
    for (tolerance in listOf(6.0, 12.0, 24.0, 48.0)) {
        val simplified = simplifyWearLine(polyline, origin, tolerance)
        val capped = if (simplified.size > 400) {
            simplified.filterIndexed { i, _ -> i % 2 == 0 || i == simplified.lastIndex }
        } else {
            simplified
        }
        val state = WearRouteState(
            active = true, destinationName = destinationName,
            routeOriginLatE7 = (origin.latitude * 1e7).toInt(),
            routeOriginLonE7 = (origin.longitude * 1e7).toInt(),
            routePts = quantizeFlat(toMeterPts(capped, origin.latitude, origin.longitude)),
            maneuvers = maneuvers.take(12),
            nextText = nextText,
            distanceToNextM = distanceToNextM,
            distanceRemainingM = distanceRemainingM,
            etaEpochMs = etaEpochMs, generatedAtMillis = generatedAtMillis,
        )
        if (WearMapsProtocol.json.encodeToString(state).encodeToByteArray().size <=
            WearMapsProtocol.MAX_ROUTE_JSON_BYTES
        ) return state
    }
    // Degenerate fallback: endpoints only, still an honest active route.
    return WearRouteState(
        active = true, destinationName = destinationName,
        routeOriginLatE7 = (origin.latitude * 1e7).toInt(),
        routeOriginLonE7 = (origin.longitude * 1e7).toInt(),
        routePts = quantizeFlat(toMeterPts(listOf(origin, polyline.last()), origin.latitude, origin.longitude)),
        maneuvers = maneuvers.take(12),
        nextText = nextText,
        distanceToNextM = distanceToNextM,
        distanceRemainingM = distanceRemainingM,
        etaEpochMs = etaEpochMs, generatedAtMillis = generatedAtMillis,
    )
}
