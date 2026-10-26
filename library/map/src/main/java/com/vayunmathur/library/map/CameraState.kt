package com.vayunmathur.library.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the camera is, and which way is up.
 *
 * [bearing] and [pitch] both **default to zero**, so every existing call site, the saver, and
 * the whole Compose path are unchanged until something tilts or rotates the camera: a north-up,
 * level camera composes exactly the axis-aligned matrices it always did.
 *
 * [bearing] is a rotation, which composes into the clip matrices as a plain 2x2 and is
 * honoured on both the [SurfaceMapRenderer.camera] and the Compose paths: [Projection]
 * carries it, so `MapMarker`s and pins rotate with the basemap. [pitch] is the perspective
 * tilt; like bearing it *is* honoured on the Compose path, because [Projection] is now
 * pitch-aware (ray/plane), so `MapMarker`s and pins follow the tilt instead of floating
 * off the ground.
 */
data class CameraPosition(
    val target: GeoPoint = GeoPoint(0.0, 0.0),
    val zoom: Double = 0.0,
    /**
     * Which compass direction points **up the screen**, in degrees clockwise from north.
     *
     * Zero is north-up and is what every phone screen uses. Android Auto's heading-up
     * navigation is the one caller that sets it, through [SurfaceMapRenderer.camera].
     *
     * Honoured on both paths: the Compose path carries it through [Projection] (which
     * rotates overlays with the basemap) and the renderer path passes it to the native
     * frame, so setting it rotates the whole map — basemap, markers, pins and clusters.
     */
    val bearing: Double = 0.0,
    /**
     * Camera tilt away from straight-down, in degrees, expected in `0.0..`[MAX_PITCH].
     *
     * Zero is the flat top-down map. Driven by the two-finger vertical-drag tilt gesture (see
     * `MapGestures`) and honoured on both the Compose and [SurfaceMapRenderer.camera] paths:
     * [Projection] intersects the eye ray with the ground plane, so overlays anchored through it
     * stay glued to their ground point under tilt.
     */
    val pitch: Double = 0.0,
)

/**
 * Holds the live [CameraPosition] (as Compose state so gestures/animations
 * drive recomposition) and derives a [Projection] once the viewport is
 * measured. Mirrors maplibre-compose's `CameraState` surface used by the apps:
 * [position], [projection], [awaitProjection], [animateTo].
 */
class CameraState(initial: CameraPosition = CameraPosition()) {
    var position: CameraPosition by mutableStateOf(initial)

    /**
     * Whether to draw a true-3D-sphere globe instead of the flat Mercator map.
     *
     * Off by default, so every existing host is unchanged. Set from `VectorMap`'s
     * `globeEnabled` param (itself default-off; only `maps` opts in). Transient like
     * [viewportDp]: owned by the host's composition, not persisted in the saver.
     *
     * While on, the "fill" zoom floor is bypassed (a globe is a ball with space
     * around it, not a plane that must cover the viewport) and [projection] uses
     * the orthographic-sphere branch below [GLOBE_DETAIL_ZOOM]. Past that zoom the
     * sphere is indistinguishable from flat, so the projection and the renderer
     * both use the flat path bit-identically (this mirrors the native
     * `GLOBE_FLAT_THRESHOLD`, which must stay in step).
     */
    var globeEnabled: Boolean by mutableStateOf(false)

    /**
     * Which body the globe draws once active. Earth is the vector basemap;
     * Moon is the NASA SVS raster pair. Session-scoped (owned by the host's
     * composition like [globeEnabled], never persisted) and only read while the
     * globe is active — setting Moon without the globe changes nothing.
     */
    var body: MapBody by mutableStateOf(MapBody.Earth)

    /** Viewport size in logical (dp) units; null until first layout. */
    internal var viewportDp: Size? by mutableStateOf(null)

