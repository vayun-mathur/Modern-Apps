package com.vayunmathur.taxi.network.lyft

import android.net.Uri
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.taxi.data.Place
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Best-effort car ride endpoints. Their request/response shapes are unverified in the APK
 * teardown (api-notes §3/§4), so these issue the documented HTTP call and return the raw
 * response body (truncated) for the caller to interpret; null on any non-2xx or when signed
 * out. No dedicated UI consumes them yet.
 */
internal class LyftExploratoryApi(
    private val session: LyftApiSession,
    private val offersBody: (Place, Place) -> String,
    private val locationV2Body: (Place) -> String,
) {
    /** `GET /v1/active-offer` — the current unaccepted offer, if any. */
    suspend fun activeOffer(): String? = getRaw("/v1/active-offer")

    /** `GET /v1/rides/{id}/pickupgeofence` — the pickup geofence for a ride. */
    suspend fun pickupGeofence(rideId: String): String? =
        getRaw("/v1/rides/${Uri.encode(rideId)}/pickupgeofence")

    /** `GET /v1/rides/{id}/paymentdetails` — payment breakdown for a ride. */
    suspend fun paymentDetails(rideId: String): String? =
        getRaw("/v1/rides/${Uri.encode(rideId)}/paymentdetails")

    /** `POST /v1/updated-cost-estimate/{id}` — refreshed cost for an in-flight ride. */
    suspend fun updatedCostEstimate(rideId: String): String? =
        postRaw("/v1/updated-cost-estimate/${Uri.encode(rideId)}", "{}")

    /** `POST /v1/scheduledridetimeestimates` — pickup-time estimates for a scheduled ride. */
    suspend fun scheduledRideTimeEstimates(pickup: Place, dropoff: Place): String? =
        postRaw("/v1/scheduledridetimeestimates", offersBody(pickup, dropoff))

    /** `POST /v1/offerings/overview` — offer overview for a route. */
    suspend fun offeringsOverview(pickup: Place, dropoff: Place): String? =
        postRaw("/v1/offerings/overview", offersBody(pickup, dropoff))

    /** `POST /v1/rides/{id}/pickup` — move the pickup of an existing ride. Mutates a live ride. */
    suspend fun updatePickup(rideId: String, pickup: Place): String? =
        postRaw("/v1/rides/${Uri.encode(rideId)}/pickup", locationV2Body(pickup))

    /** `POST /v1/rides/redispatch` — request a new driver for a ride. Mutates a live ride. */
    suspend fun redispatch(rideId: String): String? =
        postRaw("/v1/rides/redispatch", buildJsonObject { put("ride_id", rideId) }.toString())

    private suspend fun getRaw(path: String): String? {
        val token = session.accessToken() ?: return null
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}$path",
            method = "GET",
            headers = session.authHeaders(token),
        )
        Log.debug(TAG, "GET $path -> ${resp.status}")
        return if (resp.isSuccess) resp.text.take(RAW_PREVIEW_MAX) else null
    }

    private suspend fun postRaw(path: String, body: String): String? {
        val token = session.accessToken() ?: return null
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}$path",
            method = "POST",
            headers = session.authJsonHeaders(token),
            body = body,
        )
        Log.debug(TAG, "POST $path -> ${resp.status}")
        return if (resp.isSuccess) resp.text.take(RAW_PREVIEW_MAX) else null
    }

    private companion object {
        private const val TAG = "LyftExploratory"
        private const val RAW_PREVIEW_MAX = 4000
    }
}
