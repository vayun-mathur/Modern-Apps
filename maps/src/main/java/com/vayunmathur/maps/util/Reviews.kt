package com.vayunmathur.maps.util
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
data class PlaceMatchRequest(val name: String, val lat: Double, val lon: Double)

@Serializable
data class PlaceIdResponse(val id: String)

@Serializable
data class PlaceRatingRequest(val id: String)

@Serializable
data class PlaceRatingResponse(val rating: Float, val userRatingCount: Int)

@Serializable
data class FullPlaceInfo(val id: String, val rating: Float, val userRatingCount: Int)

object Reviews {
    private const val BASE_URL = "https://api.vayunmathur.com"

    private val client = HttpClient(CIO) { // Using CIO engine
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    /**
     * Chained call: 1. Match OSM data to Google ID -> 2. Get Stars from ID
     */
    suspend fun getRatingForOsmLocation(name: String, lat: Double, lon: Double): FullPlaceInfo? {
        return try {
            // Step 1: Get the Google Place ID from your server
            val idResponse: PlaceIdResponse = client.post("$BASE_URL/maps/place_match") {
                contentType(ContentType.Application.Json)
                setBody(PlaceMatchRequest(name, lat, lon))
            }.body()

            val placeId = idResponse.id

            // Step 2: Get the Rating using that ID
            val ratingResponse: PlaceRatingResponse = client.post("$BASE_URL/maps/place_rating") {
                contentType(ContentType.Application.Json)
                setBody(PlaceRatingRequest(placeId))
            }.body()

            FullPlaceInfo(
                id = placeId,
                rating = ratingResponse.rating,
                userRatingCount = ratingResponse.userRatingCount
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null // Handle errors or 404s gracefully
        }
    }
}