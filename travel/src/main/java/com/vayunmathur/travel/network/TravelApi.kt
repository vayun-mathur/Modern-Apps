package com.vayunmathur.travel.network

import com.vayunmathur.library.network.NetworkClient

/**
 * Thin wrapper over the shared [NetworkClient] for the `/api/travel` search,
 * trip, ancillary and checkout endpoints on the self-hosted proxy
 * (`api.maproj.org`). The proxy holds the booking token server-side, so the
 * app ships no secret; it just does URL/body construction + delegation to the
 * shared client.
 *
 * Booking servicing (bookings/changes/cancellations) lives in
 * [TravelOrderApi]; stays in [StaysApi].
 *
 * Each call throws on network/parse failure; callers (the ViewModel) wrap in
 * try/catch and surface an error state.
 */
object TravelApi {

    private const val BASE = TRAVEL_BASE

    private fun enc(s: String): String = travelEnc(s)

    /**
     * Search bookable flight offers synchronously: returns every offer (one per
     * fare). Either pass a single leg / round trip via
     * [origin]/[destination]/[depart]/[ret] (`slices` with one slice, or two
     * where slice 2 reverses slice 1), or one leg of a multi-city trip as a
     * one-way [slices] string (`"LHR:JFK:2026-09-01"`). [children] is
     * comma-separated ages (`"5,11"`); [maxConnections] < 0 means no cap.
     * [cabin] is `economy`/`premium_economy`/`business`/`first`.
     */
    suspend fun flights(
        origin: String = "",
        destination: String = "",
        depart: String = "",
        ret: String? = null,
        slices: String? = null,
        adults: Int = 1,
        children: String = "",
        infants: Int = 0,
        cabin: String = "economy",
        maxConnections: Int = -1,
        loyalty: String = "",
    ): OfferSearchDto = NetworkClient.getJson(
        flightsUrl(
            "/flights",
            origin,
            destination,
            depart,
            ret,
            slices,
            adults,
            children,
            infants,
            cabin,
            maxConnections,
            loyalty
        )
    )

    private fun flightsUrl(
        path: String,
        origin: String,
        destination: String,
        depart: String,
        ret: String?,
        slices: String?,
        adults: Int,
        children: String,
        infants: Int,
        cabin: String,
        maxConnections: Int,
        loyalty: String,
    ): String = buildString {
        append(BASE).append(path)
        if (!slices.isNullOrBlank()) {
            append("?slices=").append(enc(slices))
        } else {
            append("?origin=").append(enc(origin))
            append("&destination=").append(enc(destination))
            append("&depart=").append(enc(depart))
            if (!ret.isNullOrBlank()) append("&return=").append(enc(ret))
        }
        append("&adults=").append(adults)
        if (children.isNotBlank()) append("&children=").append(enc(children))
        if (infants > 0) append("&infants=").append(infants)
        append("&cabin=").append(enc(cabin))
        if (maxConnections >= 0) append("&max_connections=").append(maxConnections)
        if (loyalty.isNotBlank()) append("&loyalty=").append(enc(loyalty))
    }

    /**
     * Read a cached offer right before booking (offers expire), or to restore
     * the review screen after process death.
     */
    suspend fun offer(id: String): OfferDto =
        travelGetJson("$BASE/offer?id=${enc(id)}", "Offer no longer available")

    /** Create a trip from the chosen offer ids + travelers. */
    suspend fun createTrip(request: TripRequestDto): TripDto =
        travelPostJson("$BASE/trips", request, "Could not start booking")

    /** Current trip state (polled while pending/processing). */
    suspend fun trip(id: String): TripDto =
        travelGetJson("$BASE/trips/${enc(id)}", "Could not load booking status")

    /**
     * Ancillaries for a trip. While the server prices it answers 202
     * `{"status":"pricing","retryAfterSeconds":N}`; this surfaces as
     * [StillPricingException] carrying the hint so the caller retries. 503
     * raises [StillPricingException] too, via [travelHttpError].
     */
    suspend fun ancillaries(id: String): TripAncillariesDto {
        val res = NetworkClient.performRequest(
            url = "$BASE/trips/${enc(id)}/ancillaries",
            method = "GET",
        )
        if (!res.isSuccess) throw travelHttpError(res.status, res.body, "Could not load extras")
        val data: TripAncillariesDto = travelJson.decodeFromString(res.body)
        if (data.status == "pricing") {
            val retryAfter = runCatching {
                travelJson.decodeFromString<TripPricingDto>(res.body).retryAfterSeconds
            }.getOrDefault(5)
            throw StillPricingException(retryAfter, "Extras are still loading.")
        }
        return data
    }

    /** Save ancillary selections (replaces all), returning the new totals. */
    suspend fun selectAncillaries(id: String, selections: List<ServiceSelectionDto>): AncillariesSelectionResultDto =
        travelPostJson(
            "$BASE/trips/${enc(id)}/ancillaries",
            AncillariesSelectionRequestDto(services = selections),
            "Could not save extras"
        )

    /**
     * Get the payment intent for a trip. Raises [MissingDetailsException] (422)
     * when traveler details are missing.
     */
    suspend fun checkout(id: String): CheckoutDto =
        travelPostJson("$BASE/trips/${enc(id)}/checkout", "", "Could not start payment")

    /** Book the trip after the payment sheet confirmed its intent. */
    suspend fun confirmPayment(id: String): TripDto =
        travelPostJson("$BASE/trips/${enc(id)}/confirm", "", "Could not confirm booking")
}
