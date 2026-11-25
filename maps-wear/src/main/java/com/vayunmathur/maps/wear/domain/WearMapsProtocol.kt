package com.vayunmathur.maps.wear.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.cos

/**
 * Phone <-> watch protocol for the Wear OS Maps companion.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: Gradle cannot let one
 * application module depend on another, so the wear module (`:maps:wear`,
 * applicationId `com.vayunmathur.maps`) carries a copy of this file under
 * `com.vayunmathur.maps.wear.domain`. Change both copies together; each
 * side's unit tests pin the same golden JSON so a one-sided edit fails.
 *
 * Transport (WearLink, :library:wearlink — RFCOMM, no Play services):
 * - Presence is a completed handshake for [CAPABILITY] (the appId).
 * - Data items: [REGION_PATH] phone -> watch (bounded vector snapshot of
 *   the area around the user), [ROUTE_PATH] phone -> watch (navigation
 *   overlay, or `active=false` to clear it), [POSITION_PATH] phone ->
 *   watch (small frequent user position inside the current region) and
 *   [WATCH_STATE_PATH] watch -> phone (what the watch last applied).
 * - Messages: [REQUEST_REGION_PATH] watch -> phone (ask for a fresh
 *   region; delivery needs a live session, nothing wakes a dead process).
 *
 * What a snapshot contains — and what it honestly does not:
 * Coordinates are quantized metres east/north of the snapshot centre
 * ([QUANT_STEP_M] per unit, so round-trip error is at most half a step).
 * Lines carry roads/paths/water/buildings/route geometry from
 * `:library:map`'s Kotlin geometry API (`MapGeometry`, decoding the
 * downloaded `.mamaps` archive through the renderer's own codec);
 * points carry POIs from the offline index and saved places.
 * [WearMapSnapshot.degraded] names kinds that are *unavailable* - no
 * readable archive - never kinds that were read and simply have no
 * features in this region (an ocean bbox is a complete, empty map).
 * The watch renders whatever lines it is given.
 *
 * Position source is the PHONE. The watch does not run its own GPS in
 * this design (a watch fix would cost watch battery for a worse antenna
 * and would disagree with the phone map the user navigates by); the
 * phone's location manager / navigation session is the single source.
 */
object WearMapsProtocol {
    /** WearLink appId the phone requires in the peer handshake. */
    const val CAPABILITY = "maps_wear_companion"

    /** Data-item path (phone -> watch): the [WearMapSnapshot] JSON. */
    const val REGION_PATH = "/maps/region"

    /** Data-item path (phone -> watch): the [WearRouteState] JSON. */
    const val ROUTE_PATH = "/maps/route"

    /** Data-item path (phone -> watch): the [WearPosition] JSON. */
    const val POSITION_PATH = "/maps/position"

    /** Data-item path (watch -> phone): the [WearWatchState] JSON. */
    const val WATCH_STATE_PATH = "/maps/watch-state"

    /** Message path (watch -> phone): ask the phone to republish the region. */
    const val REQUEST_REGION_PATH = "/maps/request-region"

    /** Radius (m) of the square region a snapshot covers around its centre. */
    const val REGION_RADIUS_M = 600

    /** Metres per quantized coordinate unit (round-trip error <= half). */
    const val QUANT_STEP_M = 2.0

    /** Largest region JSON in UTF-8 bytes; extraction degrades to fit. */
    const val MAX_JSON_BYTES = 48_000

    /** Largest route JSON in UTF-8 bytes; route extraction decimates to fit. */
    const val MAX_ROUTE_JSON_BYTES = 24_000

    /** A snapshot older than this (watch clock vs [WearMapSnapshot.generatedAtMillis]) reads stale. */
    const val STALE_AFTER_MS = 90_000L

    /** Wire JSON: unknown keys ignored so a newer side never breaks an older one. */
    val json: Json = Json { ignoreUnknownKeys = true }
}

/** Geometry kind of a [WearMapLine]; the watch strokes/fills by kind. */
@Serializable
enum class WearLineKind { ROAD, PATH, WATER, BUILDING, ROUTE }

/**
 * One simplified polyline/polygon, quantized metres east/north of the
 * snapshot (or route) origin as a flat `[dx, dy, dx, dy, ...]` list in
 * [WearMapsProtocol.QUANT_STEP_M] units. [closed] polygons fill.
 */
