package com.vayunmathur.maps.ui.map
import com.vayunmathur.maps.domain.visibleBoundsOrWorld

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapMarker
import com.vayunmathur.library.map.MarkerIcon
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.data.OfflineRouterTransit
import kotlinx.coroutines.delay

/**
 * How often the in-service vehicle set is recomputed, mirroring the ~1 Hz cadence the departures
 * board countdown uses ([com.vayunmathur.maps.ui.DeparturesSheet]). The native
 * `activeVehicles` folds schedule + realtime delay into each recompute; between recomputes the
 * renderer holds each sprite's last pushed position. One second of drift is a few metres at transit
 * speeds — small at the zooms this draws at — so a plain re-push each tick is the whole animation,
 * with no per-frame predictor. The native `Vehicle` carries a bearing but no speed, so there is no
 * cheap on-device dead-reckoning to do, and the shared sprite path is geography-agnostic; if smooth
 * 60 fps tweening is wanted later it belongs in the renderer's overlay draw, keyed on the stable
 * per-trip [OfflineRouter.Vehicle.id].
 */
private const val VEHICLE_TICK_MS = 1_000L

/**
 * Below this zoom the visible bbox spans too many in-service trips for a 1 Hz recompute to stay
 * cheap (the plan's own note on bounding the enumeration to the viewport), and the sprites would be
 * an unreadable swarm anyway. Matches the spirit of the traffic prefetch's own zoom gate.
 */
private const val VEHICLE_MIN_ZOOM = 11.0

/**
 * Whether the simulated vehicle sprites draw at all. True: the sprite atlas
 * carries dedicated solid-badge vehicle pictograms (`vehicle-bus/tram/train/
 * ferry`, white Maki glyphs on transport blue — built by
 * `analysis/spritepack/pack.py`), visually distinct from the pale ambient POI
 * badges so a vehicle dwelling on a stop no longer reads as a duplicated
 * station POI. Was false while the vehicle ids borrowed POI icons.
 */
private const val VEHICLE_SPRITES_ENABLED = true

/**
 * How far the bbox centre may drift (degrees) before a recompute is forced.
 * A degree of latitude is ~111 km, so this is ~50 m: a static camera reuses
 * the last enumeration rather than paying a JNI round-trip plus a full marker
 * rebuild every second for sprites that moved a few metres.
 */
private const val VEHICLE_BBOX_TOLERANCE_DEG = 0.0005

/**
 * How many consecutive 1 Hz ticks may reuse the last enumeration while the
 * camera is static. Positions are interpolated per recompute, so an unbounded
 * skip would freeze the sprites; this bounds the staleness to a few seconds
 * (a few dozen metres at transit speeds) while still cutting the steady-state
 * JNI + recomposition rate of a stationary map.
 */
private const val VEHICLE_MAX_REUSE_TICKS = 3

/**
 * The simulated in-service transit vehicles serving the selected stop's trips, recomputed at ~1 Hz
 * and mapped to renderer markers, or an empty list when no stop is selected, when the transit layer
 * is off, when the surface is hidden, or when the camera is zoomed too far out (see
 * [VEHICLE_MIN_ZOOM]).
 *
 * Drive [com.vayunmathur.library.map.VectorMap]'s `vehicles` with the return value: it is pushed on
 * its own cadence, apart from the app pins, so a vehicle recompute never churns the pins and the
 * moving sprites stay out of the pin tap-pick. The loop samples the camera's live bounds each tick,
 * so a pan or zoom re-targets the enumeration without restarting the effect. Gated on the lifecycle
 * with the same ON_START/ON_STOP observer the renderer uses, so a backgrounded or hidden map stops
 * recomputing and clears its vehicles rather than leaving a stale swarm behind.
 *
 * [servingTripIds] is the set of pack trip ids behind the selected stop's departures board
 * ([com.vayunmathur.maps.data.transit.Departure.tripVehicleId], bit-identical to
 * [OfflineRouter.Vehicle.id]); only vehicles in the set draw, so the map shows the selected
 * station's own lines instead of every trip in the bbox. While the board is still loading (or its
 * entries are online-only with no trip id) the set is empty and nothing draws.
 */
