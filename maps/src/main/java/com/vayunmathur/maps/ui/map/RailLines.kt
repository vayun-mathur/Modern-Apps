package com.vayunmathur.maps.ui.map
import com.vayunmathur.maps.domain.visibleBoundsOrWorld

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.RouteOverlay
import com.vayunmathur.library.map.RouteSegment
import com.vayunmathur.library.map.RouteStyle
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.ui.theme.MapTokens
import com.vayunmathur.maps.data.OfflineRouterTransit
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlin.math.pow

/**
 * How long the camera must sit still before the rail network refetches: the
 * repo's debounce idiom (see the traffic prefetch in [MapSurface]). A pan
 * across the city would otherwise fire a pack query per frame.
 */
private const val RAIL_FETCH_DEBOUNCE_MS = 500L

/**
 * Below this zoom a viewport spans far more track than a lines overlay can
 * usefully draw (and the native query caps at 1500 lines). The pack's own
 * floor is z8; one step in keeps the fetch to the metro in view.
 */
private const val RAIL_MIN_ZOOM = 9.0

/**
 * How far the viewport centre may drift (degrees) before the network
 * refetches. A degree is ~111 km, so this is ~2 km: pans inside the covered
 * bbox reuse the last fetch rather than re-querying per nudge.
 */
private const val RAIL_RECENTER_DEGREES = 0.02

/**
 * Parse a 6-digit `RRGGBB` pack colour, or null when absent/malformed. The
 * pack stores colour without a `#`; [parseHexColor] in RouteOverlayBuilder
 * wants the hash, so it is added here.
 */
private fun packHexColor(hex: String?): Color? =
    hex?.takeIf { it.length == 6 }?.let {
        runCatching { Color(("#$it").toColorInt()) }.getOrNull()
    }

/**
 * How many parallel lanes a corridor draws at `zoom`, floored at read.
 * Ported from the tile style's `transit-rail` entry (`lanes` ramp
 * `[[0,1],[9,2],[11,3],[13,4]]`, linear): the lane count is a property of
 * the camera, not of the route, so a corridor fans wider as you zoom in and
 * collapses toward its centerline as you zoom out.
 */
internal fun railLaneCount(zoom: Double): Int {
    val stops = listOf(0.0 to 1.0f, 9.0 to 2.0f, 11.0 to 3.0f, 13.0 to 4.0f)
    if (zoom <= stops.first().first) return stops.first().second.toInt()
    if (zoom >= stops.last().first) return stops.last().second.toInt()
    for (i in 0 until stops.size - 1) {
        val (z0, v0) = stops[i]
        val (z1, v1) = stops[i + 1]
        if (zoom <= z1) {
            val t = (zoom - z0) / (z1 - z0)
            return (v0 + (v1 - v0) * t).toInt()
        }
    }
    return stops.last().second.toInt()
}

/**
 * How far sideways a corridor span's mesh shifts at `zoom`, in Dp. Ported
 * from `lane_offset_px` in the tile style: past the lane count the colours
 * squash onto shared lanes from the middle (ordinal 0 keeps lane 0, the
 * last ordinal the last lane) so two colours can share a lane but never
 * swap sides. `spread` is the tile entry's constant 6.0 Dp between adjacent
 * lanes; `taper/255` eases mouth pieces into their lane.
 */
internal fun railLaneOffsetDp(zoom: Double, ordinal: Int, count: Int, taper: Int): Double {
    val lanes = minOf(railLaneCount(zoom), count)
    if (lanes < 2 || count < 2) return 0.0
    val span = lanes - 1
    val steps = count - 1
    val lane = (2 * ordinal * span + steps) / (2 * steps)
    return (2 * lane - (lanes - 1)) * 6.0 / 2.0 * (taper / 255.0)
}

/**
 * Ground metres per Dp at `latitude`/`zoom` for the 512-unit tile schema.
 */
internal fun metersPerDp(latitude: Double, zoom: Double): Double {
    val worldPx = 512.0 * 2.0.pow(zoom)
    return 40_075_016.686 * kotlin.math.cos(Math.toRadians(latitude)) / worldPx
}

/**
 * A polyline shifted sideways by `meters` (positive = right of travel),
 * for fanning corridor spans into parallel lanes host-side.
 *
 * Per-vertex averaged normals: each interior vertex moves along the mean of
 * its two segment normals, endpoints along their single segment's normal.
 * Ends stay glued (zero-length segments skipped), so fanned spans meet the
 * same way centred ones do.
 */