    /**
     * Task-17 pick provider: registered by the rendered surface (see
     * `VulkanMapSurface`), answering `(box, layerIds) -> placed labels`.
     * Null until a surface registers — a projection without a live renderer
     * answers [queryRenderedLabels][Projection.queryRenderedLabels] empty.
     */
    internal var labelQueryProvider: ((DpRect, Set<String>) -> List<PlacedLabel>)? by mutableStateOf(null)

    /**
     * Marker pick provider: registered by the rendered surface (see `VulkanMapSurface`),
     * answering `(xDp, yDp) -> marker id` from the renderer's id buffer. Null until a surface
     * registers — a projection without a live renderer answers [pickMarker][Projection.pickMarker]
     * with `0` (nothing) rather than hitting a dead handle.
     */
    internal var markerPickProvider: ((Float, Float) -> Long)? by mutableStateOf(null)

    /**
     * Sets the measured viewport and enforces the minimum "fill" zoom so the map
     * always covers the viewport (no blank margins when zoomed all the way out),
     * matching maplibre's behavior.
     */
    internal fun setViewport(size: Size) {
        viewportDp = size
        // A globe is a ball with space around it, not a plane that must cover the
        // viewport — enforcing the fill floor would forbid zooming out to the globe.
        if (globeEnabled) return
        val floor = fillZoom(size)
        if (position.zoom < floor) position = position.copy(zoom = floor)
    }

    /** Smallest zoom at which the world fills [vp]'s larger dimension. */
    private fun fillZoom(vp: Size): Double {
        val dim = maxOf(vp.width, vp.height).toDouble()
        if (dim <= 0.0) return 0.0
        return log2(dim / Mercator.TILE_SIZE).coerceAtLeast(0.0)
    }

    /**
     * Current projection, or null before the viewport has been measured.
     *
     * [derivedStateOf], not a plain getter: [MapMarker] reads this once per marker per frame
     * during a pan, and the previous getter allocated a fresh [Projection] on every read.
     * With photos' cluster count that is real GC pressure.
     */
    val projection: Projection? by derivedStateOf {
        viewportDp?.let { vp ->
            Projection(
                position.target, position.zoom, vp.width, vp.height,
                position.pitch, position.bearing, labelQueryProvider, markerPickProvider,
                globe = globeEnabled && position.zoom < GLOBE_DETAIL_ZOOM,
            )
        }
    }

    /** Suspends until the viewport is measured, then returns the projection. */
    suspend fun awaitProjection(): Projection =
        snapshotFlow { projection }.filterNotNull().first()

    /** Linearly interpolate center + zoom to [target] over [durationMs]. */
    suspend fun animateTo(target: CameraPosition, durationMs: Int = 500) {
        val start = position
        // The short way round, so a turn from 350 to 10 degrees goes through north rather
        // than the long way through south. Inert on the phone, where both ends are zero.
        val turn = angleDelta(start.bearing, target.bearing)
        // Longitude likewise: a flight from 179 E to 179 W hops the antimeridian rather than
        // sweeping across the whole planet.
        val lonStep = angleDelta(start.target.longitude, target.target.longitude)
        Animatable(0f).animateTo(1f, tween(durationMs)) {
            val t = value.toDouble()
            position = CameraPosition(
                target = GeoPoint(
                    wrapLongitude(start.target.longitude + lonStep * t),
                    lerp(start.target.latitude, target.target.latitude, t),
                ),
                zoom = lerp(start.zoom, target.zoom, t),
                bearing = start.bearing + turn * t,
                pitch = lerp(start.pitch, target.pitch, t),
            )
        }
    }

    /**
     * Animated zoom by [deltaZoom] levels, keeping the geographic point under
     * [anchorDp] fixed for the whole flight (double-tap to zoom).
     *
     * On an active globe the anchor is the sphere point under the finger
     * (limb-clamped by the projection), so the flight keeps that ground fixed
     * rather than the flat point the Mercator branch would name.
     */
    internal suspend fun animateZoomBy(
        deltaZoom: Double,
        anchorDp: Offset,
        minZoom: Double,
        maxZoom: Double,
        durationMs: Int = 250,
    ) {
        val vp = viewportDp ?: return
        val start = position
        val target = clampZoom(start.zoom + deltaZoom, vp, minZoom, maxZoom)
        if (target == start.zoom) return
        Animatable(0f).animateTo(1f, tween(durationMs)) {
            position = anchoredZoom(start, lerp(start.zoom, target, value.toDouble()), anchorDp, vp)
        }
    }

