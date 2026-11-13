package com.vayunmathur.library.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpRect

/**
 * Packs marker lists into the four parallel bulk arrays the native side reads:
 * ids, then interleaved lon/lat, then icon ids, then labels. Packed once per push rather than
 * crossing the JNI boundary per pin. A null label packs as the empty string, which the native
 * side reads as "icon alone".
 */
internal fun packMapMarkers(pins: List<MapMarker>): MarkerArrays {
    val ids = LongArray(pins.size)
    val lonLat = FloatArray(pins.size * 2)
    val icons = IntArray(pins.size)
    val labels = Array(pins.size) { "" }
    for (i in pins.indices) {
        val pin = pins[i]
        ids[i] = pin.id
        lonLat[i * 2] = pin.position.longitude.toFloat()
        lonLat[i * 2 + 1] = pin.position.latitude.toFloat()
        icons[i] = pin.icon
        labels[i] = pin.label ?: ""
    }
    return MarkerArrays(ids, lonLat, icons, labels)
}

/** The four parallel marker arrays crossing JNI in [MapNative.setMarkers]. */
internal data class MarkerArrays(
    val ids: LongArray,
    val lonLat: FloatArray,
    val icons: IntArray,
    val labels: Array<String>,
)

/**
 * Packs vehicle lists into the four parallel bulk arrays the native vehicle
 * path reads: the three marker arrays plus per-vehicle route colours packed
 * as `0xRRGGBB` (`0` draws no ring). Separate from [packMapMarkers] so the pin
 * path keeps its three-array JNI signature.
 */
internal data class PackedVehicles(
    val ids: LongArray,
    val lonLat: FloatArray,
    val icons: IntArray,
    val colors: IntArray,
)

internal fun packVehicles(vehicles: List<MapMarker>): PackedVehicles {
    val ids = LongArray(vehicles.size)
    val lonLat = FloatArray(vehicles.size * 2)
    val icons = IntArray(vehicles.size)
    val colors = IntArray(vehicles.size)
    for (i in vehicles.indices) {
        val v = vehicles[i]
        ids[i] = v.id
        lonLat[i * LON_LAT_COMPONENTS] = v.position.longitude.toFloat()
        lonLat[i * LON_LAT_COMPONENTS + 1] = v.position.latitude.toFloat()
        icons[i] = v.icon
        colors[i] = v.color and RGB_MASK
    }
    return PackedVehicles(ids, lonLat, icons, colors)
}

private const val LON_LAT_COMPONENTS = 2
private const val RGB_MASK = 0x00FFFFFF

/**
 * The route, traffic and picking half of [SurfaceMapRenderer], as `internal`
 * extensions so SurfaceMapRenderer.kt stays under the FileLength limit. Public
 * setters stay on the renderer and delegate here.
 */

/**
 * Draw [points] as a single-colour navigation route, over the basemap and under the
 * puck. `null` or fewer than two distinct points draws nothing, which is how a route is
 * cleared. The convenience path for a host that wants one line in one colour — Android
 * Auto, which has no view hierarchy to hang a Compose overlay in.
 *
 * Main thread, like everything else here (the stroke and upload run on the render thread).
 * Not free, but paid once per route rather than once per frame: the native side tessellates
 * the polyline and uploads it,
 * and never rebuilds it — the mesh is zoom-independent, so a navigation session costs
 * one tessellation however long the drive or however much the driver zooms. Setting
 * the same route again does re-tessellate, so drive this from a state change rather
 * than from a per-frame callback.
 */
internal fun SurfaceMapRenderer.setSingleRoute(points: List<GeoPoint>?, style: RouteStyle = RouteStyle()) {
    routeSegments = points?.let { listOf(RouteSegment(it, DEFAULT_ROUTE_COLOR)) }
    routeStyle = style
    applyRoute()
    invalidate()
}

/**
 * Draw a multi-segment, per-segment-coloured route (see [RouteOverlay]), over the
 * basemap and under the puck. `null` or an all-empty overlay draws nothing, which is
 * how a route is cleared. This is what the phone pushes: the traffic-band / transit /
 * travelled-grey colouring resolved on the device into one coloured segment per run.
 *
 * Same cost model as the single-colour [setSingleRoute]: one tessellation per push, never
 * per frame or per zoom step.
 */
internal fun SurfaceMapRenderer.setOverlayRoute(overlay: RouteOverlay?) {
    routeSegments = overlay?.segments
    routeStyle = overlay?.style ?: RouteStyle()
    applyRoute()
    invalidate()
}

/**
 * Draw the pack-driven rail-lines network under the navigation route (see
 * [RouteOverlay]). `null` or an all-empty overlay draws nothing, which is how
 * the network is cleared (transit toggle off, or the pack carries no shapes
 * for the viewport). One tessellation per push, like the route.
 */
