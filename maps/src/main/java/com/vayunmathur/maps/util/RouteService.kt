package com.vayunmathur.maps.util

import kotlinx.serialization.Serializable
import com.vayunmathur.library.map.GeoPoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Singleton holding route service state and operations. */
object RouteService {
    /** Kinds of travel mode. */
    enum class TravelMode { DRIVE, TRANSIT, WALK, BICYCLE }
    /** Singleton holding api state and operations. */
    object API {
        /** Immutable value describing transit details. */
        @Serializable
        data class TransitDetails(
            val headsign: String,
            val stopCount: Int,
            val transitLine: TransitLine,
            val stopDetails: StopDetails,
            val feedName: String? = null,
        )
        /** Immutable value describing stop details. */
        @Serializable
        data class StopDetails(
            val arrivalTime: String,
            val departureTime: String,
            val arrivalStop: Stop,
            val departureStop: Stop,
        )
        /** Immutable value describing transit line; carries name, nameShort, color. */
        @Serializable data class Stop(val name: String)
        /** Immutable value describing a transit line: display name, short name and brand colour. */
        @Serializable
        data class TransitLine(val name: String, val nameShort: String? = null, val color: String)
        /** Immutable value describing nav instruction. */
        @Serializable
        data class NavInstruction(
            val maneuver: Maneuver = Maneuver.MANEUVER_UNSPECIFIED,
            val instructions: String = "",
        )
        /** Kinds of maneuver. */
        @Serializable
        enum class Maneuver {
            MANEUVER_UNSPECIFIED, TURN_SLIGHT_LEFT, TURN_SHARP_LEFT, UTURN_LEFT,
            TURN_LEFT, TURN_SLIGHT_RIGHT, TURN_SHARP_RIGHT, UTURN_RIGHT,
            TURN_RIGHT, STRAIGHT, RAMP_LEFT, RAMP_RIGHT, MERGE, FORK_LEFT,
            FORK_RIGHT, FERRY, FERRY_TRAIN, ROUNDABOUT_LEFT, ROUNDABOUT_RIGHT,
            DEPART, NAME_CHANGE, WAIT, RIDE,
        }
        /**
         * One available turn lane at the maneuver's junction, ordered left→right.
         * [directions] are the turn indications the lane offers — real OSM
         * `turn:lanes` can list several per lane (e.g. through + right), falling
         * back to a single topology-inferred direction when tags are absent.
         * [active] marks a lane that leads onto the taken route (highlighted in
         * the lane-guidance UI).
         */
        @Serializable data class Lane(val directions: List<Maneuver>, val active: Boolean)
    }
    /**
     * [departureTime] and [arrivalTime] are wall-clock `HH:mm` in the **feed's**
     * timezone, matching the per-leg times in [API.StopDetails] — a transit
     * itinerary's legs are timetabled in that zone, so a device-local rendering
     * would disagree with the rows beneath. Null on any route without a ride leg,
     * which includes every non-transit mode and a walk-only itinerary.
     */
    data class Route(
        override val duration: Duration,
        override val distanceMeters: Double,
        val polyline: List<GeoPoint>,
        val step: List<Step>,
        val departureTime: String? = null,
        val arrivalTime: String? = null,
        /**
         * Ground elevation samples along the route for the nav elevation chart
         * (WS-G): each point pairs a cumulative distance from the start (metres)
         * with an elevation (metres). Empty for transit and for any route whose
         * graph carried no elevation data.
         */
        val elevationProfile: List<ElevationPoint> = emptyList(),
        /** Total cumulative ascent over the route in metres. */
        val ascentMeters: Double = 0.0,
        /** Total cumulative descent over the route in metres. */
        val descentMeters: Double = 0.0,
    ): RouteType
    /** One sample of the [Route.elevationProfile]: elevation at a cumulative distance. */
    data class ElevationPoint(val distanceMeters: Double, val elevationMeters: Double)
    /** Immutable value describing step. */
    data class Step(
        val distanceMeters: Double,
        val staticDuration: Duration,
        val polyline: List<GeoPoint>,
        val navInstruction: API.NavInstruction,
        val travelMode: TravelMode,
        val transitDetails: API.TransitDetails? = null,
        val speedRatio: Double = 1.0,
        val lanes: List<API.Lane> = emptyList(),
    )
    /** Contract for route type. */
    interface RouteType { val duration: Duration; val distanceMeters: Double }
    /** Empty Route: a route type implementation for empty route. */
    class EmptyRoute : RouteType {
        override val duration: Duration = 0.seconds
        override val distanceMeters: Double = 0.0
    }
}