    /**
     * Applies a "quick zoom" drag (double-tap, hold, then swipe). [dragDp] is
     * the vertical distance dragged since the gesture began — down is positive
     * and zooms in, up zooms out — and [from] is the camera as of that start,
     * so the zoom is absolute rather than accumulated frame by frame. The
     * geographic point under [anchorDp] stays fixed (sphere-anchored on an
     * active globe, like [animateZoomBy]).
     */
    internal fun onQuickZoom(
        from: CameraPosition,
        anchorDp: Offset,
        dragDp: Float,
        minZoom: Double,
        maxZoom: Double,
    ) {
        val vp = viewportDp ?: return
        if (vp.height <= 0f) return
        val delta = dragDp / vp.height * QUICK_ZOOM_LEVELS_PER_VIEWPORT
        position = anchoredZoom(from, clampZoom(from.zoom + delta, vp, minZoom, maxZoom), anchorDp, vp)
    }

    /** Clamps [zoom] to [minZoom]..[maxZoom], never below the "fill" floor for [vp]. */
    private fun clampZoom(zoom: Double, vp: Size, minZoom: Double, maxZoom: Double): Double =
        // Globe: no fill floor (see `setViewport`).
        if (globeEnabled) zoom.coerceIn(minZoom, maxZoom)
        else zoom.coerceIn(maxOf(minZoom, fillZoom(vp)), maxZoom)

    /**
     * Applies a transform gesture, anchoring the geographic point under
     * [centroidDp] so pinch-zoom keeps that point fixed. Deltas are in logical
     * (dp) units.
     *
     * [rotationDeg] is the two-finger twist since the last event, in degrees
     * (Compose's `detectTransformGestures` convention: counterclockwise positive).
     * The camera bearing is clockwise-from-north, so it is subtracted. Ignored unless
     * [rotateEnabled]; pan and zoom never reset the bearing either way (the position is
     * `copy`ied, not rebuilt).
     *
     * On an active globe (flag on and zoomed out past the detail threshold) pan
     * rotates the sphere through the same basis the renderer draws
     * ([globePanBy]), and the twist/tilt gestures are ignored: the globe path
     * is north-up level, and tilting a planet is a separate workstream.
     */
    internal fun onGesture(
        centroidDp: Offset,
        panDp: Offset,
        zoomChange: Float,
        rotationDeg: Float = 0f,
        minZoom: Double,
        maxZoom: Double,
        scrollEnabled: Boolean,
        zoomEnabled: Boolean,
        rotateEnabled: Boolean = true,
    ) {
        val vp = viewportDp ?: return
        // Never zoom out past a full-screen world — unless a globe, which has space around it.
        val effectiveMinZoom = if (globeEnabled) minZoom else maxOf(minZoom, fillZoom(vp))
        var zoom = position.zoom

        // Globe: rotate the sphere under the finger; zoom stays Mercator-anchored
        // so the threshold crossing is continuous.
        if (globeEnabled && position.zoom < GLOBE_DETAIL_ZOOM) {
            var center = position.target
            if (scrollEnabled && (panDp.x != 0f || panDp.y != 0f)) {
                center = globePanBy(center, panDp, vp, position.zoom)
            }
            if (zoomEnabled && zoomChange != 1f && zoomChange > 0f) {
                val newZoom = (zoom + log2(zoomChange.toDouble())).coerceIn(effectiveMinZoom, maxZoom)
                if (newZoom != zoom) {
                    val zoomed = anchoredZoom(position.copy(target = center), newZoom, centroidDp, vp)
                    center = zoomed.target
                    zoom = zoomed.zoom
                }
            }
            position = position.copy(target = center, zoom = zoom, bearing = 0.0, pitch = 0.0)
            return
        }

        // Pan: dragging content one way shifts the world the same way, so the
        // center moves opposite to the pan. The gesture arrives in screen space but the
        // Mercator math below is in world space, so inverse-rotate it by the bearing first
        // (mirroring `Projection.positionFromScreenLocation`'s inverse step). At zero
        // bearing the rotation is the identity and this is unchanged.
        val panWorld = unrotatePan(panDp, position.bearing)
        val cWorld = Mercator.project(position.target.longitude, position.target.latitude, zoom)
        var cx = cWorld.x
        var cy = cWorld.y
        if (scrollEnabled) {
            cx -= panWorld.x
            cy -= panWorld.y
        }
        var center = Mercator.unproject(cx, cy, zoom)

        // Zoom about the centroid: keep the geo point under the fingers fixed.
        if (zoomEnabled && zoomChange != 1f && zoomChange > 0f) {
            val newZoom = (zoom + log2(zoomChange.toDouble())).coerceIn(effectiveMinZoom, maxZoom)
            if (newZoom != zoom) {
                val zoomed = anchoredZoom(position.copy(target = center), newZoom, centroidDp, vp)
                center = zoomed.target
                zoom = zoomed.zoom
            }
        }

        var bearing = position.bearing
        if (rotateEnabled && rotationDeg != 0f) {
            bearing = wrapBearing(bearing - rotationDeg * ROTATION_SENSITIVITY)
        }

        // `copy`, not a fresh `CameraPosition`: a gesture pans and zooms, and rebuilding
        // the position from scratch would silently reset the bearing to north.
        position = position.copy(target = center, zoom = zoom, bearing = bearing)
    }