internal fun offsetPolyline(
    points: List<com.vayunmathur.library.map.GeoPoint>,
    meters: Double,
): List<com.vayunmathur.library.map.GeoPoint> {
    if (points.size < 2 || meters == 0.0) return points
    data class Vec(val east: Double, val north: Double)
    // Segment normals (east, north), length-weighted by averaging below.
    val normals = mutableListOf<Vec>()
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val cosLat = kotlin.math.cos(
            Math.toRadians((a.latitude + b.latitude) / 2.0)
        ).coerceAtLeast(1e-6)
        val east = (b.longitude - a.longitude) * 111_320.0 * cosLat
        val north = (b.latitude - a.latitude) * 111_320.0
        val len = kotlin.math.hypot(east, north)
        // Right-of-travel normal: (north, -east) normalized. Matches the
        // tool's `d.east * uy - d.north * ux` side: positive offset draws
        // right of travel, like ascending ordinals.
        normals.add(
            if (len <= 0.0) Vec(0.0, 0.0)
            else Vec(north / len, -east / len)
        )
    }
    return points.mapIndexed { i, p ->
        val n = when (i) {
            0 -> normals.first()
            points.size - 1 -> normals.last()
            else -> {
                val a = normals[i - 1]
                val b = normals[i]
                val s = Vec(a.east + b.east, a.north + b.north)
                val len = kotlin.math.hypot(s.east, s.north)
                if (len <= 0.0) a else Vec(s.east / len, s.north / len)
            }
        }
        val cosLat = kotlin.math.cos(Math.toRadians(p.latitude)).coerceAtLeast(1e-6)
        com.vayunmathur.library.map.GeoPoint(
            p.longitude + n.east * meters / (111_320.0 * cosLat),
            p.latitude + n.north * meters / 111_320.0,
        )
    }
}

/**
 * What the rail collector should do on a settled camera. The refetch /
 * re-fan / restore decision, factored pure for tests. `CLEAR` drops the
 * overlay at the zoom floor but keeps the fetch cache, so zooming back in
 * restores via `REFAN` instead of re-querying the pack.
 */
internal enum class RailRefresh {
    CLEAR,
    REUSE,
    REFAN,
    REFETCH,
}

/**
 * Pure form of the settle logic in [rememberRailLines]: below
 * [RAIL_MIN_ZOOM] clear; outside the fetched footprint (or on first load)
 * refetch; inside it re-fan when the lane count stepped, or when the overlay
 * is null with cached spans (the zoom gate cleared it).
 */
internal fun railRefreshDecision(
    zoom: Double,
    centre: Pair<Double, Double>,
    fetchedCentre: Pair<Double, Double>?,
    fetchedZoom: Double,
    fannedZoom: Double,
    overlayNull: Boolean,
    spansEmpty: Boolean,
): RailRefresh {
    if (zoom < RAIL_MIN_ZOOM) return RailRefresh.CLEAR
    val last = fetchedCentre
    if (last == null ||
        kotlin.math.abs(centre.first - last.first) >= RAIL_RECENTER_DEGREES ||
        kotlin.math.abs(centre.second - last.second) >= RAIL_RECENTER_DEGREES ||
        kotlin.math.abs(zoom - fetchedZoom) >= 1.0
    ) {
        return RailRefresh.REFETCH
    }
    if (spansEmpty) return RailRefresh.REUSE
    if (overlayNull) return RailRefresh.REFAN
    return if (railLaneCount(zoom) != railLaneCount(fannedZoom)) {
        RailRefresh.REFAN
    } else {
        RailRefresh.REUSE
    }
}

