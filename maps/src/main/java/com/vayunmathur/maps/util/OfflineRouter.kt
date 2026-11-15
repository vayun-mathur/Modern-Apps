package com.vayunmathur.maps.util

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.annotation.Keep
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vayunmathur.library.map.GeoPoint

/**
 * Road-graph routing over the on-device graph: init, single/multi-leg
 * planning, and the JNI + data types the transit helpers share.
 *
 * The JNI surface stays here — native code resolves those members by class
 * and name. Everything else moved to focused holders in the same package:
 * [OfflineRouterTraffic] (live-traffic display state + tile server),
 * [OfflineRouterTransit] (RAPTOR planning, boards, vehicles, lines), and
 * [OfflineRouterRouteBuilder] (native steps → [RouteService.Route]).
 */
object OfflineRouter {
    init {
        System.loadLibrary("offlinerouter")
        OfflineRouterTraffic.startLocalTileServer()
    }

    /** At least an origin and a destination for a multi-waypoint route. */
    private const val MIN_ROUTE_POSITIONS = 2

    private external fun init(basePath: String): Boolean

    /** Invoker so [OfflineRouterLifecycle] can init without touching JNI visibility. */
    internal fun initGraph(basePath: String): Boolean = init(basePath)
    private external fun findRouteNative(
            sLat: Double,
            sLon: Double,
            eLat: Double,
            eLon: Double,
            mode: Int
    ): Array<RawStep>?
    /**
     * Offline transit journey planning (P11b): RAPTOR over the per-region
     * `<feed>.transit` index at `<basePath>/<feed>.transit`. `depSecs` is seconds
     * since midnight, `weekday` is 0=Mon..6=Sun, `date` is yyyymmdd — all in the
     * **feed's** timezone (see [getFeedTimezoneNative]), since the index is
     * world-merged. `prevWeekday`/`prevDate` describe the preceding service day,
     * whose GTFS `>24:00:00` trips run into the query day.
     *
     * `overlay*` carry MOTIS realtime so the planner skips cancelled trips and
     * uses live times; pass empty arrays for a schedule-only plan.
     * `overlayCoords` is interleaved `[lat, lon, ...]` and `overlayTimes` is
     * interleaved `[schedSecs, delaySecs, cancelled, ...]`, both parallel to
     * `overlayRoutes`.
     *
     * Returns walk + wait + ride legs as [RawStep]s, or null when the feed is
     * missing, doesn't cover the endpoints, or no journey exists.
     *
     * The [JvmName] is load-bearing: `internal` members mangle to
     * `name$maps` in bytecode, but JNI resolves by the declared name -
     * without it every call dies with UnsatisfiedLinkError (seen on-device
     * 2026-09-14: the probe linked only after this was added, and the five
     * older transit natives were silently broken the same way).
     */
    @JvmName("findTransitRouteNative")
    internal external fun findTransitRouteNative(
            basePath: String,
            feed: String,
            sLat: Double,
            sLon: Double,
            eLat: Double,
            eLon: Double,
            depSecs: Int,
            weekday: Int,
            date: Int,
            prevWeekday: Int,
            prevDate: Int,
            overlayCoords: DoubleArray,
            overlayRoutes: Array<String>,
            overlayTimes: IntArray
    ): Array<RawStep>?
    /**
     * Offline scheduled departure board: upcoming departures from the stop
     * nearest `(lat,lon)` in `<basePath>/<feed>.transit`. Time and overlay
     * arguments are as in [findTransitRouteNative].
     * Returns null when the feed is missing or doesn't cover the point.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("getStopDeparturesNative")
    internal external fun getStopDeparturesNative(
            basePath: String,
            feed: String,
            lat: Double,
            lon: Double,
            depSecs: Int,
            weekday: Int,
            date: Int,
            prevWeekday: Int,
            prevDate: Int,
            overlayCoords: DoubleArray,
            overlayRoutes: Array<String>,
            overlayTimes: IntArray,
            max: Int
    ): Array<RawDeparture>?
    /**
     * Whether `<basePath>/basemap.mamaps` carries a transit section (kind 13).
     *
     * The archive already ships the world transit pack (packed by
     * `mamaps_pack --transit` and read archive-first by the native
     * `transit_index`), but the Kotlin discovery gate used to list only the
     * per-region sidecar packs under `<basePath>` - so on an archive-only
     * device every transit entry point short-circuited before any JNI call.
     * A pure presence probe (section directory only, no pack parse), cheap
     * enough to call on every entry.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("hasTransitArchiveNative")
    internal external fun hasTransitArchiveNative(basePath: String): Boolean

    /**
     * IANA timezone of the feed covering `(lat,lon)` in the given pack, or null
     * when the pack is stale/absent, doesn't cover the point, or its GTFS had no
     * `agency.txt`. Callers resolve this before deriving any query times.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("getFeedTimezoneNative")
    internal external fun getFeedTimezoneNative(
            basePath: String,
            feed: String,
            lat: Double,
            lon: Double
    ): String?

    /**
     * MOTIS/Transitous id of the stop nearest `(lat, lon)` in the baked v5 pack,
     * or null when the pack is absent, predates v5, doesn't cover the point, or
     * its feed's Transitous source name was unknown at build time.
     *
     * A purely local lookup. It exists because the departure board fetches its
     * realtime overlay before it knows which stop the board is for, so it has to
     * name the stop up front — and since `/api/v1/map/stops` is gone, there is no
     * longer any network way to turn a coordinate into a MOTIS id.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("nearestStopMotisIdNative")
    internal external fun nearestStopMotisIdNative(
            basePath: String,
            feed: String,
            lat: Double,
            lon: Double
    ): String?
    /**
     * Drawable lines for the bbox, read from the timetable pack's
     * GTFS-shape sections (no tile rebuild needed). `coords` is flat
     * `[lon0, lat0, ...]`; `routeColor` is 0xRRGGBB (`0` when absent).
     * Empty array (not null) when the pack is absent; null on JNI failure.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("getRailLinesNative")
    internal external fun getRailLinesNative(
            basePath: String,
            feed: String,
            minLat: Double,
            minLon: Double,
            maxLat: Double,
            maxLon: Double
    ): Array<RawRailLine>?
    /**
     * Drawable lines serving the stop nearest `(lat, lon)`, read from the
     * timetable pack's GTFS-shape sections like [getRailLinesNative] (same
     * `RawRailLine` layout, same corridor slots). Buses included: at one stop
     * a handful of bus polylines is context, not noise. Empty array (not
     * null) when the pack is absent or no stop is near; null on JNI failure.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("getStopLinesNative")
    internal external fun getStopLinesNative(
            basePath: String,
            feed: String,
            lat: Double,
            lon: Double
    ): Array<RawRailLine>?
    /**
     * A trip's stop-by-stop itinerary for the vehicle-details sheet, decoded
     * from the stable per-trip id `activeVehiclesNative` returns. Times are
     * feed-local-midnight seconds in the query-day frame, like board times.
     * Null when the id does not resolve or the trip does not run today; a
     * cancelled trip still returns with `cancelled` set.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("getTripItineraryNative")
    internal external fun getTripItineraryNative(
            basePath: String,
            feed: String,
            vehicleId: Long,
            weekday: Int,
            date: Int,
            prevWeekday: Int,
            prevDate: Int,
            overlayCoords: DoubleArray,
            overlayRoutes: Array<String>,
            overlayTimes: IntArray
    ): RawTripItinerary?
    /**
     * Simulated in-service transit vehicles for the visible bbox (WS-F): every
     * trip in `<basePath>/<feed>.transit` running at `nowSecs` (seconds since
     * feed-local midnight), interpolated to a live `lon,lat,bearing` along its
     * shape. Positions are computed on-device from the pack schedule + shape +
     * the realtime overlay; there is no live GPS feed.
     *
     * Time and `overlay*` arguments are as in [findTransitRouteNative], so a
     * `>24:00:00` overnight trip is placed and a delayed/cancelled trip is drawn
     * live/suppressed. Returns [RawVehicle]s (possibly empty), or null when the
     * feed is missing/malformed.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("activeVehiclesNative")
    internal external fun activeVehiclesNative(
            basePath: String,
            feed: String,
            nowSecs: Int,
            weekday: Int,
            date: Int,
            prevWeekday: Int,
            prevDate: Int,
            overlayCoords: DoubleArray,
            overlayRoutes: Array<String>,
            overlayTimes: IntArray,
            minLat: Double,
            minLon: Double,
            maxLat: Double,
            maxLon: Double
    ): Array<RawVehicle>?
    private external fun updateTrafficNative(
            edgeIds: LongArray,
            speeds: ByteArray,
            packedSquare: Int
    )
    external fun getTrafficSegmentsNative(): DoubleArray
    private external fun notifyTrafficFetchFinishedNative(packedSquare: Int)
    external fun getTrafficTileNative(z: Int, x: Int, y: Int): ByteArray?

    // Display-traffic version/table/notify live in OfflineRouterTraffic (same
    // package) — callers use it directly. Lifecycle (initialize/reload/
    // transitBase) lives in OfflineRouterLifecycle. Kept out of this object
    // so it stays under the function cap.

    external fun ensureTrafficLoadedNative(lat: Double, lon: Double, forceAsync: Boolean)

    // Traffic fetching + payload decoding live in OfflineRouterTrafficFetch (same
    // package) so this object stays under the function cap; the JNI callback
    // below is the only entry point and keeps its exact name/signature.
    private fun fetchTrafficData(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        packedSquare: Int,
        forceAsync: Boolean,
    ) {
        OfflineRouterTrafficFetch.fetchTrafficData(
            minLat, minLon, maxLat, maxLon, packedSquare, forceAsync,
        )
    }

    internal fun updateTraffic(edgeIds: LongArray, speeds: ByteArray, packedSquare: Int) {
        updateTrafficNative(edgeIds, speeds, packedSquare)
    }

    internal fun finishTrafficFetch(packedSquare: Int) {
        notifyTrafficFetchFinishedNative(packedSquare)
    }

    /** Type representing raw step. */
    class RawStep
    @Keep
    constructor(
            val maneuverId: Int,
            val roadName: String,
            val distanceMm: Long,
            val duration10ms: Long,
            val geometry: DoubleArray,
            val speedRatio: Double,
            val isTransit: Boolean,
            val gtfsFeed: String?,
            val stopCode: String?,
            val endStopCode: String?,
            val stopCount: Int,
            /** Packed turn lanes: one int per lane, `dirMask * 2 + valid`, where
             * `dirMask` is a bitmask of Maneuver ordinals the lane offers. */
            val lanePacked: IntArray,
            // Transit-only tail. The JNI ctor descriptor is shared with the
            // driving path, which passes null/0 — keep these LAST so adding to
            // them never renumbers the arguments above.
            /** GTFS `trip_headsign` of the ridden trip. */
            val headsign: String?,
            /** GTFS `route_color` packed as 0xRRGGBB, or 0 when absent. */
            val routeColor: Int,
            /** Departure, seconds since feed-local midnight (0 when unknown). */
            val depSecs: Int,
            /** Arrival, seconds since feed-local midnight (0 when unknown). */
            val arrSecs: Int,
            /**
             * MOTIS/Transitous id of the ride's board stop, baked into the v5
             * pack. Null on a walk/wait leg, on a pre-v5 pack, or when the feed's
             * Transitous source name was unknown at build time — in which case the
             * realtime overlay simply has nothing to ask about for this leg.
             */
            val boardStopId: String?,
            /** MOTIS/Transitous id of the ride's alight stop. See [boardStopId]. */
            val alightStopId: String?,
            /**
             * Per-coordinate ground elevation in metres, parallel to [geometry]
             * (so `elevations.size == geometry.size / 2`). Baked from the DEM at
             * graph-build time (WS-G) and interpolated along each edge. Empty on a
             * transit leg or when the graph carries no elevation data.
             */
            val elevations: DoubleArray,
            /** Whole-route cumulative ascent in metres (repeated on every step). */
            val ascentM: Double,
            /** Whole-route cumulative descent in metres (repeated on every step). */
            val descentM: Double,
    )

