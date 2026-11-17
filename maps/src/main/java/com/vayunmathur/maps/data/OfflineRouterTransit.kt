package com.vayunmathur.maps.data
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.domain.AlternativeRoutes
import com.vayunmathur.maps.domain.GtfsRouteTypes
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.domain.TransitClock
import com.vayunmathur.maps.domain.transitClock

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.ConnectivityMonitor
import com.vayunmathur.maps.BuildConfig
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.RailLine
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.data.transit.TransitousDataSource
import com.vayunmathur.maps.data.transit.TripItinerary
import com.vayunmathur.maps.data.transit.TripStop
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Offline transit planning over the on-device transit pack - the world pack
 * inside `<base>/basemap.mamaps` when the archive carries it, else the legacy
 * per-region sidecar packs - extracted from [OfflineRouter] to keep that file
 * under the length limit.
 *
 * The JNI surface (native methods, `RawStep`/`RawDeparture`/`RawVehicle`, the traffic fetch
 * callback) stays on [OfflineRouter] — native code resolves those by class and member name, so
 * moving them would break the lookup. Everything here is pure Kotlin and reaches the native
 * layer through [OfflineRouter]'s `internal` members.
 */
internal object OfflineRouterTransit {
    /**
     * How many pages [getStopDeparturesOffline] will walk to reach its `until`. A bound rather
     * than a target: a quiet stop arrives in one page, and the busiest hub in the world does not
     * get to spin the board reader for a day's worth of stop times.
     */
    private const val MAX_DEPARTURE_PAGES = 12
    /** Millis per second, for schedule/overlay time conversions. */
    private const val MILLIS_PER_SECOND = 1000L
    /** Seconds per minute, for delay display. */
    private const val SECONDS_PER_MINUTE = 60
    /** Mask for one unsigned RGB byte of a GTFS `route_color`. */
    private const val RGB_MASK = 0xFFFFFF
    /** Minimum geometry doubles for one board/alight coordinate pair. */
    private const val MIN_GEOMETRY_DOUBLES = 4
    /** Minimum native coords for a rail line (one [lon, lat] pair). */
    private const val MIN_RAIL_COORDS = 4
    /** Minimum decoded points for a drawable rail line. */
    private const val MIN_RAIL_POINTS = 2
    /** Stride of one [lon, lat] pair in a native coordinate array. */
    private const val COORD_PAIR_STRIDE = 2

    /**
     * MOTIS realtime, flattened for the JNI overlay arguments. [coords] is
     * interleaved `[lat, lon, ...]`, [times] is interleaved
     * `[schedSecs, delaySecs, cancelled, ...]`, and both are parallel to
     * [routes]. Empty means "plan against the schedule only".
     */
    internal class Overlay(
            val coords: DoubleArray,
            val routes: Array<String>,
            val times: IntArray,
    ) {
        val isEmpty: Boolean get() = routes.isEmpty()

        companion object {
            val EMPTY = Overlay(DoubleArray(0), emptyArray(), IntArray(0))
        }
    }

    /**
     * Pack names under the base path, listed once.
     *
     * The single-archive `basemap.mamaps` already ships the world transit
     * pack as section 13 (packed by `mamaps_pack --transit`), and the native
     * index reads it first - so an archive-present device reports a single
     * `world` pseudo-feed that routes every transit entry point to that
     * archive path. Legacy per-file sidecar packs are the fallback for
     * archives without a transit section. Cleared by [onReload], which
     * [OfflineRouter.reload] calls once a download has replaced the archive.
     */
    @Volatile
    private var cachedTransitFeeds: List<String>? = null

    /** Drops the cached pack list; called by [OfflineRouter.reload]. */
    fun onReload() {
        cachedTransitFeeds = null
    }

    private fun transitFeeds(base: String): List<String> {
        cachedTransitFeeds?.let { return it }
        // Archive first: on an archive-only device there are no sidecar files,
        // so a file listing alone would disable offline transit everywhere.
        // The probe reads the archive section directory only (no pack parse);
        // a negative simply falls through to the legacy per-file list below.
        // The native index reads the archive first and ignores the feed name
        // on that path, so this stable pseudo-feed routes there.
        if (runCatching { OfflineRouter.hasTransitArchiveNative(base) }.getOrDefault(false)) {
            Log.debug("OfflineRouterTransit", "archive transit section found")
            val feeds = listOf("world")
            cachedTransitFeeds = feeds
            return feeds
        }
        val feeds = File(base)
                .listFiles { f -> f.isFile && f.name.endsWith(".transit") }
                ?.map { it.name.removeSuffix(".transit") }
                .orEmpty()
        // An empty result is not cached: the pack may still be downloading, and
        // remembering "none" would disable offline transit until the next restart.
        if (feeds.isNotEmpty()) cachedTransitFeeds = feeds
        return feeds
    }

