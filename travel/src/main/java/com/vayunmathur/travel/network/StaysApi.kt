package com.vayunmathur.travel.network

import com.vayunmathur.library.network.NetworkClient
import java.net.URLEncoder
import kotlinx.serialization.json.Json

/**
 * Thin wrapper over the shared [NetworkClient] for the `/api/stays` endpoints on
 * the proxy. Mirrors [TravelApi]: URL/body construction + delegation, with the
 * booking token held server-side. Booking returns a payment intent paid in-app;
 * the trip is then confirmed/polled/serviced through the shared trip + bookings
 * endpoints.
 * Callers wrap in try/catch and surface errors.
 */
object StaysApi {

    private const val BASE = "https://api.maproj.org/api/stays"

    private val json = Json { ignoreUnknownKeys = true }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Search accommodations for the given dates. [place] is free text the
     * server resolves (or an IATA code); pass [latitude]/[longitude] when the
     * offline suggestion carried coordinates. [checkIn]/[checkOut] are
     * `YYYY-MM-DD`.
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
        return travelGetJson(url, "Search failed")
    }

    /** All rates for a search result. */
    suspend fun rates(searchResultId: String): StayRatesDto =
        travelGetJson("$BASE/rates?id=${enc(searchResultId)}", "Rates failed")

    /** Create a trip for a chosen rate (confirming price). */
    suspend fun quote(rateId: String): StayQuoteDto =
        postJson("$BASE/quote", StayQuoteRequest(rateId), "Quote failed")

    /** Book a quoted rate: records guests, returns the payment intent. */
    suspend fun book(request: StayBookingRequestDto): StayBookingResultDto =
        postJson("$BASE/bookings", request, "Booking failed")

    private const val MAX_ERROR_SNIPPET_LENGTH = 300

    private suspend inline fun <reified B, reified T> postJson(url: String, body: B, fallback: String): T {
        val res = travelPerform("POST", url, travelRequestBody(body))
        if (!res.isSuccess) throw travelHttpError(res.status, res.body, fallback)
        return json.decodeFromString(res.body)
    }
}

@kotlinx.serialization.Serializable
private data class StayQuoteRequest(val rateId: String)