    /** One offline scheduled departure from the baked `.transit` index. */
    class RawDeparture
    @Keep
    constructor(
            val routeName: String,
            val headsign: String,
            val feed: String,
            val stopCode: String,
            /** GTFS route colour as packed 0xRRGGBB, or 0 when absent. */
            val routeColor: Int,
            /** GTFS route_type. */
            val routeType: Int,
            /** Scheduled departure, seconds since feed-local midnight. */
            val depSecs: Int,
            /** Realtime shift in seconds; 0 without live data. */
            val delaySecs: Int,
            val cancelled: Boolean,
            /** Whether the realtime overlay covered this departure. */
            val realTime: Boolean,
            // The trip behind this departure, packed like a vehicle id
            // (`(route_idx << 32) | (trip_index << 1) | prev_day`) so a tap
            // opens the same trip sheet. Kept LAST so the shared descriptor
            // never renumbers the arguments above.
            val tripId: Long,
    )

    /**
     * One simulated in-service vehicle as it crosses the JNI boundary. Flat like
     * [RawStep]/[RawDeparture]; [Vehicle] is the typed form callers consume.
     */
    class RawVehicle
    @Keep
    constructor(
            val lon: Double,
            val lat: Double,
            /** Heading in degrees, 0 = north, clockwise, along the direction of travel. */
            val bearing: Double,
            /** GTFS `route_color` packed as 0xRRGGBB, or 0 when absent. */
            val colour: Int,
            /** GTFS `route_type`. */
            val mode: Int,
            /** Stable per-trip id, so a sprite animates between recomputes. */
            val id: Long,
    )