    /**
     * Offline transit routing (P11d): plan a journey with the on-device RAPTOR
     * planner over the transit pack covering the endpoints (the world pack in
     * the archive when present, else a downloaded per-region sidecar index).
     * Returns null when no index is present/covering or no journey is
     * found — the caller then falls back to the P10 online Transitous planner.
     *
     * Runs at most **two** RAPTOR passes: a schedule-only plan, then, when the
     * device is online, a replan against MOTIS realtime for the stops that plan
     * actually touches, so a cancelled or badly delayed trip is avoided. If the
     * replan finds nothing we keep the schedule-only journey rather than
     * iterating.
     */
    suspend fun getTransitRouteOffline(
            context: Context,
            start: GeoPoint,
            end: GeoPoint
    ): RouteService.Route? = getTransitRouteOffline(context, start, end, departureOffsetSecs = 0)

    /**
     * The journey the planner finds [departureOffsetSecs] later: the raw
     * material for a transit alternative. Same-line next-bus results are
     * filtered out downstream by [AlternativeRoutes]; a different line or
     * service sequence survives.
     */
    suspend fun getTransitRouteOffline(
            context: Context,
            start: GeoPoint,
            end: GeoPoint,
            departureOffsetSecs: Int,
    ): RouteService.Route? = withContext(Dispatchers.Default) {
        val base = OfflineRouterLifecycle.transitBase(context) ?: return@withContext null
        val feeds = transitFeeds(base)
        if (feeds.isEmpty()) return@withContext null

        for (feed in feeds) {
            planFeed(context, base, feed, start, end, departureOffsetSecs)?.let { return@withContext it }
        }
        null
    }

    private suspend fun planFeed(
        context: Context,
        base: String,
        feed: String,
        start: GeoPoint,
        end: GeoPoint,
        departureOffsetSecs: Int = 0,
    ): RouteService.Route? {
        // The index is world-merged, so query times must be in the feed's
        // timezone. Journeys spanning two zones use the origin's — a known
        // limitation, but far better than always using the device's.
        val clock = transitClock(
            runCatching {
                OfflineRouter.getFeedTimezoneNative(base, feed, start.latitude, start.longitude)
            }.getOrNull()
        )
        val shifted = if (departureOffsetSecs == 0) clock else clock.copy(depSecs = clock.depSecs + departureOffsetSecs)
        val scheduled = planJourney(base, feed, start, end, shifted, Overlay.EMPTY)
            ?.takeIf { it.isNotEmpty() } ?: return null
        val overlay = realtimeOverlay(context, journeyStops(scheduled), clock)
        val raw = if (overlay.isEmpty) {
            scheduled
        } else {
            planJourney(base, feed, start, end, shifted, overlay) ?: scheduled
        }
        return OfflineRouterRouteBuilder.buildRoute(context, raw, RouteService.TravelMode.TRANSIT)
    }