    /**
     * Applies a two-finger twist rotation. [deltaDeg] is clockwise-from-north positive
     * (already converted from the gesture detector's convention by [onGesture]); it wraps
     * 0–360 rather than clamping, so spinning past north continues through it.
     *
     * Ignored on an active globe, which is north-up level by construction.
     */
    internal fun onRotate(deltaDeg: Double) {
        if (deltaDeg == 0.0) return
        if (globeEnabled && position.zoom < GLOBE_DETAIL_ZOOM) return
        position = position.copy(bearing = wrapBearing(position.bearing + deltaDeg))
    }

    /**
     * Applies a two-finger vertical-drag tilt. [deltaDp] is the vertical movement since the last
     * event (Compose's y, down positive); dragging **up** tilts into the map (raises the pitch),
     * matching Google Maps. Clamped to `0.0..`[MAX_PITCH]; nothing else about the camera moves.
     *
     * Ignored on an active globe, whose shaders draw level: tilting a planet is
     * a separate workstream.
     */
    internal fun onTilt(deltaDp: Float) {
        if (globeEnabled && position.zoom < GLOBE_DETAIL_ZOOM) return
        val next = (position.pitch - deltaDp * PITCH_DEG_PER_DP).coerceIn(0.0, MAX_PITCH)
        if (next != position.pitch) position = position.copy(pitch = next)
    }

    /**
     * Whether the globe path is currently drawing the sphere: the toggle on and
     * zoomed out past the detail threshold, mirroring the native
     * `globe_active` and [Projection]'s globe branch.
     */
    internal val isGlobeActive: Boolean
        get() = globeEnabled && position.zoom < GLOBE_DETAIL_ZOOM
}

/**
 * Rotate the globe centre by a screen-space drag [panDp] (Dp, down-positive),
 * as seen through a viewport [vp]: dragging content right moves the planet
 * right, so the centre moves the opposite way along its own parallel/meridian.
 *
 * One Dp at the centre is `1 / r` radians of great circle, with `r` the globe radius
 * ([globeRadius], mirroring the native `globe_radius`); a longitude step is that over
 * `cos(lat)`. Longitude wraps; latitude clamps to the Mercator bound the flat path honours.
 * Unused [vp]: kept so the signature matches the flat pan it replaces at the call site.
 */