    /**
     * A simulated moving transit vehicle for the map overlay (WS-F). [bearing] is
     * degrees (0 = north, clockwise); [colour] is 0xRRGGBB (0 when absent);
     * [mode] is a coarse label (BUS/TRAM/RAIL/…) for icon selection; [id] is
     * stable across the ~1 Hz recomputes for the same trip.
     */
    data class Vehicle(
            val lon: Double,
            val lat: Double,
            val bearing: Float,
            val colour: Int,
            val mode: String,
            val id: Long,
    )

    /**
     * One drawable rail line as it crosses the JNI boundary: the route's full
     * GTFS-shape polyline with its agency colour. Flat like [RawVehicle].
     */
    class RawRailLine
    @Keep
    constructor(
            val name: String,
            /** GTFS `route_color` packed as 0xRRGGBB, or 0 when absent. */
            val color: Int,
            /** GTFS `route_type`. */
            val routeType: Int,
            val feed: String,
            /** Flat `[lon0, lat0, ...]`, like a [RawStep] geometry. */
            val coords: DoubleArray,
            // Corridor slot, appended LAST.
            val ordinal: Int,
            val lanes: Int,
            val taper: Int,
    )

    /**
     * One itinerary stop as it crosses the JNI boundary. Times are
     * feed-local-midnight seconds in the query-day frame, converted by
     * callers exactly like board times.
     */
    class RawTripStop
    @Keep
    constructor(
            val name: String,
            val lat: Double,
            val lon: Double,
            val arrSecs: Int,
            val depSecs: Int,
            /** Baked MOTIS id when the pack carries one, else empty. */
            val motisId: String,
            /** Set on every stop when the trip is cancelled. */
            val cancelled: Boolean,
    )

