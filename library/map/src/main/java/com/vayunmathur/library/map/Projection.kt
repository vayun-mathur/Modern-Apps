package com.vayunmathur.library.map

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One tile-baked placed label hit by [Projection.queryRenderedLabels].
 *
 * The maps-side adapter maps [layerId] (our flat ids) to its own layer-id
 * sets and builds its `Feature1` (`Feature<Geometry.Point, JsonObject>`,
 * properties `{kind, name, name:en}`) for `SpecificFeature.parse`.
 */
data class PlacedLabel(
    val layerId: String,
    val name: String,
    /**
     * The feature's **own** archive kind (`cafe`, `hotel`, `station`, …), not its layer's.
     *
     * A symbol layer filters on several kinds — `poi-food` draws four — so before this was
     * read from the feature it reported the layer's first whitelist entry, and every food POI
     * came back as `restaurant`.
     */
    val kind: String,
    val position: GeoPoint,
    /**
     * The archive's stable id for the feature, or `0` when it has none.
     *
     * Only `places` and `poi` features carry one — they are the only pure points, and the
     * tiler merges everything else. The low two bits say which OSM id space it came from
     * (1 node, 2 way, 3 relation) because those three sequences overlap; the id itself is
     * the remaining bits.
     */
    val featureId: Long = 0L,
    /**
     * The tagged OSM relation id of the admin boundary this label names, baked at build
     * time into the tile's `region_links` table, or `0` (`REGION_NONE`) when linked to
     * nothing. The 7th field of the native pick row; legacy 6-field rows parse as `0`.
     */
    val regionId: Long = 0L,
)

/**
 * Immutable snapshot of the camera (center + zoom + pitch) and viewport used to map
 * between geographic [GeoPoint]s and on-screen [DpOffset]s. Mirrors the subset
 * of maplibre-compose's projection API the apps call, so migration is an
 * import swap.
 *
 * The map center projects to the viewport center; offsets are density
 * independent (`Dp`) and measured from the viewport's top-left corner.
 *
 * At [pitchDeg] `== 0` this is the plain orthographic inverse it always was. When tilted it
 * mirrors the native perspective in `camera::Camera` exactly — [screenLocationFromPosition]
 * projects through the same divide, [positionFromScreenLocation] intersects the eye ray with the
 * ground plane — so Compose overlays land where the renderer drew the basemap under them.
 * [bearingDeg] rotates the map clockwise-from-north exactly as the native `Camera::rotation`
 * 2x2 does, so the Compose overlays agree with the rotated basemap; `0.0` is north-up.
 *
 * [labelQuery] answers [queryRenderedLabels]: null until a rendered surface
 * registers one (see `VulkanMapSurface`), so a projection without a live
 * renderer answers empty rather than crashing.
 */
