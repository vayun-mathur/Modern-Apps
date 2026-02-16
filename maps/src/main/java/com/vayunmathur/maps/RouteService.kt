package com.vayunmathur.maps

import com.vayunmathur.maps.data.SpecificFeature
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration

object RouteService {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    private const val ROUTES_URL = "https://api.vayunmathur.com/maps/route"

    suspend fun computeRoute(
        features: SpecificFeature.Route,
        userPosition: Position,
        travelMode: TravelMode
    ): Route? {
        val originPos = features.waypoints.first()?.position ?: userPosition
        val destPos = features.waypoints.last()?.position ?: userPosition
        val intermediates = features.waypoints.subList(1, features.waypoints.size - 1).map { it?.position ?: userPosition }

        // Construct simplified request for our Deno server
        val request = ServerRouteRequest(
            origin = ServerLatLng(originPos.latitude, originPos.longitude),
            destination = ServerLatLng(destPos.latitude, destPos.longitude),
            intermediates = intermediates.map { ServerLatLng(it.latitude, it.longitude) },
            travelMode = travelMode
        )

        return try {
            val response = client.post(ROUTES_URL) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            // The server now returns the transformed structure directly.
            // We deserialize into a helper DTO to handle 'Position' conversion.
            val serverRoute = response.body<ServerRouteResponse>()

            return Route(
                duration = Duration.parse(serverRoute.duration),
                distanceMeters = serverRoute.distanceMeters,
                polyline = serverRoute.polyline.map { Position(it.longitude, it.latitude) },
                step = serverRoute.step.map { step ->
                    Step(
                        distanceMeters = step.distanceMeters,
                        staticDuration = Duration.parse(step.staticDuration),
                        polyline = step.polyline.map { Position(it.longitude, it.latitude) },
                        navInstruction = step.navInstruction,
                        travelMode = step.travelMode,
                        transitDetails = step.transitDetails
                    )
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    enum class TravelMode {
        DRIVE, TRANSIT, WALK, BICYCLE
    }

    // --- DTOs for communication with Vayunmathur.com Server ---

    @Serializable
    data class ServerRouteRequest(
        val origin: ServerLatLng,
        val destination: ServerLatLng,
        val intermediates: List<ServerLatLng>,
        val travelMode: TravelMode
    )

    @Serializable
    data class ServerLatLng(val latitude: Double, val longitude: Double)

    @Serializable
    data class ServerRouteResponse(
        val duration: String,
        val distanceMeters: Double,
        val polyline: List<ServerLatLng>,
        val step: List<ServerStep>
    )

    @Serializable
    data class ServerStep(
        val distanceMeters: Double,
        val staticDuration: String,
        val polyline: List<ServerLatLng>,
        val navInstruction: API.NavInstruction,
        val travelMode: TravelMode,
        val transitDetails: API.TransitDetails? = null
    )

    // --- Existing Models reused for internal structure ---

    object API {
        @Serializable
        data class TransitDetails(val headsign: String, val stopCount: Int, val transitLine: TransitLine, val stopDetails: StopDetails)

        @Serializable
        data class StopDetails(val arrivalTime: String, val departureTime: String, val arrivalStop: Stop, val departureStop: Stop)

        @Serializable
        data class Stop(val name: String)

        @Serializable
        data class TransitLine(val name: String, val nameShort: String? = null, val color: String)

        @Serializable
        data class NavInstruction(val maneuver: Maneuver = Maneuver.MANEUVER_UNSPECIFIED, val instructions: String = "")

        @Serializable
        enum class Maneuver {
            MANEUVER_UNSPECIFIED, TURN_SLIGHT_LEFT, TURN_SHARP_LEFT, UTURN_LEFT, TURN_LEFT,
            TURN_SLIGHT_RIGHT, TURN_SHARP_RIGHT, UTURN_RIGHT, TURN_RIGHT, STRAIGHT,
            RAMP_LEFT, RAMP_RIGHT, MERGE, FORK_LEFT, FORK_RIGHT, FERRY, FERRY_TRAIN,
            ROUNDABOUT_LEFT, ROUNDABOUT_RIGHT, DEPART, NAME_CHANGE;

            fun icon(): Int? {
                return when(this) {
                    TURN_SLIGHT_LEFT -> R.drawable.direction_turn_slight_left
                    TURN_SHARP_LEFT -> R.drawable.direction_turn_sharp_left
                    UTURN_LEFT -> R.drawable.direction_uturn
                    TURN_LEFT -> R.drawable.direction_turn_left
                    TURN_SLIGHT_RIGHT -> R.drawable.direction_turn_slight_right
                    TURN_SHARP_RIGHT -> R.drawable.direction_turn_sharp_right
                    UTURN_RIGHT -> R.drawable.direction_uturn
                    TURN_RIGHT -> R.drawable.direction_turn_right
                    STRAIGHT -> R.drawable.direction_turn_straight
                    RAMP_LEFT -> R.drawable.direction_off_ramp_left
                    RAMP_RIGHT -> R.drawable.direction_off_ramp_right
                    MERGE -> R.drawable.direction_merge_straight
                    FORK_LEFT -> R.drawable.direction_fork_left
                    FORK_RIGHT -> R.drawable.direction_fork_right
                    FERRY -> null
                    FERRY_TRAIN -> null
                    ROUNDABOUT_LEFT -> R.drawable.direction_roundabout_left
                    ROUNDABOUT_RIGHT -> R.drawable.direction_roundabout_right
                    DEPART -> R.drawable.direction_depart
                    NAME_CHANGE -> R.drawable.direction_new_name_straight
                    MANEUVER_UNSPECIFIED -> null
                }
            }
        }
    }

    data class Route(
        override val duration: Duration,
        override val distanceMeters: Double,
        val polyline: List<Position>,
        val step: List<Step>,
    ): RouteType

    data class Step(
        val distanceMeters: Double,
        val staticDuration: Duration,
        val polyline: List<Position>,
        val navInstruction: API.NavInstruction,
        val travelMode: TravelMode,
        val transitDetails: API.TransitDetails? = null
    )

    interface RouteType {
        val duration: Duration
        val distanceMeters: Double
    }
}