package com.vayunmathur.travel.network

import com.vayunmathur.library.network.NetworkClient
import java.net.URLEncoder

/**
 * Thin wrapper over the shared [NetworkClient] for the `/api/travel` endpoints
 * on the self-hosted proxy (`api.vayunmathur.com`). The proxy holds the Duffel
 * token server-side, so the app ships no secret; it just does URL/body
 * construction + delegation to the Ktor client.
 *
 * Each call throws on network/parse failure; callers (the ViewModel) wrap in
 * try/catch and surface an error state.
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
     * Search bookable flight offers for a route. [depart] / [ret] are
     * `YYYY-MM-DD`; omit [ret] for one-way. [cabin] is one of
     * `economy`/`premium_economy`/`business`/`first`.
     */
    suspend fun flights(
        origin: String,
        destination: String,
        depart: String,
        ret: String? = null,
        adults: Int = 1,
        cabin: String = "economy",
    ): OfferSearchDto {
        val url = buildString {
            append(BASE).append("/flights")
            append("?origin=").append(enc(origin))
            append("&destination=").append(enc(destination))
            append("&depart=").append(enc(depart))
            if (!ret.isNullOrBlank()) append("&return=").append(enc(ret))
            append("&adults=").append(adults)
            append("&cabin=").append(enc(cabin))
        }
        return NetworkClient.getJson(url)
    }

    /** Re-price/confirm a single offer right before booking (offers expire). */
    suspend fun offer(id: String): OfferDto =
        NetworkClient.getJson("$BASE/offer?id=${enc(id)}")

    /** Book a selected offer. Returns the confirmed order (with a PNR). */
    suspend fun createOrder(request: OrderRequestDto): OrderResultDto =
        NetworkClient.callJson(
            url = "$BASE/orders",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = request,
        )
}