internal fun SurfaceMapRenderer.setRailLines(overlay: RouteOverlay?) {
    railSegments = overlay?.segments
    railStyle = overlay?.style ?: RouteStyle()
    applyRailLines()
    invalidate()
}

/**
 * Push the live-traffic colour table: [ids] holds each segment's `component_id` and
 * [argbColors] the fully-resolved ARGB to draw it, index for index. The host owns the
 * theme, so the colours are final. Replaces the whole table each call; a segment whose id
 * is absent draws nothing (the basemap road shows through). Cheap — a pure recolour, no
 * tessellation — so it is safe to drive from a camera-idle callback.
 *
 * Remembered so it survives the surface being recreated. Has no visible effect unless the
 * traffic layer is enabled through [SurfaceMapRenderer.setLayers]/[LayerOptions.traffic].
 */
internal fun SurfaceMapRenderer.pushTrafficSpeeds(ids: LongArray, argbColors: IntArray) {
    trafficSpeeds = ids to argbColors
    applyTraffic()
    invalidate()
}

/** Clear the live-traffic overlay so it draws nothing until the next [pushTrafficSpeeds]. */
internal fun SurfaceMapRenderer.dropTraffic() {
    trafficSpeeds = null
    applyTraffic()
    invalidate()
}

/**
 * The id of the pin under a tap, or `0` when the tap hit no pin. [xDp]/[yDp] are Dp from the
 * viewport top-left. Reads the renderer's id buffer (see the native `pickAt`), so it stays
 * correct under tilt and never trails the basemap on a pan, unlike a Compose CPU hit-test.
 *
 * The returned value is whatever [MapMarker.id] the host set, so it maps the tap back to its
 * own feature. `internal` because it is wired through [Projection.pickMarker] like the label
 * pick, so a caller without a live renderer gets `0` rather than a dead handle.
 */
internal fun SurfaceMapRenderer.tapMarkerAt(xDp: Float, yDp: Float): Long =
    // The native pick renders the id buffer and waits for the GPU, so it runs on the render
    // thread; the tap handler waits for it, bounded, and falls back to "no pin" past the bound
    // (the host's CPU hit-test then takes over) rather than freezing the main thread.
    queryRenderThread(PICK_TIMEOUT_MILLIS, 0L) { h ->
        try {
            MapNative.pickAt(h, xDp, yDp)
        } catch (_: Throwable) {
            0L
        }
    }

/**
 * One native pick row as a [PlacedLabel], or null when the row is malformed.
 *
 * Rows are 7 fields (`layerId/name/kind/lon/lat/featureId/regionId`, see
 * `bridge/pick.rs`); legacy 6-field rows parse with `regionId = 0`. Pure so the
 * contract is a JVM unit test rather than a screenshot.
 */
internal fun parseLabelRow(parts: List<String>): PlacedLabel? {
    if (parts.size != LABEL_FIELDS && parts.size != LABEL_FIELDS_LEGACY) return null
    return PlacedLabel(
        layerId = parts[0],
        name = parts[1],
        kind = parts[2],
        position = GeoPoint(
            longitude = parts[3].toDoubleOrNull() ?: 0.0,
            latitude = parts[4].toDoubleOrNull() ?: 0.0,
        ),
        // Unsigned on the native side; ids never come close to the sign bit
        // (an OSM id shifted left two is ~36 bits), so a Long is roomy.
        featureId = parts[5].toLongOrNull() ?: 0L,
        regionId = parts.getOrNull(LABEL_REGION_INDEX)?.toLongOrNull() ?: 0L,
    )
}

private const val LABEL_FIELDS = 7
private const val LABEL_FIELDS_LEGACY = 6
private const val LABEL_REGION_INDEX = 6
internal fun SurfaceMapRenderer.tapLabelsIn(box: DpRect, layerIds: Set<String>): List<PlacedLabel> {
    // The placed-label snapshot is native state the frame rewrites, so it is read on the
    // render thread; parsing the rows happens back here.
    val rows = queryRenderThread(PICK_TIMEOUT_MILLIS, emptyArray<String>()) { h ->
        try {
            MapNative.pickLabels(h, box.left.value, box.top.value, box.right.value, box.bottom.value)
        } catch (_: Throwable) {
            emptyArray()
        }
    }
    return try {
        rows
            .asSequence()
            .map { row -> parseLabelRow(row.split('')) }
            .filter { label -> label != null && (layerIds.isEmpty() || label.layerId in layerIds) }
            .map { label -> label!! }
            .toList()
    } catch (_: Throwable) {
        emptyList()
    }
}