class Projection internal constructor(
    private val center: GeoPoint,
    private val zoom: Double,
    private val widthDp: Float,
    private val heightDp: Float,
    private val pitchDeg: Double = 0.0,
    private val bearingDeg: Double = 0.0,
    private val labelQuery: ((DpRect, Set<String>) -> List<PlacedLabel>)? = null,
    private val markerPick: ((Float, Float) -> Long)? = null,
    /**
     * Whether this projection draws the orthographic-sphere globe rather than
     * the flat Mercator map. False for every host but `maps` with the globe
     * toggle on (and always false past [GLOBE_DETAIL_ZOOM], where the sphere is
     * indistinguishable from flat — see [CameraState.globeEnabled]).
     */
    private val globe: Boolean = false,
) {
    private val halfW = widthDp / 2.0
    private val halfH = heightDp / 2.0
    // Perspective constants, mirroring `camera::Camera::perspective` so this agrees with what the
    // renderer drew. `d` (camera-to-centre distance) cancels at pitch 0, so it only sets the
    // foreshortening strength; only used on the tilted branches below.
    private val d = 1.5 * heightDp
    private val fx = d / halfW
    private val fy = d / halfH

    /** Screen location (from the viewport top-left) of a geographic [position]. */
    fun screenLocationFromPosition(position: GeoPoint): DpOffset {
        if (globe) return globeScreenLocationFromPosition(position)
        val c = Mercator.project(center.longitude, center.latitude, zoom)
        val p = Mercator.project(position.longitude, position.latitude, zoom)
        val dx = p.x - c.x
        val dy = p.y - c.y
        // Bearing rotation first (mirroring `Camera::rotation`): a world offset becomes a
        // screen offset via sx = cos*dx + sin*dy, sy = -sin*dx + cos*dy. At zero bearing
        // this is the identity, so the north-up path is unchanged.
        val sx: Double
        val sy: Double
        if (bearingDeg == 0.0) {
            sx = dx
            sy = dy
        } else {
            val radians = Math.toRadians(bearingDeg)
            val cos = cos(radians)
            val sin = sin(radians)
            sx = cos * dx + sin * dy
            sy = -sin * dx + cos * dy
        }
        if (pitchDeg == 0.0) {
            return DpOffset((sx + halfW).toFloat().dp, (sy + halfH).toFloat().dp)
        }
        val pitch = Math.toRadians(pitchDeg)
        val psin = sin(pitch)
        val pcos = cos(pitch)
        val w = d - psin * sy
        // Behind the eye — only past the horizon, which the pitch cap keeps off-screen. Push it
        // far off rather than dividing by a non-positive w.
        if (w <= 0.0) return DpOffset(OFF_SCREEN_DP.dp, OFF_SCREEN_DP.dp)
        val screenX = halfW + (fx * sx / w) * halfW
        val screenY = halfH + (fy * pcos * sy / w) * halfH
        return DpOffset(screenX.toFloat().dp, screenY.toFloat().dp)
    }

    /**
     * Geographic position under a screen [offset] (from the viewport top-left).
     *
     * The tilted branch intersects the eye ray with the flat ground **plane**, ignoring terrain
     * elevation: over relief this leaves a bounded vertical error, which is fine for gestures
     * (pan/anchor follow the finger approximately). Exact ground/feature hits under tilt go through
     * the GPU instead — label picks via `pickLabels` and precise hits via `pickAt`'s id/depth
     * readback — both of which are terrain-correct because they read what was actually drawn.
     */
    fun positionFromScreenLocation(offset: DpOffset): GeoPoint {
        if (globe) return globePositionFromScreenLocation(offset)
        val c = Mercator.project(center.longitude, center.latitude, zoom)
        val sx: Double
        val sy: Double
        if (pitchDeg == 0.0) {
            sx = offset.x.value - halfW
            sy = offset.y.value - halfH
        } else {
            val pitch = Math.toRadians(pitchDeg)
            val psin = sin(pitch)
            val pcos = cos(pitch)
            val ndcX = (offset.x.value - halfW) / halfW
            val ndcY = (offset.y.value - halfH) / halfH
            // Invert clip.y = fy*cos*sy / (d - sin*sy) for sy, then clip.x for sx. The denominator
            // only vanishes at/above the horizon, which the pitch cap keeps off-screen; clamp to a
            // tiny positive so a query exactly on it maps far away rather than to a NaN.
            val denom = (fy * pcos + ndcY * psin).coerceAtLeast(MIN_PERSPECTIVE_DENOM)
            sy = ndcY * d / denom
            val w = d - psin * sy
            sx = ndcX * w / fx
        }
        // Inverse bearing rotation (mirroring `Camera::screen_to_world`): the screen offset back
        // to a world offset via x = cos*sx - sin*sy, y = sin*sx + cos*sy.
        if (bearingDeg == 0.0) {
            return Mercator.unproject(c.x + sx, c.y + sy, zoom)
        }
        val radians = Math.toRadians(bearingDeg)
        val cos = cos(radians)
        val sin = sin(radians)
        return Mercator.unproject(c.x + cos * sx - sin * sy, c.y + sin * sx + cos * sy, zoom)
    }

    /**
     * Orthographic-sphere forward projection: lon/lat to the screen point, or far
     * off-screen when the point is on the far side of the planet.
     *
     * Mirrors the native `globe_project` exactly: the same [GLOBE_DETAIL_ZOOM]
     * sphere radius, the same 3D basis, so Compose overlays land where the
     * renderer drew the basemap under them. Bearing/pitch are folded in by the
     * caller's pre-rotation of the basis only insofar as the native path does —
     * the globe path is north-up level (tilt under globe is disabled at call
     * sites), so like every other globe helper this takes no bearing/pitch.
     */
    private fun globeScreenLocationFromPosition(position: GeoPoint): DpOffset {
        val (x, y, z) = globeProject(position.longitude, position.latitude)
        if (z < 0.0) return DpOffset(OFF_SCREEN_DP.dp, OFF_SCREEN_DP.dp)
        // Screen y is down-positive from the top-left; north (+y in the sphere
        // basis) renders up, matching `globe_anchor_to_screen` on the native
        // side and every globe vertex shader (`halfH - y`).
        return DpOffset((halfW + x).toFloat().dp, (halfH - y).toFloat().dp)
    }

    /**
     * Orthographic-sphere inverse: the lon/lat under a screen point, or the
     * limb-clamped point when the tap is off the disc (the nearest visible
     * ground, so a tap on space still reverse-geocodes to the edge in view).
     */
    private fun globePositionFromScreenLocation(offset: DpOffset): GeoPoint {
        val dx = offset.x.value - halfW
        val dy = halfH - offset.y.value
        val r = globeRadius()
        val dist = sqrt(dx * dx + dy * dy)
        // Off the disc: clamp to the limb along the same ray.
        val (nx, ny) = if (dist > r) Pair(dx / dist * r, dy / dist * r) else Pair(dx, dy)
        val nz = sqrt((r * r - nx * nx - ny * ny).coerceAtLeast(0.0))
        return globeUnproject(nx, ny, nz)
    }

    /**
     * 3D unit-sphere point for lon/lat *relative to the camera centre*: rotates
     * the ECEF-style position so the centre is at (0, 0, 1), then scales by the
     * globe radius. The far side is z < 0.
     */
    private fun globeProject(longitude: Double, latitude: Double): Triple<Double, Double, Double> {
        val (x, y, z) = globePoint(center.longitude, center.latitude, longitude, latitude)
        val r = globeRadius()
        return Triple(x * r, y * r, z * r)
    }

    private fun globeUnproject(nx: Double, ny: Double, nz: Double): GeoPoint {
        // Normalise back to the unit sphere (nx/ny/nz are in Dp), then invert.
        val r = globeRadius()
        val (lon, lat) = globeLonLat(center.longitude, center.latitude, nx / r, ny / r, nz / r)
        return GeoPoint(lon, lat)
    }

    /** Screen radius of the globe in Dp: see the top-level [globeRadius]. */
    private fun globeRadius(): Double = globeRadius(zoom, center.latitude)

    /**
     * Visible-ground bounds on the globe: the hemisphere cap around the centre,
     * intersected with the viewport disc. Sampled on a grid (not just corners),
     * because corners are routinely off-disc and limb-clamping them would shrink
     * the box to the wrong ground.
     */
    private fun globeVisibleBounds(): GeoBounds {
        val pts = mutableListOf(center)
        val steps = 8
        for (i in 0..steps) {
            for (j in 0..steps) {
                pts += globePositionFromScreenLocation(
                    DpOffset((widthDp * i / steps).dp, (heightDp * j / steps).dp)
                )
            }
        }
        // Antimeridian: when the disc straddles ±180 the numeric min/max spans the
        // whole planet. Detect the wrap (span > 180) and report the wrapped box.
        val lons = pts.map { it.longitude }
        val span = lons.max() - lons.min()
        val west: Double
        val east: Double
        if (span > ANTIMERIDIAN_SPAN_DEG) {
            west = pts.filter { it.longitude > 0 }.minOf { it.longitude }
            east = pts.filter { it.longitude < 0 }.maxOf { it.longitude }
        } else {
            west = lons.min()
            east = lons.max()
        }
        return GeoBounds(
            west = west,
            south = pts.minOf { it.latitude },
            east = east,
            north = pts.maxOf { it.latitude },
        )
    }

    /** The lon/lat bounds of the currently visible viewport. */
    fun queryVisibleBoundingBox(): GeoBounds {
        if (globe) {
            // The visible hemisphere around the centre: corners may be off-disc, and
            // sampling them would clamp to the limb and shrink the box wrongly.
            return globeVisibleBounds()
        }
        if (pitchDeg == 0.0 && bearingDeg == 0.0) {
            val topLeft = positionFromScreenLocation(DpOffset(0.dp, 0.dp))
            val bottomRight = positionFromScreenLocation(DpOffset(widthDp.dp, heightDp.dp))
            return GeoBounds(
                west = topLeft.longitude,
                south = bottomRight.latitude,
                east = bottomRight.longitude,
                north = topLeft.latitude,
            )
        }
        // Tilted and/or rotated: the visible ground is a trapezoid or a rotated rectangle, so
        // take the AABB of all four screen corners' ground points. The box grows up to sqrt(2)
        // at 45 degrees — correctly, since that ground really is on screen (mirroring the
        // native `viewport_bounds` support function).
        val corners = listOf(
            positionFromScreenLocation(DpOffset(0.dp, 0.dp)),
            positionFromScreenLocation(DpOffset(widthDp.dp, 0.dp)),
            positionFromScreenLocation(DpOffset(0.dp, heightDp.dp)),
            positionFromScreenLocation(DpOffset(widthDp.dp, heightDp.dp)),
        )
        return GeoBounds(
            west = corners.minOf { it.longitude },
            south = corners.minOf { it.latitude },
            east = corners.maxOf { it.longitude },
            north = corners.maxOf { it.latitude },
        )
    }

    /**
     * Tile-baked placed labels (task 17) whose screen boxes intersect [box],
     * restricted to [layerIds, in placement order (topmost first).
     *
     * The [queryRenderedFeatures]-equivalent the maps `FeatureSource` adapter
     * needs: `source = { box, layerIds -> projection.queryRenderedLabels(box,
     * layerIds).map { it.toFeature1() } }`. Registered by `VulkanMapSurface`, so this
     * is empty only when nothing placed is under the query box.
     */
    fun queryRenderedLabels(box: DpRect, layerIds: Set<String>): List<PlacedLabel> =
        labelQuery?.invoke(box, layerIds) ?: emptyList()

    /**
     * The [MapMarker.id] of the renderer-drawn pin under a tap at ([xDp], [yDp]) (Dp from the
     * viewport top-left), or `0` when the tap hit no pin.
     *
     * The marker counterpart of [queryRenderedLabels]: it reads the renderer's GPU id buffer, so a
     * pin stays tappable under tilt — where its screen box is no longer a plain projection of its
     * lon/lat — and never trails the basemap on a pan. Registered by `VulkanMapSurface`, so this is
     * `0` only when nothing is under the finger or no rendered surface is live.
     */
    fun pickMarker(xDp: Float, yDp: Float): Long = markerPick?.invoke(xDp, yDp) ?: 0L

    companion object {
        private const val OFF_SCREEN_DP = -1e5f
        private const val MIN_PERSPECTIVE_DENOM = 1e-6
    }
}

