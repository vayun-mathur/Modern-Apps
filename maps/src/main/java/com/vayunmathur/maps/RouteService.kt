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

// --- Request Models ---
@Serializable
data class RoutesRequest(
    val origin: Waypoint,
    val destination: Waypoint,
    val travelMode: String = "DRIVE",
    val routingPreference: String = "TRAFFIC_AWARE",
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
    val polyline: Polyline
)

@Serializable
data class Polyline(val encodedPolyline: String)

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
        userPosition: Position
    ): Route? {
        val originPos = features.from?.position ?: userPosition
        val destPos = features.to?.position ?: userPosition

        val request = RoutesRequest(
            origin = Waypoint(Location(LatLng(originPos.latitude, originPos.longitude))),
            destination = Waypoint(Location(LatLng(destPos.latitude, destPos.longitude)))
        )

        return try {
            val response: RouteResponse = client.post(ROUTES_URL) {
                contentType(ContentType.Application.Json)
                header("X-Goog-Api-Key", API_KEY)
                header("X-Goog-FieldMask", "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline")
                setBody(request) // Now it knows exactly how to serialize this object
            }.body()

            val routeres = response.routes.firstOrNull() ?: return null
            return Route(routeres.duration, routeres.distanceMeters, decodePolyline(routeres.polyline.encodedPolyline))
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
}

data class Route(
    val duration: String,
    val distanceMeters: Int,
    val polyline: List<Position>
)