@Composable
fun rememberTransitVehicles(
    camera: CameraState,
    transitEnabled: Boolean,
    selectedStop: TransitStop?,
    servingTripIds: Set<Long>,
): List<MapMarker> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(false) }
    var vehicles by remember { mutableStateOf(emptyList<MapMarker>()) }

    // The same ON_START/ON_STOP gate VulkanMapSurface stops the renderer on: a STARTED-but-not-
    // RESUMED surface (split-screen, PiP) is on screen and must keep its vehicles moving.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> started = true
                Lifecycle.Event.ON_STOP -> started = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(transitEnabled, started, selectedStop, servingTripIds) {
        // See VEHICLE_SPRITES_ENABLED: the overlay draws no vehicle sprites
        // until the atlas carries dedicated vehicle art. Clears any shown.
        if (!transitEnabled || !started || !VEHICLE_SPRITES_ENABLED) {
            vehicles = emptyList()
            return@LaunchedEffect
        }
        // Last enumeration, reused while the camera is static (see below).
        var lastCentreLat = Double.NaN
        var lastCentreLon = Double.NaN
        var lastZoom = Double.NaN
        var reuseTicks = 0
        while (true) {
            // No stop selected: clear and keep ticking rather than returning, so a later
            // selection starts drawing without waiting on recomposition to relaunch the effect.
            if (selectedStop == null) {
                vehicles = emptyList()
                delay(VEHICLE_TICK_MS)
                continue
            }
            val bounds = camera.visibleBoundsOrWorld()
            val zoom = camera.position.zoom
            val centreLat = (bounds.south + bounds.north) / 2.0
            val centreLon = (bounds.west + bounds.east) / 2.0
            // A static camera reuses the last enumeration: the native call
            // interpolates positions per recompute, but a second of drift is
            // metres at transit speeds while the JNI round-trip plus the
            // marker-list rebuild and re-push cost every tick. Bounded by
            // VEHICLE_MAX_REUSE_TICKS so the sprites never freeze; reusing the
            // same list instance also skips recomposition downstream. A stop
            // change forces a recompute: the serving set moved even if the
            // camera did not (the LaunchedEffect keys relaunch the loop, which
            // resets these sentinels).
            val cameraStatic = zoom >= VEHICLE_MIN_ZOOM &&
                kotlin.math.abs(centreLat - lastCentreLat) < VEHICLE_BBOX_TOLERANCE_DEG &&
                kotlin.math.abs(centreLon - lastCentreLon) < VEHICLE_BBOX_TOLERANCE_DEG &&
                kotlin.math.abs(zoom - lastZoom) < 0.25
            if (!cameraStatic || reuseTicks >= VEHICLE_MAX_REUSE_TICKS) {
                vehicles = if (zoom < VEHICLE_MIN_ZOOM) {
                    emptyList()
                } else {
                    OfflineRouterTransit.activeVehicles(
                        context,
                        minLat = bounds.south,
                        minLon = bounds.west,
                        maxLat = bounds.north,
                        maxLon = bounds.east,
                    ).filter { it.id in servingTripIds }.map { v ->
                        MapMarker(
                            id = v.id,
                            position = GeoPoint(longitude = v.lon, latitude = v.lat),
                            icon = gtfsModeToMarkerIcon(v.mode),
                            // The pack's GTFS route_color drives the ring under the
                            // sprite so the vehicle reads in its line's colour; 0
                            // (no colour in the pack) draws no ring.
                            color = v.colour,
                        )
                    }
                }
                lastCentreLat = centreLat
                lastCentreLon = centreLon
                lastZoom = zoom
                reuseTicks = 0
            } else {
                reuseTicks++
            }
            delay(VEHICLE_TICK_MS)
        }
    }
    return vehicles
}

/**
 * The renderer marker icon for a coarse GTFS mode label (see
 * [OfflineRouter.Vehicle.mode]). Trams/monorails, the rail family (subway/rail/funicular/aerial),
 * ferries and everything else (bus/trolleybus and any unknown) each fold onto one of the four
 * reserved `VEHICLE_*` sprites, which is as fine as the shared atlas draws.
 */
internal fun gtfsModeToMarkerIcon(mode: String): Int = when (mode) {
    "TRAM", "MONORAIL" -> MarkerIcon.VEHICLE_TRAM
    "SUBWAY", "RAIL", "FUNICULAR", "AERIAL" -> MarkerIcon.VEHICLE_TRAIN
    "FERRY" -> MarkerIcon.VEHICLE_FERRY
    else -> MarkerIcon.VEHICLE_BUS
}
