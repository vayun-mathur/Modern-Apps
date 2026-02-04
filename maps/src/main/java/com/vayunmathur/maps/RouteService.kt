package com.vayunmathur.maps

import com.vayunmathur.maps.ui.SpecificFeature
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

object RouteService {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true // Important to include default values in JSON
            })
        }
    }

    private const val API_KEY = "AIzaSyBJ2gUeEQ36jbBGLRJUjK1541StDfpBWHI"
    private const val ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"

    suspend fun computeRoute(
        features: SpecificFeature.Route,
        userPosition: Position,
        travelMode: TravelMode
    ): Route? {
        val originPos = features.from?.position ?: userPosition
        val destPos = features.to?.position ?: userPosition

        val request = API.RoutesRequest(
            origin = API.Waypoint(API.Location(API.LatLng(originPos.latitude, originPos.longitude))),
            destination = API.Waypoint(API.Location(API.LatLng(destPos.latitude, destPos.longitude))),
            travelMode = travelMode,
            routingPreference = if (travelMode == TravelMode.DRIVE) "TRAFFIC_AWARE" else null,
        )

        return try {
            val response = client.post(ROUTES_URL) {
                contentType(ContentType.Application.Json)
                header("X-Goog-Api-Key", API_KEY)
                header("X-Goog-FieldMask", "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.navigationInstruction")
                setBody(request) // Now it knows exactly how to serialize this object
            }

            println(response.bodyAsText())

            val routeres = response.body<API.RouteResponse>().routes.firstOrNull() ?: return null
            return Route(routeres.duration, routeres.distanceMeters, decodePolyline(routeres.polyline.encodedPolyline), routeres.legs.map { leg -> leg.steps.map {
                Step(decodePolyline(it.polyline.encodedPolyline), it.navigationInstruction)
            }}.flatten())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun decodePolyline(encoded: String): List<Position> {
        val poly = mutableListOf<Position>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(Position(lng.toDouble() / 1E5, lat.toDouble() / 1E5))
        }
        return poly
    }

    enum class TravelMode {
        DRIVE, TRANSIT, WALK, BICYCLE
    }

    object API {
        // --- Request Models ---
        @Serializable
        data class RoutesRequest(
            val origin: Waypoint,
            val destination: Waypoint,
            val travelMode: TravelMode,
            val routingPreference: String?,
            val computeAlternativeRoutes: Boolean = false,
            val routeModifiers: RouteModifiers = RouteModifiers(),
            val languageCode: String = "en-US",
            val units: String = "METRIC"
        )

        @Serializable
        data class Waypoint(val location: Location)

        @Serializable
        data class Location(val latLng: LatLng)

        @Serializable
        data class LatLng(val latitude: Double, val longitude: Double)

        @Serializable
        data class RouteModifiers(
            val avoidTolls: Boolean = false,
            val avoidHighways: Boolean = false,
            val avoidFerries: Boolean = false
        )

        // --- Response Models ---
        @Serializable
        data class RouteResponse(val routes: List<RouteRes> = emptyList())

        @Serializable
        data class RouteRes(
            val duration: String,
            val distanceMeters: Int,
            val polyline: Polyline,
            val legs: List<Leg>
        )

        @Serializable
        data class Leg(val steps: List<Step>)

        @Serializable
        data class Step(val polyline: Polyline, val navigationInstruction: NavInstruction)

        @Serializable
        data class NavInstruction(val maneuver: Maneuver = Maneuver.MANEUVER_UNSPECIFIED, val instructions: String = "")

        @Serializable
        enum class Maneuver {
            MANEUVER_UNSPECIFIED,
            TURN_SLIGHT_LEFT,
            TURN_SHARP_LEFT,
            UTURN_LEFT,
            TURN_LEFT,
            TURN_SLIGHT_RIGHT,
            TURN_SHARP_RIGHT,
            UTURN_RIGHT,
            TURN_RIGHT,
            STRAIGHT,
            RAMP_LEFT,
            RAMP_RIGHT,
            MERGE,
            FORK_LEFT,
            FORK_RIGHT,
            FERRY,
            FERRY_TRAIN,
            ROUNDABOUT_LEFT,
            ROUNDABOUT_RIGHT,
            DEPART,
            NAME_CHANGE;

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


    @Serializable
    data class Polyline(val encodedPolyline: String)

    data class Route(
        val duration: String,
        val distanceMeters: Int,
        val polyline: List<Position>,
        val step: List<Step>,
    )

    data class Step(
        val polyline: List<Position>,
        val navInstruction: API.NavInstruction
    )
}