@Serializable
data class WearMapLine(
    val kind: WearLineKind,
    val pts: List<Int> = emptyList(),
    val closed: Boolean = false,
    val name: String = "",
)

/** One POI point, quantized metres east/north of the snapshot centre. */
@Serializable
data class WearMapPoint(
    /** Offline POI type id (phone `PoiCategories` numbering); -1 for saved. */
    val type: Int = 0,
    val name: String = "",
    val dx: Int = 0,
    val dy: Int = 0,
)

/** One saved place (absolute coords: the glance list needs distance, not just on-map position). */
@Serializable
data class WearSavedPlace(
    val name: String = "",
    val latE7: Int = 0,
    val lonE7: Int = 0,
)

/**
 * The phone's bounded vector snapshot for the watch ([WearMapsProtocol.REGION_PATH]).
 * Centre is E7 degrees; all geometry is quantized metres relative to it.
 */
@Serializable
data class WearMapSnapshot(
    val generatedAtMillis: Long = 0L,
    val centerLatE7: Int = 0,
    val centerLonE7: Int = 0,
    val radiusM: Int = WearMapsProtocol.REGION_RADIUS_M,
    val lines: List<WearMapLine> = emptyList(),
    val points: List<WearMapPoint> = emptyList(),
    val saved: List<WearSavedPlace> = emptyList(),
    /** True when the byte cap dropped or coarsened geometry. */
    val truncated: Boolean = false,
    /**
     * Human-honest names of what is unavailable/coarser, e.g. "roads",
     * "detail". A basemap kind appears here only when it could not be
     * sourced (no readable archive), not when it was read and the
     * region simply contains none. Empty = full.
     */
    val degraded: List<String> = emptyList(),
    /** User bearing (degrees clockwise from north) at publish, -1 = unknown. */
    val userBearingDeg: Int = -1,
)

/** The phone's current user position ([WearMapsProtocol.POSITION_PATH]); -1 bearing = unknown. */
@Serializable
data class WearPosition(
    val latE7: Int = 0,
    val lonE7: Int = 0,
    val bearingDeg: Int = -1,
    val atMillis: Long = 0L,
)

/** One maneuver in the route-detail list. */
@Serializable
data class WearManeuver(
    val text: String = "",
    /** Maneuver kind name from the phone router (e.g. TURN_LEFT); may be empty. */
    val kind: String = "",
    /** Cumulative distance from route start (m) at which this maneuver occurs. */
    val distanceFromStartM: Int = 0,
)

/**
 * Navigation overlay for the watch ([WearMapsProtocol.ROUTE_PATH]).
 * `active=false` is the explicit clear: route ended/arrived/stopped.
 * [routePts] are quantized metres relative to ([routeOriginLatE7],
 * [routeOriginLonE7]), independent of any region snapshot's centre, so a
 * region republish can never misalign the overlay.
 */
@Serializable
data class WearRouteState(
    val active: Boolean = false,
    val destinationName: String = "",
    val routeOriginLatE7: Int = 0,
    val routeOriginLonE7: Int = 0,
    val routePts: List<Int> = emptyList(),
    val maneuvers: List<WearManeuver> = emptyList(),
    val nextText: String = "",
    val distanceToNextM: Int = 0,
    val distanceRemainingM: Int = 0,
    val etaEpochMs: Long = 0L,
    val generatedAtMillis: Long = 0L,
)

/** What the watch last applied, published at [WearMapsProtocol.WATCH_STATE_PATH]. */
@Serializable
data class WearWatchState(
    val appliedGeneratedAtMillis: Long = 0L,
    val hasRegion: Boolean = false,
    val routeActive: Boolean = false,
)

/** Metres east of [originLon] for [lon] at [originLat] (equirectangular; fine at region scale). */
fun wearMetersEast(originLat: Double, originLon: Double, lon: Double): Double =
    (lon - originLon) * 111_320.0 * cos(Math.toRadians(originLat))

/** Metres north of [originLat] for [lat]. */
fun wearMetersNorth(originLat: Double, lat: Double): Double = (lat - originLat) * 110_540.0

/** Quantize metres to [WearMapsProtocol.QUANT_STEP_M] units. */
fun wearQuantize(meters: Double): Int = Math.round(meters / WearMapsProtocol.QUANT_STEP_M).toInt()

/** Inverse of [wearQuantize]. */
fun wearDequantize(units: Int): Double = units * WearMapsProtocol.QUANT_STEP_M