/**
 * The pack-driven transit lines for the selected stop, as a [RouteOverlay
 * ], or null when the transit layer is off, no stop is selected, or the pack
 * carries no shapes for its routes.
 *
 * Fetched from the on-device timetable pack (GTFS-shape polylines per route
 * serving the stop), not the tile layer that needs an archive rebuild. Drawn
 * in the separate rail slot under the navigation route, in each line's agency
 * colour with the route fallback where the pack carries none. Buses included:
 * at one stop a handful of bus polylines is context, not noise. Keyed by the
 * selected stop, so switching stops refetches while panning reuses the fetch.
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberRailLines(
    camera: CameraState,
    transitEnabled: Boolean,
    selectedStop: TransitStop?,
    tokens: MapTokens,
): RouteOverlay? {
    val context = LocalContext.current
    var overlay by remember { mutableStateOf<RouteOverlay?>(null) }
    // Last fetch footprint: keyed by the selected stop, reused while the
    // camera stays inside it. The fetched spans (with corridor slots) are
    // kept separately from the built overlay so a zoom-band change re-fans
    // cached geometry without re-querying the pack.
    var fetchedStop by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var fetchedCentre by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var fetchedZoom by remember { mutableStateOf(Double.NaN) }
    var fetchedSpans by remember {
        mutableStateOf<List<com.vayunmathur.maps.data.transit.RailLine>>(emptyList())
    }
    var fannedZoom by remember { mutableStateOf(Double.NaN) }

    LaunchedEffect(transitEnabled, selectedStop?.lat, selectedStop?.lon) {
        if (!transitEnabled || selectedStop == null) {
            overlay = null
            fetchedStop = null
            fetchedCentre = null
            fetchedZoom = Double.NaN
            fetchedSpans = emptyList()
            fannedZoom = Double.NaN
        }
    }

    // Re-fan cached spans when the lane count steps (z9/11/13 bands). Pure
    // geometry post-processing: no pack query, no tessellation here (the
    // push re-tessellates native-side, like any overlay update).
    fun fan(spans: List<com.vayunmathur.maps.data.transit.RailLine>, zoom: Double, lat: Double) {
        val mpd = metersPerDp(lat, zoom)
        overlay = if (spans.isEmpty()) {
            null
        } else {
            RouteOverlay(
                segments = spans.map { line ->
                    val offsetDp = railLaneOffsetDp(zoom, line.ordinal, line.lanes, line.taper)
                    RouteSegment(
                        points = offsetPolyline(line.points, offsetDp * mpd),
                        color = packHexColor(line.color)
                            ?: tokens.routeTransitFallback,
                    )
                },
                // Thinner than the 8 dp navigation route with no
                // casing: a network backdrop, not a followed line.
                style = RouteStyle(width = 4.dp, casingWidth = 0.dp),
            )
        }
        fannedZoom = zoom
    }

    LaunchedEffect(camera, transitEnabled, selectedStop?.lat, selectedStop?.lon) {
        val stop = selectedStop
        if (!transitEnabled || stop == null) return@LaunchedEffect
        val stopKey = stop.lat to stop.lon
        // A new stop invalidates the old fetch even if the camera never moved.
        if (stopKey != fetchedStop && fetchedStop != null) {
            overlay = null
            fetchedCentre = null
            fetchedZoom = Double.NaN
            fetchedSpans = emptyList()
            fannedZoom = Double.NaN
        }
        snapshotFlow { camera.position }
            .debounce(RAIL_FETCH_DEBOUNCE_MS)
            .collectLatest {
                val bounds = camera.visibleBoundsOrWorld()
                val zoom = camera.position.zoom
                val centre = (bounds.south + bounds.north) / 2.0 to
                    (bounds.west + bounds.east) / 2.0
                when (railRefreshDecision(
                    zoom, centre, fetchedCentre, fetchedZoom,
                    fannedZoom, overlay == null, fetchedSpans.isEmpty(),
                )) {
                    RailRefresh.CLEAR -> {
                        overlay = null
                        fannedZoom = Double.NaN
                        return@collectLatest
                    }
                    RailRefresh.REFAN -> {
                        fan(fetchedSpans, zoom, centre.first)
                        return@collectLatest
                    }
                    RailRefresh.REUSE -> return@collectLatest
                    RailRefresh.REFETCH -> Unit
                }
                // Keyed by the selected stop, not the viewport: the native side
                // resolves its routes through nearest_stop, consistent with the
                // departure board for the same stop.
                val lines = try {
                    OfflineRouterTransit.stopLines(
                        context,
                        lat = stop.lat,
                        lon = stop.lon,
                    )
                } catch (_: Exception) {
                    emptyList()
                }
                fetchedStop = stopKey
                fetchedCentre = centre
                fetchedZoom = zoom
                fetchedSpans = lines
                fan(lines, zoom, centre.first)
            }
    }
    return overlay
}
