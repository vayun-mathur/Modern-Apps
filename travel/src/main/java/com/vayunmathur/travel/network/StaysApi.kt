package com.vayunmathur.travel.network

import com.vayunmathur.library.network.NetworkClient
import java.net.URLEncoder
import kotlinx.serialization.json.Json

/**
 * Thin wrapper over the shared [NetworkClient] for the `/api/stays` endpoints on
 * the proxy. Mirrors [TravelApi]: URL/body construction + delegation, with the
 * Duffel token held server-side. Callers wrap in try/catch and surface errors.
 */
object StaysApi {

    private const val BASE = "https://api.vayunmathur.com/api/stays"

    private val json = Json { ignoreUnknownKeys = true }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Search accommodations near [place] (an IATA code the server resolves to
     * coordinates) for the given dates. [checkIn]/[checkOut] are `YYYY-MM-DD`.
     */
    suspend fun search(
        place: String,
        checkIn: String,
        checkOut: String,
        rooms: Int = 1,
        adults: Int = 2,
        radius: Int = 10,
        latitude: Double? = null,
        longitude: Double? = null,
    ): List<StaySearchResultDto> {
        val url = buildString {
            append(BASE).append("/search")
            append("?place=").append(enc(place))
            append("&check_in=").append(enc(checkIn))
            append("&check_out=").append(enc(checkOut))
            append("&rooms=").append(rooms)
            append("&adults=").append(adults)
            append("&radius=").append(radius)
            if (latitude != null && longitude != null) {
                append("&latitude=").append(latitude)
                append("&longitude=").append(longitude)
            }
        }
        return NetworkClient.getJson(url)
    }

    /** Accommodation/location autocomplete for the search box. */
    suspend fun suggestions(query: String): List<StaySuggestionDto> {
        if (query.isBlank()) return emptyList()
        return NetworkClient.getJson("$BASE/suggestions?q=${enc(query)}")
    }

    /** All rates for a search result. */
    suspend fun rates(searchResultId: String): StayRatesDto =
        NetworkClient.getJson("$BASE/rates?id=${enc(searchResultId)}")

    /** Confirm price/availability for a rate. */
    suspend fun quote(rateId: String): StayQuoteDto =
        postJson("$BASE/quote", StayQuoteRequest(rateId), "Quote failed")

    /** Book a quoted rate. */
    suspend fun book(request: StayBookingRequestDto): StayBookingResultDto =
        postJson("$BASE/bookings", request, "Booking failed")

    /** List stay bookings. */
    suspend fun bookings(): List<StayBookingResultDto> = NetworkClient.getJson("$BASE/bookings")

    /** One stay booking. */
    suspend fun bookingDetail(id: String): StayBookingResultDto =
        NetworkClient.getJson("$BASE/bookings/${enc(id)}")

    /** Cancel a stay booking. */
    suspend fun cancel(id: String): StayBookingResultDto =
        postJson("$BASE/bookings/${enc(id)}/cancel", "", "Cancellation failed")

    private suspend inline fun <reified T> postJson(url: String, body: Any, fallback: String): T {
        val res = NetworkClient.performRequest(
            url = url,
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = if (body is String && body.isEmpty()) null else body,
        )
        if (!res.isSuccess) {
            throw RuntimeException(res.body.trim().take(300).ifBlank { "$fallback (HTTP ${res.status})." })
        }
        return json.decodeFromString(res.body)
    }
}

@kotlinx.serialization.Serializable
private data class StayQuoteRequest(val rateId: String)
