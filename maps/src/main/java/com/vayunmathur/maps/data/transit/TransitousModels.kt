package com.vayunmathur.maps.data.transit
import com.vayunmathur.maps.data.string

import com.vayunmathur.library.map.GeoPoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Public-transit models for the P10 departure boards (Transitous / MOTIS,
 * open GTFS + GTFS-RT). Two layers of types live here:
 *
 *  - **App models** ([TransitStop], [Departure]) — what the overlay layer, the
 *    ViewModel and the [com.vayunmathur.maps.ui.DeparturesSheet] consume. They
 *    are position/epoch-based and carry no MOTIS wire quirks.
 *  - **Wire DTOs** (the `Motis*` classes) — the subset of the MOTIS v1 REST
 *    responses we actually read. Every field is optional/defaulted and the
 *    [com.vayunmathur.library.network.NetworkClient] JSON is
 *    `ignoreUnknownKeys`, so a MOTIS schema change degrades to nulls rather
 *    than throwing (mirrors the Google-scrape null-safety convention).
 *
 * Only `GET /api/v1/stoptimes` is still called. `/api/v1/map/stops` was replaced
 * by the baked `transit_stops` basemap layer (stops are static data) and
 * `/api/v1/plan` by the on-device RAPTOR planner, so their DTOs are gone.
 */

/**
 * A transit stop drawn on the map and tapped to open its departure board.
 *
 * [id] is the MOTIS/Transitous stop id baked into the `transit_stops` tile layer,
 * which is what the realtime board queries. A stop from a feed whose Transitous
 * source name the build did not know has none and falls back to its name, so the
 * offline board still opens but no live delays arrive.
 */
data class TransitStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
)

/**
 * One stop on a trip's itinerary, for the vehicle-details sheet. Times are
 * epoch millis like [Departure], so the sheet countdown shares its logic;
 * [arrivesMillis]/[departsMillis] already include any realtime shift.
 */
data class TripStop(
    val name: String,
    val lat: Double,
    val lon: Double,
    val arrivesMillis: Long,
    val departsMillis: Long,
    /** Baked MOTIS id when the pack carries one, else empty. */
    val motisId: String,
)

/**
 * A trip's full run for the vehicle-details sheet: header plus one [TripStop]
 * per stop in travel order.
 */
data class TripItinerary(
    val routeName: String,
    val headsign: String,
    /** GTFS route colour as a 6-digit hex WITHOUT a leading `#`, or null. */
    val routeColor: String?,
    val mode: String,
    val cancelled: Boolean,
    val stops: List<TripStop>,
)

/**
 * One drawable rail-line span for the pack-driven lines overlay: part of a
 * route's shape with its lane in the corridor it crosses.
 */
data class RailLine(
    val name: String,
    /** GTFS route colour as a 6-digit hex WITHOUT a leading `#`, or null. */
    val color: String?,
    val mode: String,
    val points: List<GeoPoint>,
    /** This colour's index among the corridor's distinct colours. */
    val ordinal: Int,
    /** Distinct colours the corridor carries (1 outside one). */
    val lanes: Int,
    /** How far into its lane this span sits, over 255. */
    val taper: Int,
)

/**
 * One upcoming departure at a stop. Times are epoch millis so the sheet can run
 * a purely client-side live countdown; [delayMinutes] is realtime − scheduled
 * (positive = late, negative = early) and drives the delay colouring.
 */
data class Departure(
    val line: String,
    val headsign: String,
    val scheduledMillis: Long,
    val realtimeMillis: Long,
    val delayMinutes: Int,
    val realTime: Boolean,
    val platform: String?,
    val mode: String?,
    /** GTFS route colour as a 6-digit hex WITHOUT a leading `#`, or null. */
    val routeColor: String?,
    val cancelled: Boolean,
    /**
     * MOTIS's opaque trip handle, for diagnostics only. It is an internally
     * encoded (feed prefix + trip + service day) string, not a bare GTFS
     * `trip_id`, and the baked `.transit` pack stores no trip id at all, so it
     * cannot be used to join realtime onto the offline schedule.
     */
    val tripId: String? = null,
    /**
     * The pack trip behind this departure, packed like a vehicle id
     * (`(route_idx << 32) | (trip_index << 1) | prev_day`). Set on offline
     * board entries so tapping one opens the same trip sheet a tapped
     * vehicle opens; null on online-only entries.
     */
    val tripVehicleId: Long? = null,
)

// --- MOTIS v1 wire DTOs ----------------------------------------------------

/** Response of `GET /api/v1/stoptimes` — the live board for one stop. */
@Serializable
data class MotisStoptimes(
    val stopTimes: List<MotisStopTime> = emptyList(),
)

/** Immutable value describing motis stop time. */
@Serializable
data class MotisStopTime(
    val place: MotisPlace? = null,
    val mode: String? = null,
    val realTime: Boolean = false,
    val headsign: String? = null,
    val agencyName: String? = null,
    val routeShortName: String? = null,
    val routeColor: String? = null,
    val routeTextColor: String? = null,
    val tripId: String? = null,
    val cancelled: Boolean = false,
)

/**
 * A place node inside a MOTIS stoptime. Times are ISO-8601 strings (with a zone
 * offset); `scheduled*` is the timetable time and the bare field is realtime.
 */
@Serializable
data class MotisPlace(
    val name: String? = null,
    val stopId: String? = null,
    val arrival: String? = null,
    val departure: String? = null,
    @SerialName("scheduledArrival") val scheduledArrival: String? = null,
    @SerialName("scheduledDeparture") val scheduledDeparture: String? = null,
    val track: String? = null,
    @SerialName("scheduledTrack") val scheduledTrack: String? = null,
)