/**
 * Past this zoom the sphere is sub-pixel from flat, so both the Kotlin
 * projection and the native renderer use the flat path bit-identically.
 * Must stay in step with the native `GLOBE_FLAT_THRESHOLD`.
 *
 * Public (not internal): the maps body switch reads it to decide when the
 * chips collapse into the Earth/Moon dropdown.
 */
const val GLOBE_DETAIL_ZOOM = 8.0

/** Zoom at which [globeRadius] starts blending toward the flat scale. Mirrors the native `GLOBE_BLEND_START`. */
const val GLOBE_BLEND_START_ZOOM = 5.0

/**
 * Screen radius of the globe in Dp at [zoom], centred on [centerLatitude]. Mirrors the native
 * `globe_radius`: half the world width when zoomed out, blending geometrically to the flat
 * Mercator centre scale (`world / (2 * PI * cos(lat))`) by [GLOBE_DETAIL_ZOOM], so the switch
 * to the flat map does not jump.
 */
fun globeRadius(zoom: Double, centerLatitude: Double): Double {
    val t = ((zoom - GLOBE_BLEND_START_ZOOM) / (GLOBE_DETAIL_ZOOM - GLOBE_BLEND_START_ZOOM)).coerceIn(0.0, 1.0)
    val c = cos(Math.toRadians(centerLatitude)).coerceAtLeast(0.05)
    val toFlat = 1.0 / (PI * c)
    return Mercator.worldSize(zoom) / 2.0 * Math.pow(toFlat, t)
}