    /** A trip's full run: header plus one [RawTripStop] per stop, in order. */
    class RawTripItinerary
    @Keep
    constructor(
            val routeName: String,
            val headsign: String,
            /** GTFS `route_color` packed as 0xRRGGBB, or 0 when absent. */
            val color: Int,
            /** GTFS `route_type`. */
            val routeType: Int,
            val feed: String,
            val cancelled: Boolean,
            val stops: Array<RawTripStop>,
    )

    /** Base dir (external files) holding the downloaded `basemap.mamaps` archive and legacy `*.transit` packs. */
    internal var basePath: String? = null

    // (Init state lives in OfflineRouterLifecycle alongside initialize/reload.)

    // Lifecycle (transitBase/initialize/reload) lives in OfflineRouterLifecycle
    // (same package) — callers use it directly. Transit planning, boards,
    // vehicles, lines, itineraries and departures live in OfflineRouterTransit.
    // Road routing lives in OfflineRouterRoadRoutes; the getRouteMulti delegate
    // below stays for the call sites not yet moved.

    suspend fun getRoute(
        context: Context,
        start: GeoPoint,
        end: GeoPoint,
        mode: RouteService.TravelMode,
    ): RouteService.Route =
        withContext(Dispatchers.Default) {
            Log.dev("OfflineRouter", "getRoute: mode=$mode, start=$start, end=$end")
            if (!OfflineRouterLifecycle.isInitialized) {
                OfflineRouterLifecycle.initialize(context)
            }
            Log.debug("OfflineRouter", "isInitialized=${OfflineRouterLifecycle.isInitialized}")

            val rawSteps =
                findRouteNative(
                    start.latitude,
                    start.longitude,
                    end.latitude,
                    end.longitude,
                    mode.ordinal
                )
                    ?: throw IllegalStateException("No route found")

            buildRoute(context, rawSteps, mode)
        }

    /**
     * Convert native [RawStep]s into a [RouteService.Route] — see
     * [OfflineRouterRouteBuilder.buildRoute] for the shared implementation.
     * Kept here as a thin delegate so existing callers don't move.
     */
    private fun buildRoute(
            context: Context,
            rawSteps: Array<RawStep>,
            mode: RouteService.TravelMode
    ): RouteService.Route = OfflineRouterRouteBuilder.buildRoute(context, rawSteps, mode)
}