internal fun SurfaceMapRenderer.applyRoute() {
    // Drawable segments only: fewer than two points strokes nothing, and dropping them
    // here keeps the native side's per-segment ranges aligned with the colours.
    val drawable = routeSegments?.filter { it.points.size >= 2 }
    if (drawable.isNullOrEmpty()) {
        onRenderThread { h -> MapNative.clearRoute(h) }
        return
    }
    // Flat lon/lat pairs across every segment, plus a point count and colour per
    // segment: three bulk arrays cross JNI instead of one call per point, which for a
    // cross-city route is thousands of crossings saved. Built here rather than held,
    // because a route is set once and this is not a per-frame path.
    val flat = FloatArray(drawable.sumOf { it.points.size } * 2)
    val lengths = IntArray(drawable.size)
    val colors = IntArray(drawable.size)
    var at = 0
    drawable.forEachIndexed { i, segment ->
        lengths[i] = segment.points.size
        colors[i] = segment.color.toArgb()
        for (point in segment.points) {
            flat[at * 2] = point.longitude.toFloat()
            flat[at * 2 + 1] = point.latitude.toFloat()
            at++
        }
    }
    val width = routeStyle.width.value
    val casingWidth = routeStyle.casingWidth.value
    val casingColor = routeStyle.casingColor.toArgb()
    // Packed here, stroked and uploaded on the render thread.
    onRenderThread { h -> MapNative.setRoute(h, flat, lengths, colors, width, casingWidth, casingColor) }
}

internal fun SurfaceMapRenderer.applyRailLines() {
    // Drawable segments only, like the route: fewer than two points strokes
    // nothing, and dropping them keeps the native per-segment ranges aligned.
    val drawable = railSegments?.filter { it.points.size >= 2 }
    if (drawable.isNullOrEmpty()) {
        onRenderThread { h -> MapNative.clearRailLines(h) }
        return
    }
    val flat = FloatArray(drawable.sumOf { it.points.size } * 2)
    val lengths = IntArray(drawable.size)
    val colors = IntArray(drawable.size)
    var at = 0
    drawable.forEachIndexed { i, segment ->
        lengths[i] = segment.points.size
        colors[i] = segment.color.toArgb()
        for (point in segment.points) {
            flat[at * 2] = point.longitude.toFloat()
            flat[at * 2 + 1] = point.latitude.toFloat()
            at++
        }
    }
    val width = railStyle.width.value
    val casingWidth = railStyle.casingWidth.value
    val casingColor = railStyle.casingColor.toArgb()
    onRenderThread { h -> MapNative.setRailLines(h, flat, lengths, colors, width, casingWidth, casingColor) }
}

internal fun SurfaceMapRenderer.applyTraffic() {
    val speeds = trafficSpeeds
    onRenderThread { h ->
        if (speeds == null) {
            MapNative.clearTraffic(h)
        } else {
            MapNative.setTrafficSpeeds(h, speeds.first, speeds.second)
        }
    }
}

internal fun SurfaceMapRenderer.applyUserPuck() {
    val puck = userPuck
    onRenderThread { h ->
        if (puck == null) {
            MapNative.clearUserPuck(h)
        } else {
            MapNative.setUserPuck(
                h,
                puck.position.longitude.toFloat(),
                puck.position.latitude.toFloat(),
                puck.bearing ?: 0f,
                puck.bearing != null,
            )
        }
    }
}

internal fun SurfaceMapRenderer.applyMarkers() {
    val pins = markers
    if (pins.isEmpty()) {
        onRenderThread { h -> MapNative.clearMarkers(h) }
        return
    }
    val packed = packMapMarkers(pins)
    onRenderThread { h -> MapNative.setMarkers(h, packed.ids, packed.lonLat, packed.icons, packed.labels) }
}

internal fun SurfaceMapRenderer.applyVehicles() {
    val vs = vehicles
    if (vs.isEmpty()) {
        onRenderThread { h -> MapNative.clearVehicles(h) }
        return
    }
    val packed = packVehicles(vs)
    onRenderThread { h -> MapNative.setVehicles(h, packed.ids, packed.lonLat, packed.icons, packed.colors) }
}

/**
 * How long a tap waits for the render thread's pick: one in-flight frame plus the pick's own
 * one-shot render. Past it the tap resolves as a miss and the host's fallbacks run.
 */
internal const val PICK_TIMEOUT_MILLIS = 250L

/**
 * The fill the single-colour [setSingleRoute] convenience paints: the car's `#1A73E8`,
 * the one route colour in this app authored against a real rendering (see
 * [RouteStyle]). A per-segment route carries its own colours instead.
 */
internal val DEFAULT_ROUTE_COLOR = Color(0xFF1A73E8)