/** Half the full circle: antimeridian wrap threshold. */
private const val ANTIMERIDIAN_SPAN_DEG = 180.0

/** [longitude] wrapped to -180..180. */
internal fun wrapLongitude(longitude: Double): Double {
    var lon = (longitude + LONGITUDE_WRAP_OFFSET) % LONGITUDE_FULL_CIRCLE
    if (lon < 0) lon += LONGITUDE_FULL_CIRCLE
    return lon - LONGITUDE_WRAP_OFFSET
}

private const val LONGITUDE_WRAP_OFFSET = 180.0
private const val LONGITUDE_FULL_CIRCLE = 360.0

/**
 * 3D unit-sphere point of ([longitude], [latitude]) in the basis facing
 * ([centerLon], [centerLat]): the centre maps to (0, 0, 1), the far side is
 * z < 0. Single implementation shared by [Projection]'s forward/inverse —
 * mirrors the native `globe_point`, which must stay in step.
 */
internal fun globePoint(
    centerLon: Double,
    centerLat: Double,
    longitude: Double,
    latitude: Double,
): Triple<Double, Double, Double> {
    // Orthonormal basis at the centre: east, north, up. The centre maps to
    // (0, 0, 1); east is +x, north is +y, up (toward the viewer) is +z.
    val latR = Math.toRadians(latitude)
    val dLon = Math.toRadians(longitude - centerLon)
    val cLatR = Math.toRadians(centerLat)
    val cosLat = cos(latR)
    val sinLat = sin(latR)
    val sy = sin(cLatR)
    val cy = cos(cLatR)
    val x = cosLat * sin(dLon)
    val y = cy * sinLat - sy * cosLat * cos(dLon)
    val z = sy * sinLat + cy * cosLat * cos(dLon)
    return Triple(x, y, z)
}

/**
 * Inverse of [globePoint]: lon/lat of a unit-sphere point in the
 * centre-facing basis. Mirrors the native `globe_lonlat`.
 */
internal fun globeLonLat(
    centerLon: Double,
    centerLat: Double,
    x: Double,
    y: Double,
    z: Double,
): GeoPoint {
    // Invert the east/north/up basis: recover ECEF, then lon/lat.
    val cLatR = Math.toRadians(centerLat)
    val sy = sin(cLatR)
    val cy = cos(cLatR)
    val ex = x
    val ey = cy * y + sy * z
    val ez = -sy * y + cy * z
    val lat = asin(ey.coerceIn(-1.0, 1.0)) * LONGITUDE_WRAP_OFFSET / PI
    val lon = centerLon + atan2(ex, ez) * LONGITUDE_WRAP_OFFSET / PI
    return GeoPoint(wrapLongitude(lon), lat.coerceIn(-LAT_MAX, LAT_MAX))
}

private const val LAT_MAX = 90.0
