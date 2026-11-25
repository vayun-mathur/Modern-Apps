package com.vayunmathur.maps.wear.domain

import kotlin.math.sqrt

/**
 * Pure north-up projection for the watch Canvas renderer.
 *
 * Coordinates are metres east/north of the snapshot centre. The view is
 * centred on [centerEastM]/[centerNorthM] (the snapshot centre, or the
 * user when following), scaled so the snapshot radius fits the smaller
 * viewport dimension, plus a pan offset in metres. North is up:
 * positive north metres map to smaller screen y. Rotation is not done
 * in v1 (a single Canvas rotate would make it cheap; labels/legibility
 * keep v1 north-up — see module AGENTS.md).
 */
data class WearMapProjection(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val radiusM: Int,
    val centerEastM: Double = 0.0,
    val centerNorthM: Double = 0.0,
    val panEastM: Double = 0.0,
    val panNorthM: Double = 0.0,
    val zoom: Double = 1.0,
) {
    /** Pixels per metre: radius fits half the smaller viewport side, times [zoom]. */
    val pixelsPerMeter: Double
        get() = (minOf(viewportWidthPx, viewportHeightPx) / 2.0 / radiusM.coerceAtLeast(1)) * zoom

    /** Screen (x, y) in pixels for metres east/north of the snapshot centre. */
    fun project(eastM: Double, northM: Double): Pair<Float, Float> {
        val x = viewportWidthPx / 2.0 + (eastM - centerEastM + panEastM) * pixelsPerMeter
        val y = viewportHeightPx / 2.0 - (northM - centerNorthM + panNorthM) * pixelsPerMeter
        return x.toFloat() to y.toFloat()
    }

    /** Inverse of [project]: metres east/north under a screen point. */
    fun unproject(xPx: Float, yPx: Float): Pair<Double, Double> {
        val east = (xPx - viewportWidthPx / 2.0) / pixelsPerMeter + centerEastM - panEastM
        val north = (viewportHeightPx / 2.0 - yPx) / pixelsPerMeter + centerNorthM - panNorthM
        return east to north
    }

    /** True when metres (e/n) fall inside the snapshot square. */
    fun insideRegion(eastM: Double, northM: Double): Boolean =
        kotlin.math.abs(eastM) <= radiusM && kotlin.math.abs(northM) <= radiusM
}

/** Metres east/north of a snapshot's centre for an absolute E7 position. */
fun wearOffsetInSnapshot(snapshot: WearMapSnapshot, latE7: Int, lonE7: Int): Pair<Double, Double> {
    val originLat = snapshot.centerLatE7 / 1e7
    val originLon = snapshot.centerLonE7 / 1e7
    return wearMetersEast(originLat, originLon, lonE7 / 1e7) to
        wearMetersNorth(originLat, latE7 / 1e7)
}

/** Metres east/north of a snapshot's centre for a route-overlay point (route origin + quantized). */
fun wearRoutePointInSnapshot(
    snapshot: WearMapSnapshot,
    route: WearRouteState,
    dxUnits: Int,
    dyUnits: Int,
): Pair<Double, Double> {
    val snapshotLat = snapshot.centerLatE7 / 1e7
    val snapshotLon = snapshot.centerLonE7 / 1e7
    val routeLat = route.routeOriginLatE7 / 1e7
    val routeLon = route.routeOriginLonE7 / 1e7
    val originEast = wearMetersEast(snapshotLat, snapshotLon, routeLon)
    val originNorth = wearMetersNorth(snapshotLat, routeLat)
    return (originEast + wearDequantize(dxUnits)) to (originNorth + wearDequantize(dyUnits))
}

/** Distance (m) between an E7 position and a saved place, for the glance list. */
fun wearDistanceM(fromLatE7: Int, fromLonE7: Int, to: WearSavedPlace): Double {
    val dx = wearMetersEast(fromLatE7 / 1e7, fromLonE7 / 1e7, to.lonE7 / 1e7)
    val dy = wearMetersNorth(fromLatE7 / 1e7, to.latE7 / 1e7)
    return sqrt(dx * dx + dy * dy)
}

/** Route overlay lifecycle: an inactive state clears the overlay; an active one replaces it. */
fun wearApplyRouteState(current: WearRouteState?, incoming: WearRouteState): WearRouteState? =
    if (incoming.active) incoming else null

/** Stale when the snapshot is older than the protocol window; no snapshot is always stale. */
fun wearIsStale(snapshot: WearMapSnapshot?, nowMillis: Long): Boolean =
    snapshot == null || nowMillis - snapshot.generatedAtMillis > WearMapsProtocol.STALE_AFTER_MS
