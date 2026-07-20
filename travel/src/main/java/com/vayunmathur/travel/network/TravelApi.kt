package com.vayunmathur.travel.network

import com.vayunmathur.library.network.NetworkClient
import java.net.URLEncoder

/**
 * Thin wrapper over the shared [NetworkClient] for the `/api/travel/*` endpoints
 * on the self-hosted proxy (`api.vayunmathur.com`). The proxy holds the
 * Travelpayouts token/affiliate marker server-side, so the app ships no secret;
 * it just does URL construction + delegation to the Ktor client.
 *
 * Modeled after [com.vayunmathur.weather.network.WeatherApi]. Each call throws
 * on network/parse failure; callers (the ViewModel) wrap in try/catch and
 * surface an error state.
 */
object TravelApi {

    private const val BASE = "https://api.vayunmathur.com/api/travel"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Airport/city autocomplete. Returns empty for a blank query. */
    suspend fun places(query: String): List<PlaceDto> {
        if (query.isBlank()) return emptyList()
        return NetworkClient.getJson("$BASE/places?q=${enc(query)}")
    }

    /**
     * Cheapest fares for a route. [depart] / [ret] are `YYYY-MM-DD`; omit [ret]
     * for one-way. [adults] is forwarded but ignored by the aggregated upstream.
     */
    suspend fun flights(
        origin: String,
        destination: String,
        depart: String,
        ret: String? = null,
        adults: Int = 1,
        currency: String = "usd",
    ): List<FlightDto> {
        val url = buildString {
            append(BASE).append("/flights")
            append("?origin=").append(enc(origin))
            append("&destination=").append(enc(destination))
            append("&depart=").append(enc(depart))
            if (!ret.isNullOrBlank()) append("&return=").append(enc(ret))
            append("&adults=").append(adults)
            append("&currency=").append(enc(currency))
        }
        return NetworkClient.getJson(url)
    }

    /** Hotel offers for a location and date range. */
    suspend fun hotels(
        location: String,
        checkin: String,
        checkout: String,
        adults: Int = 2,
        currency: String = "usd",
    ): List<HotelDto> {
        val url = buildString {
            append(BASE).append("/hotels")
            append("?location=").append(enc(location))
            append("&checkin=").append(enc(checkin))
            append("&checkout=").append(enc(checkout))
            append("&adults=").append(adults)
            append("&currency=").append(enc(currency))
        }
        return NetworkClient.getJson(url)
    }

    /** Best-effort car-rental offers (deep-link-first; see [CarDto]). */
    suspend fun cars(
        location: String,
        pickup: String,
        dropoff: String,
        currency: String = "usd",
    ): List<CarDto> {
        val url = buildString {
            append(BASE).append("/cars")
            append("?location=").append(enc(location))
            append("&pickup=").append(enc(pickup))
            append("&dropoff=").append(enc(dropoff))
            append("&currency=").append(enc(currency))
        }
        return NetworkClient.getJson(url)
    }
}