internal fun globePanBy(center: GeoPoint, panDp: Offset, vp: Size, zoom: Double): GeoPoint {
    val r = globeRadius(zoom, center.latitude)
    if (r <= 0.0) return center
    val degPerDp = Math.toDegrees(1.0 / r)
    val lonScale = 1.0 / cos(Math.toRadians(center.latitude)).coerceAtLeast(0.05)
    val lon = wrapLongitude(center.longitude - panDp.x * degPerDp * lonScale)
    val lat = (center.latitude + panDp.y * degPerDp).coerceIn(-MAX_GLOBE_LAT, MAX_GLOBE_LAT)
    return GeoPoint(lon, lat)
}

/** Latitude clamp for globe panning: the Mercator bound the flat path honours. */
private const val MAX_GLOBE_LAT = 85.05112878

/** Largest tilt the renderer supports, in degrees. Mirrors the native `PITCH_MAX_DEG` cap. */
const val MAX_PITCH: Double = 65.0

/** Degrees of tilt per Dp of two-finger vertical drag: a ~150 Dp drag sweeps the full range. */
private const val PITCH_DEG_PER_DP = 0.4

/**
 * How much of the twist gesture reaches the bearing: 1.0 tracks the fingers exactly.
 *
 * Below 1.0 would feel laggy against the fingers; above would overshoot them. The detector
 * reports the rotation since the last event, so this is a pure gain, not a threshold.
 */
private const val ROTATION_SENSITIVITY = 1.0f

/** Bearing wrapped to 0–360: spinning past north continues through it rather than clamping. */
internal fun wrapBearing(bearing: Double): Double =
    ((bearing % FULL_CIRCLE_DEG) + FULL_CIRCLE_DEG) % FULL_CIRCLE_DEG

/** Shortest signed turn from [from] to [to], in degrees. */
private fun angleDelta(from: Double, to: Double): Double =
    ((to - from + ANGLE_WRAP_OFFSET_DEG) % FULL_CIRCLE_DEG) - HALF_CIRCLE_DEG

private const val FULL_CIRCLE_DEG = 360.0
private const val HALF_CIRCLE_DEG = 180.0
private const val ANGLE_WRAP_OFFSET_DEG = 540.0

/** Zoom levels covered by a quick-zoom drag across the full viewport height. */
private const val QUICK_ZOOM_LEVELS_PER_VIEWPORT = 4.0