    private fun planJourney(
        base: String,
        feed: String,
        start: GeoPoint,
        end: GeoPoint,
        clock: TransitClock,
        overlay: Overlay,
    ): Array<OfflineRouter.RawStep>? {
        return try {
            OfflineRouter.findTransitRouteNative(
                base, feed,
                start.latitude, start.longitude,
                end.latitude, end.longitude,
                clock.depSecs, clock.weekday, clock.date,
                clock.prevWeekday, clock.prevDate,
                overlay.coords, overlay.routes, overlay.times
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Board and alight stops of every ride in a planned journey, as
     * `(position, MOTIS id)`.
     *
     * The id is baked into the v5 pack, which is what lets the overlay name a stop
     * to `/stoptimes` directly. A leg whose pack predates v5, or whose feed's
     * Transitous source name the build did not know, carries no id and is dropped:
     * without one there is no way to ask about it now that the `/map/stops`
     * proximity lookup is gone, and it simply stays schedule-only.
     */
    private fun journeyStops(steps: Array<OfflineRouter.RawStep>): List<Pair<GeoPoint, String>> =
            steps.filter { it.isTransit && it.geometry.size >= MIN_GEOMETRY_DOUBLES }
                    .flatMap { s ->
                        val g = s.geometry
                        listOfNotNull(
                                s.boardStopId?.ifBlank { null }
                                        ?.let { GeoPoint(g[0], g[1]) to it },
                                s.alightStopId?.ifBlank { null }
                                        ?.let { GeoPoint(g[g.size - 2], g[g.size - 1]) to it },
                        )
                    }
                    .distinctBy { it.second }

    /**
     * Fetch MOTIS boards for [stops] concurrently and flatten them into an
     * [Overlay]. Each stop is named by its baked MOTIS id, so this is one
     * `/stoptimes` call per stop with no proximity round-trip.
     *
     * Returns [Overlay.EMPTY] when the device is offline — without that gate every
     * offline plan would pay a full HTTP timeout per stop before `runCatching`
     * swallowed it.
     */
    private suspend fun realtimeOverlay(
        context: Context,
        stops: List<Pair<GeoPoint, String>>,
        clock: TransitClock,
    ): Overlay {
        if (stops.isEmpty()) return Overlay.EMPTY
        if (!ConnectivityMonitor.isOnline(context)) return Overlay.EMPTY
        val boards = TransitBoards.fetchBoards(stops)
        return TransitBoards.buildOverlay(boards, clock)
    }

    /**
     * The stop nearest `(lat, lon)` from the on-device transit pack, as a
     * [TransitStop] whose id is the MOTIS/Transitous id so the realtime board can
     * query it. Null when no pack covers the point.
     *
     * Reached from a tapped station POI, which carries no stop id of its own. This
     * is a purely local lookup — `/api/v1/map/stops`, which used to answer
     * "what stop is here", is gone.
     */
    suspend fun nearestStop(
            context: Context,
            lat: Double,
            lon: Double,
    ): TransitStop? = withContext(Dispatchers.Default) {
        val base = OfflineRouterLifecycle.transitBase(context) ?: return@withContext null
        val feeds = transitFeeds(base)
        for (feed in feeds) {
            val id = runCatching {
                OfflineRouter.nearestStopMotisIdNative(base, feed, lat, lon)
            }.getOrNull()?.ifBlank { null } ?: continue
            // The board resolves its own stop from the coordinate, so the name is
            // only a label until it loads; the MOTIS id is the part that matters.
            return@withContext TransitStop(id = id, name = id, lat = lat, lon = lon)
        }
        null
    }

    /**
     * Simulated moving transit vehicles within the visible bbox (WS-F). Positions
     * are interpolated on-device from the on-device pack's schedule +
     * shape by the native `activeVehiclesNative`; there is no live GPS feed. The
     * result is meant to be recomputed at ~1 Hz by a ticker (the renderer's frame
     * clock smooths motion between recomputes) and pushed to the map overlay.
     *
     * Schedule-only for now: the realtime [Overlay] would need a board fetch per
     * visible stop, which the 1 Hz ticker (task #8) assembles via [realtimeOverlay]
     * and folds in before pushing — the native call already accepts it.
     */
    suspend fun activeVehicles(
            context: Context,
            minLat: Double,
            minLon: Double,
            maxLat: Double,
            maxLon: Double,
    ): List<OfflineRouter.Vehicle> = withContext(Dispatchers.Default) {
        val base = OfflineRouterLifecycle.transitBase(context) ?: return@withContext emptyList()
        val feeds = transitFeeds(base)
        if (feeds.isEmpty()) return@withContext emptyList()

        val out = mutableListOf<OfflineRouter.Vehicle>()
        for (feed in feeds) {
            out.addAll(vehiclesForFeed(base, feed, minLat, minLon, maxLat, maxLon))
        }
        out
    }

    private fun vehiclesForFeed(
        base: String,
        feed: String,
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): List<OfflineRouter.Vehicle> {
        // The pack is world-merged, so query time must be in the feed's zone;
        // resolve it at the bbox centre.
        val clock = transitClock(
            runCatching {
                OfflineRouter.getFeedTimezoneNative(
                    base, feed, (minLat + maxLat) / 2, (minLon + maxLon) / 2
                )
            }.getOrNull()
        )
        val overlay = Overlay.EMPTY
        val raw = try {
            OfflineRouter.activeVehiclesNative(
                base, feed,
                clock.depSecs, clock.weekday, clock.date,
                clock.prevWeekday, clock.prevDate,
                overlay.coords, overlay.routes, overlay.times,
                minLat, minLon, maxLat, maxLon
            )
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        // TEMP-DIAG-A (light-rail icon): histogram the raw GTFS
        // route_type ints the pack returns plus the mapped label, so a
        // Seattle Link viewport shows the exact value behind the icon.
        // Remove once the mapping fix lands.
        if (BuildConfig.DEBUG) {
            val hist = raw.groupingBy { it.mode }.eachCount().toSortedMap()
            Log.dev("TransitIconDiag", "feed=$feed n=${raw.size} rawRouteType->count=$hist")
        }
        return raw.map { v ->
            OfflineRouter.Vehicle(
                lon = v.lon,
                lat = v.lat,
                bearing = v.bearing.toFloat(),
                colour = v.colour,
                mode = gtfsRouteTypeToMode(v.mode),
                id = v.id,
            )
        }
    }

    /**
     * Drawable rail lines serving the bbox, read straight from the on-device
     * timetable pack (GTFS-shape polylines per route) rather than the tile
     * layer that needs an archive rebuild. Colours are the agencies' own
     * `route_color`. Empty when no pack covers the bbox.
     */
    suspend fun railLines(
            context: Context,
            minLat: Double,
            minLon: Double,
            maxLat: Double,
            maxLon: Double,
    ): List<RailLine> = withContext(Dispatchers.Default) {
        val base = OfflineRouterLifecycle.transitBase(context) ?: return@withContext emptyList()
        val feeds = transitFeeds(base)
        if (feeds.isEmpty()) return@withContext emptyList()

        val out = mutableListOf<RailLine>()
        for (feed in feeds) {
            out.addAll(linesForFeed(base, feed, minLat, minLon, maxLat, maxLon))
        }
        out
    }

    private fun linesForFeed(
        base: String,
        feed: String,
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): List<RailLine> {
        val raw = try {
            OfflineRouter.getRailLinesNative(
                base, feed, minLat, minLon, maxLat, maxLon
            )
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return rawLinesToRailLines(raw)
    }

    /**
     * Offline transit lines for the selected stop, read straight from the
     * on-device timetable pack (GTFS-shape polylines per route serving the
     * stop) rather than the tile layer that needs an archive rebuild. Buses
     * included: at one stop a handful of bus polylines is context, not noise.
     * Empty when no pack covers the stop.
     */
    suspend fun stopLines(
            context: Context,
            lat: Double,
            lon: Double,
    ): List<RailLine> = withContext(Dispatchers.Default) {
        val base = OfflineRouterLifecycle.transitBase(context) ?: return@withContext emptyList()
        val feeds = transitFeeds(base)
        if (feeds.isEmpty()) return@withContext emptyList()

        val out = mutableListOf<RailLine>()
        for (feed in feeds) {
            out.addAll(stopLinesForFeed(base, feed, lat, lon))
        }
        out
    }

    private fun stopLinesForFeed(base: String, feed: String, lat: Double, lon: Double): List<RailLine> {
        val raw = try {
            OfflineRouter.getStopLinesNative(base, feed, lat, lon)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return rawLinesToRailLines(raw)
    }

    private fun rawLinesToRailLines(raw: Array<OfflineRouter.RawRailLine>): List<RailLine> {
        val out = mutableListOf<RailLine>()
        for (line in raw) {
            lineToRailLine(line)?.let { out.add(it) }
        }
        return out
    }

    private fun lineToRailLine(line: OfflineRouter.RawRailLine): RailLine? {
        if (line.coords.size < MIN_RAIL_COORDS) return null
        val points = linePoints(line.coords)
        if (points.size < MIN_RAIL_POINTS) return null
        return RailLine(
            name = line.name,
            color = if (line.color == 0) null
                else String.format("%06X", line.color and RGB_MASK),
            mode = gtfsRouteTypeToMode(line.routeType),
            points = points,
            ordinal = line.ordinal,
            lanes = line.lanes,
            taper = line.taper,
        )
    }

    private fun linePoints(coords: DoubleArray): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        var i = 0
        while (i + 1 < coords.size) {
            // Native emits [lon, lat] pairs like a RawStep geometry.
            points.add(GeoPoint(coords[i], coords[i + 1]))
            i += COORD_PAIR_STRIDE
        }
        return points
    }

    /**
     * A trip's full stop-by-stop itinerary for the vehicle-details sheet,
     * decoded from the stable per-trip id `activeVehicles` returns. Runs the
     * same two-pass pattern as the route planner: a schedule-only fetch
     * names the stops (with baked MOTIS ids), then, when online, a replan
     * against the MOTIS boards for those stops folds realtime in. Null when
     * the id does not resolve or the trip does not run today.
     *
     * [lat]/[lon] is the vehicle's tapped position, used only to resolve the
     * feed timezone the query times must be expressed in.
     */
    suspend fun tripItinerary(
            context: Context,
            vehicleId: Long,
            lat: Double,
            lon: Double,
    ): TripItinerary? = withContext(Dispatchers.Default) {
        val base = OfflineRouterLifecycle.transitBase(context) ?: return@withContext null
        val feeds = transitFeeds(base)
        for (feed in feeds) {
            itineraryForFeed(context, base, feed, vehicleId, lat, lon)?.let { return@withContext it }
        }
        null
    }

    private suspend fun itineraryForFeed(
        context: Context,
        base: String,
        feed: String,
        vehicleId: Long,
        lat: Double,
        lon: Double,
    ): TripItinerary? {
        val zoneId = runCatching {
            OfflineRouter.getFeedTimezoneNative(base, feed, lat, lon)
        }.getOrNull()
        val clock = transitClock(zoneId)
        val scheduled = TransitItineraries.fetchItinerary(base, feed, vehicleId, clock, Overlay.EMPTY)
            ?: return null
        val overlay = realtimeOverlay(
            context,
            scheduled.stops.mapNotNull { s ->
                s.motisId.ifBlank { null }?.let { GeoPoint(s.lon, s.lat) to it }
            },
            clock,
        )
        val raw = if (overlay.isEmpty) scheduled
            else TransitItineraries.fetchItinerary(base, feed, vehicleId, clock, overlay) ?: scheduled
        return TripItinerary(
            routeName = raw.routeName,
            headsign = raw.headsign,
            routeColor = if (raw.color == 0) null
                else String.format("%06X", raw.color and RGB_MASK),
            mode = gtfsRouteTypeToMode(raw.routeType),
            cancelled = raw.cancelled,
            stops = raw.stops.map { s -> tripStop(s, clock) },
        )
    }

    private fun tripStop(
        s: OfflineRouter.RawTripStop,
        clock: TransitClock,
    ): TripStop = TransitItineraries.tripStop(s, clock)

    /**
     * Departure board from the on-device transit index for the stop nearest
     * `(lat,lon)`. Scheduled times come from the pack; when the device is online
     * the MOTIS board for that stop is folded in as a realtime overlay, so
     * `delayMinutes`/`realTime`/`cancelled` are live. Returns an empty list when
     * no pack covers the point.
     */
    /**
     * The schedule board for the stop nearest [lat],[lon].
     *
     * [anchor] is the instant the board starts from, defaulting to now. Passing an earlier instant
     * is how the past half of the window is fetched: the pack answers "what leaves from here,
     * onwards", so yesterday's trains are simply the same query asked from yesterday. The
     * per-feed timezone still applies — the anchor is absolute, and each feed resolves it in its
     * own zone, which matters because the pack is world-merged.
     *
     * [until] walks the board forward until it reaches that instant, and is what makes the past
     * half actually recent. "Onwards" is the only direction the pack answers in, so one query for
     * [max] events anchored a day ago returns the events *following the anchor* — at a busy
     * station those are used up within a few hours and the board stops there, leaving a hole
     * between them and now. Paging to [until] closes it, and [max] then keeps the events nearest
     * [until] rather than the ones nearest [anchor].
     */
    suspend fun getStopDeparturesOffline(
            context: Context,
            lat: Double,
            lon: Double,
            max: Int = 30,
            anchor: java.time.Instant? = null,
            until: java.time.Instant? = null,
    ): List<Departure> = withContext(Dispatchers.Default) {
        val base = OfflineRouterLifecycle.transitBase(context) ?: return@withContext emptyList()
        val feeds = transitFeeds(base)
        if (feeds.isEmpty()) return@withContext emptyList()

        val all = mutableListOf<Departure>()
        for (feed in feeds) {
            all.addAll(boardForFeed(context, base, feed, lat, lon, max, anchor, until))
        }
        all.sortBy { it.realtimeMillis }
        // Walking to [until] means the interesting end is the far one: the events just before
        // it, not the ones a day earlier that happen to have been read first.
        if (until != null) all.takeLast(max) else all.take(max)
    }

    private suspend fun boardForFeed(
        context: Context,
        base: String,
        feed: String,
        lat: Double,
        lon: Double,
        max: Int,
        anchor: java.time.Instant?,
        until: java.time.Instant?,
    ): List<Departure> {
        // Neither the feed's zone nor its nearest stop depends on when we ask, so they are
        // resolved once even when the board below is walked forward over several pages.
        val zoneId = runCatching { OfflineRouter.getFeedTimezoneNative(base, feed, lat, lon) }.getOrNull()
        // The board is fetched before we know which stop it is for, so name the
        // stop up front from the pack. No pack id (pre-v5, or a feed whose
        // Transitous source name the build did not know) means no realtime, and
        // the board stays schedule-only.
        //
        // Only the nearest stop's board is fetched, even though the offline
        // board aggregates co-located platforms within 150 m. That is
        // deliberate: the Rust overlay matches a delay to a stop within 60 m,
        // tight enough that adjacent platforms don't collide, so a neighbouring
        // platform's realtime could not be attributed anyway without carrying
        // per-stop coordinates back out of the board.
        val motisId = runCatching {
            OfflineRouter.nearestStopMotisIdNative(base, feed, lat, lon)
        }.getOrNull()?.ifBlank { null }

        val pages = BoardPages(context, base, feed, lat, lon, max, anchor, until, zoneId, motisId)
        return pages.collectStepped()
    }

    /** Paged board walk for one feed: state + per-page fetch/advance. */
    private class BoardPages(
        private val context: Context,
        private val base: String,
        private val feed: String,
        private val lat: Double,
        private val lon: Double,
        private val max: Int,
        anchor: java.time.Instant?,
        private val until: java.time.Instant?,
        private val zoneId: String?,
        private val motisId: String?,
    ) {
        private var from: java.time.Instant = anchor ?: java.time.Instant.now()
        private var page = 0
        private val all = mutableListOf<Departure>()

        suspend fun collectStepped(): List<Departure> {
            while (page < MAX_DEPARTURE_PAGES) {
                page++
                if (!fetchPage()) break
            }
            return all
        }

        /** Fetch one page; false stops the walk. */
        private suspend fun fetchPage(): Boolean {
            val clock = transitClock(zoneId, now = { zone -> from.atZone(zone) })
            // Rebuilt per page because the overlay's times are relative to the clock's
            // midnight, and a walk of a day either side crosses one. The MOTIS board it
            // reads is briefly cached, so only the first page pays for the fetch.
            val overlay = pageOverlay(clock)
            val raw = fetchDepartures(base, feed, lat, lon, clock, overlay, max) ?: return false
            if (raw.isEmpty()) return false
            for (d in raw) {
                all.add(departureOf(d, clock))
            }
            return advance(raw, clock)
        }

        /** Overlay for this page (schedule-only when no MOTIS id). */
        private suspend fun pageOverlay(clock: TransitClock): Overlay {
            if (motisId == null) return Overlay.EMPTY
            return realtimeOverlay(context, listOf(GeoPoint(lon, lat) to motisId), clock)
        }

        /** Advance [from] past this page; false stops the walk. */
        private fun advance(raw: Array<OfflineRouter.RawDeparture>, clock: TransitClock): Boolean {
            if (until == null) return false
            // A short page means the feed has nothing further, not that we arrived.
            if (raw.size < max) return false
            val last = clock.midnightMillis + raw.last().depSecs.toLong() * MILLIS_PER_SECOND
            if (last >= until.toEpochMilli()) return false
            // Past the last event returned, or the next page repeats it forever.
            from = java.time.Instant.ofEpochMilli(last + MILLIS_PER_SECOND)
            return true
        }
    }

    private fun fetchDepartures(
        base: String,
        feed: String,
        lat: Double,
        lon: Double,
        clock: TransitClock,
        overlay: Overlay,
        max: Int,
    ): Array<OfflineRouter.RawDeparture>? {
        return try {
            OfflineRouter.getStopDeparturesNative(
                base, feed, lat, lon,
                clock.depSecs, clock.weekday, clock.date,
                clock.prevWeekday, clock.prevDate,
                overlay.coords, overlay.routes, overlay.times, max
            )
        } catch (_: Exception) {
            null
        }
    }

}
 
/** The departure for a raw native row, in the feed clock's terms. */
private fun OfflineRouterTransit.departureOf(
    d: OfflineRouter.RawDeparture,
    clock: TransitClock,
): Departure = TransitDepartures.departureOf(d, clock)

/** Map a GTFS `route_type` (base + extended ranges) to a coarse mode label. */
internal fun gtfsRouteTypeToMode(t: Int): String = GtfsRouteTypes.mode(t)