/** [from] re-centered so the geo point under [anchorDp] is still there at [newZoom]. */
private fun CameraState.anchoredZoom(
    from: CameraPosition,
    newZoom: Double,
    anchorDp: Offset,
    vp: Size,
): CameraPosition {
    // On an active globe the zoom pivot is the sphere point under the finger,
    // which the projection limb-clamps: zooming keeps that ground fixed, and
    // the centre tracks it so the flight reads as diving toward the finger.
    // Bearing/pitch are level on the globe path, so no inverse-rotation.
    if (globeEnabled && from.zoom < GLOBE_DETAIL_ZOOM) {
        val globeNow = Projection(
            center = from.target,
            zoom = from.zoom,
            widthDp = vp.width,
            heightDp = vp.height,
            globe = true,
        )
        val geo = globeNow.positionFromScreenLocation(
            DpOffset(anchorDp.x.dp, anchorDp.y.dp),
        )
        val globeNext = Projection(
            center = geo,
            zoom = newZoom,
            widthDp = vp.width,
            heightDp = vp.height,
            globe = newZoom < GLOBE_DETAIL_ZOOM,
        )
        // Keep the picked ground under the finger: re-derive the centre whose
        // projection of `geo` at the new zoom lands back on the anchor.
        val moved = globeNext.screenLocationFromPosition(geo)
        val want = DpOffset(anchorDp.x.dp, anchorDp.y.dp)
        // Screen delta (Dp) back to a centre shift, through the sphere's centre scale:
        // `globeNext` is centred on `geo`, so `moved` is the viewport centre, and putting
        // `geo` at `want` means moving the centre the *opposite* way by the offset. A Dp is
        // `1 / r` radians of latitude and `1 / (r cos lat)` of longitude; screen y is down.
        val r = globeRadius(newZoom, geo.latitude)
        val cosLat = cos(Math.toRadians(geo.latitude)).coerceAtLeast(0.05)
        val dLon = -Math.toDegrees((want.x.value - moved.x.value) / (r * cosLat))
        val dLat = Math.toDegrees((want.y.value - moved.y.value) / r)
        return from.copy(
            target = GeoPoint(
                wrapLongitude(geo.longitude + dLon),
                (geo.latitude + dLat).coerceIn(-MAX_GLOBE_LAT, MAX_GLOBE_LAT),
            ),
            zoom = newZoom,
        )
    }
    // The anchor is a screen point; its offset from the viewport centre is what the
    // Mercator math consumes, so inverse-rotate that offset by the bearing first (same
    // step as the pan in `onGesture`). At zero bearing this is the identity.
    val rawDx = anchorDp.x - vp.width / 2.0
    val rawDy = anchorDp.y - vp.height / 2.0
    val anchorWorld = unrotatePan(Offset(rawDx.toFloat(), rawDy.toFloat()), from.bearing)
    val dx = anchorWorld.x.toDouble()
    val dy = anchorWorld.y.toDouble()
    val cw = Mercator.project(from.target.longitude, from.target.latitude, from.zoom)
    val geo = Mercator.unproject(cw.x + dx, cw.y + dy, from.zoom)
    val gw = Mercator.project(geo.longitude, geo.latitude, newZoom)
    return from.copy(target = Mercator.unproject(gw.x - dx, gw.y - dy, newZoom), zoom = newZoom)
}

/**
 * A screen-space delta back into world space: the inverse of [Projection]'s bearing rotation
 * (`x = cos*sx - sin*sy, y = sin*sx + cos*sy`). Short-circuited at zero so the north-up path
 * is bit-identical.
 */
private fun unrotatePan(pan: Offset, bearingDeg: Double): Offset {
    if (bearingDeg == 0.0) return pan
    val radians = Math.toRadians(bearingDeg)
    val cos = kotlin.math.cos(radians)
    val sin = kotlin.math.sin(radians)
    return Offset((cos * pan.x - sin * pan.y).toFloat(), (sin * pan.x + cos * pan.y).toFloat())
}

private fun lerp(start: Double, end: Double, t: Double): Double = start + (end - start) * t

/**
 * Saves the camera across configuration change and process death.
 *
 * Only `(lon, lat, zoom, bearing, pitch)`: [CameraState.viewportDp] is re-measured by the next
 * layout pass and [CameraState.labelQueryProvider] is re-registered by the surface, so
 * persisting either would restore a value that is immediately overwritten — and a stale
 * viewport would produce a wrong projection for one frame.
 */
private val CameraStateSaver = listSaver<CameraState, Double>(
    save = {
        listOf(
            it.position.target.longitude,
            it.position.target.latitude,
            it.position.zoom,
            it.position.bearing,
            it.position.pitch,
        )
    },
    // `getOrElse` rather than `it[n]`: a bundle written before the bearing or pitch existed is
    // three or four long, and a restored camera is not worth an IndexOutOfBounds.
    restore = {
        CameraState(
            CameraPosition(
                GeoPoint(it[0], it[1]),
                it[2],
                it.getOrElse(3) { 0.0 },
                it.getOrElse(4) { 0.0 },
            )
        )
    },
)

/**
 * Remembers a [CameraState] seeded with [initial], surviving rotation and process death.
 *
 * [initial] applies only on first composition; a restored camera wins over it. Hosts that
 * refit the camera from a `LaunchedEffect` — `taxi` and `fooddelivery` do — overwrite the
 * restored position anyway, so restoration is inert for them.
 */
@Composable
fun rememberCameraState(initial: CameraPosition = CameraPosition()): CameraState =
    rememberSaveable(saver = CameraStateSaver) { CameraState(initial) }
