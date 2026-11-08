package com.vayunmathur.taxi.network.lyft

import android.net.Uri
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.taxi.data.BookingResult
import com.vayunmathur.taxi.data.CancelResult
import com.vayunmathur.taxi.data.ChargeAccount
import com.vayunmathur.taxi.data.DriverLocation
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.RideStatusResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/**
 * Ride lifecycle: booking, active-ride reads, driver location, and cancellation.
 *
 * Endpoints recovered from the APK:
 *   POST   /v1/core_trips/create         → CreateTrip                   (book)
 *   GET    /v1/activeride                → ReadActiveRide               (status)
 *   POST   /v1/rides/{id}/cancel         → CancelRide                   (cancel)
 */
internal class LyftRidesClient(
    private val session: LyftApiSession,
    private val bookingLive: () -> Boolean,
) {
    private val rideParser: LyftRideParser by lazy { LyftRideParser(session.json) }

    suspend fun createRide(
        quote: RideQuote,
        pickup: Place,
        dropoff: Place,
        account: ChargeAccount?,
        dryRun: Boolean,
    ): BookingResult {
        val token = session.accessToken() ?: return BookingResult.Failed("Not signed in to Lyft")
        val offerId = quote.offerId
            ?: return BookingResult.Failed("This fare has no offer id — re-quote and try again")
        val riderId = session.tokens.userId()

        // CreateTripRequest (defpackage/ura): rider_id(1), offer_id(2), origin(3), destination(4),
        // ride_segment_creation_args(5). Sent as proto-JSON, as the offers call is.
        val request = buildJsonObject {
            riderId?.let { put("rider_id", it) } // uint64 → string in proto-JSON
            put("offer_id", offerId)
            put("origin", locationV2(pickup))
            put("destination", locationV2(dropoff))
            putJsonObject("ride_segment_creation_args") {
                quote.offerToken?.let { put("offer_token", it) }
                quote.rideType?.let { put("ride_type", it) }
                quote.costToken?.let { put("cost_token", it) }
                put("party_size", PARTY_SIZE_SOLO)
                // pickup_mode defaults to "standard" in the official app (RideSegmentCreationArgs
                // tag 9); the resolver expects it set.
                put("pickup_mode", "standard")
                // ChargeAccountDTO exposes only an id; passed as charge_token (tag 12). Exact
                // charge_token vs shared_charge_account_id split is unverified — dry-run surfaces
                // the built body so it can be checked against a real capture.
                account?.let { put("charge_token", it.chargeToken ?: it.id) }
            }
            // NB: purchase_session_id is NOT a field on CreateTripRequest (it belongs to the
            // offers request); server-side dedupe is handled by the request throttler instead,
            // so this client takes no session id — the facade keeps it only for the interface.
        }
        val requestJson = request.toString()

        // Master guard: never send while booking is not live, or when the caller asked for a
        // dry run. The full request is returned so the UI/logcat can verify it — no charge.
        if (!bookingLive() || dryRun) {
            Log.status(TAG, "DRY-RUN /v1/core_trips/create (not sent): $requestJson")
            return BookingResult.DryRun(requestJson, account)
        }

        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/core_trips/create",
            method = "POST",
            headers = session.authJsonHeaders(token),
            body = requestJson,
        )
        Log.debug(TAG, "POST /v1/core_trips/create -> ${resp.status}")
        if (!resp.isSuccess) return BookingResult.Failed(session.httpError(resp))
        // CreateTripResponse (vra): trip_details(1 = TripDetails) → trip_id(1). No status here;
        // parse the id (JSON or protobuf) for tracking and show nothing else.
        return BookingResult.Created(rideId = parseCreatedRideId(resp), status = null, raw = "")
    }

    /** Extracts `trip_details.trip_id` from a CreateTripResponse (JSON or binary protobuf). */
    private fun parseCreatedRideId(resp: RawResponse): String? {
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        fun proto(): String? = runCatching {
            ProtoMessage(resp.bytes, 0, resp.bytes.size)
                .message(TRIP_DETAILS_FIELD)?.varint(TRIP_ID_FIELD)?.toString()
        }.getOrNull()
        fun asJson(): String? = runCatching {
            (session.json.parseToJsonElement(resp.text) as? JsonObject)
                ?.get("trip_details")?.jsonObject
                ?.let { it.lyftStr("trip_id") ?: it.lyftStr("id") }
        }.getOrNull()
        return if (isProto) (proto() ?: asJson()) else (asJson() ?: proto())
    }

    suspend fun activeRide(): RideStatusResult {
        val token = session.accessToken() ?: return RideStatusResult.Failed("Not signed in to Lyft")
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/activeride",
            method = "GET",
            headers = session.authHeaders(token),
        )
        Log.debug(TAG, "GET /v1/activeride -> ${resp.status} (${resp.bytes.size} bytes)")
        if (resp.status == LyftApiSession.HTTP_NOT_FOUND) return RideStatusResult.None
        if (!resp.isSuccess) return RideStatusResult.Failed(session.httpError(resp))
        val ride = rideParser.parseActiveRide(resp)
        return if (ride == null || !ride.hasContent) RideStatusResult.None else RideStatusResult.Active(ride)
    }

    suspend fun driverLocation(rideId: String): DriverLocation? {
        val token = session.accessToken() ?: return null
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/rides/${Uri.encode(rideId)}/driver-location",
            method = "GET",
            headers = session.authHeaders(token),
        )
        Log.debug(TAG, "GET /v1/rides/{id}/driver-location -> ${resp.status}")
        if (!resp.isSuccess) return null
        return rideParser.parseDriverLocation(resp)
    }

    suspend fun cancelRide(rideId: String): CancelResult {
        val token = session.accessToken() ?: return CancelResult.Failed("Not signed in to Lyft")
        // A cancel genuinely cancels a live ride (and may incur a fee), so it is sent live
        // regardless of booking liveness — that flag only gates ride *creation*.
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/rides/${Uri.encode(rideId)}/cancel",
            method = "POST",
            headers = session.authJsonHeaders(token),
            body = "{}",
        )
        Log.debug(TAG, "POST /v1/rides/{id}/cancel -> ${resp.status}")
        // Surface the server response verbatim either way — a cancel can carry a fee.
        return if (resp.isSuccess) {
            CancelResult.Done(resp.text.take(CANCEL_RESPONSE_PREVIEW_MAX).ifBlank { "Ride cancelled" })
        } else {
            CancelResult.Failed(session.httpError(resp))
        }
    }

    /** Reads a specific ride by id (`GET /v1/rides/{id}`), parsed like the active ride. */
    suspend fun rideById(rideId: String): RideStatusResult {
        val token = session.accessToken() ?: return RideStatusResult.Failed("Not signed in to Lyft")
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/v1/rides/${Uri.encode(rideId)}",
            method = "GET",
            headers = session.authHeaders(token),
        )
        Log.debug(TAG, "GET /v1/rides/{id} -> ${resp.status}")
        if (resp.status == LyftApiSession.HTTP_NOT_FOUND) return RideStatusResult.None
        if (!resp.isSuccess) return RideStatusResult.Failed(session.httpError(resp))
        val ride = rideParser.parseActiveRide(resp)
        return if (ride == null || !ride.hasContent) RideStatusResult.None else RideStatusResult.Active(ride)
    }

    /**
     * A `LocationV2DTO` for `origin`/`destination` on `/v1/core_trips/create`, recovered from the
     * APK (`kvp`, `LocationV2MapperKt.toLocationV2ForApiRequest`). Coordinates live three levels
     * deep as **integer microdegrees** (degrees × 1e6, sint32) at
     * `portable_location_with_features.portable_location.location.{lat,lng}_microdegrees` — a flat
     * `{latitude, longitude}` is not resolvable by the server (it answers 422 "not available for
     * your specified locations"). The display name/address go under
     * `location_metadata.static_metadata.spot`.
     */
    internal fun locationV2Body(place: Place): String = locationV2(place).toString()

    private fun locationV2(place: Place): JsonObject {
        val latMicro = (place.location.latitude * MICRODEGREES_PER_DEGREE).roundToInt()
        val lngMicro = (place.location.longitude * MICRODEGREES_PER_DEGREE).roundToInt()
        val basicLocation = buildJsonObject {
            put("lat_microdegrees", latMicro)
            put("lng_microdegrees", lngMicro)
        }
        return buildJsonObject {
            putJsonObject("portable_location_with_features") {
                putJsonObject("portable_location") {
                    put("location", basicLocation)
                }
            }
            putJsonObject("location_metadata") {
                putJsonObject("static_metadata") {
                    putJsonObject("spot") {
                        put("name", place.name)
                        place.address?.let {
                            put("display_address", it)
                            put("routable_address", it)
                        }
                    }
                }
            }
            put("source", "user")
        }
    }

    private companion object {
        private const val TAG = "LyftRides"
        private const val PARTY_SIZE_SOLO = 1
        private const val MICRODEGREES_PER_DEGREE = 1_000_000.0
        private const val CANCEL_RESPONSE_PREVIEW_MAX = 2000

        // CreateTripResponse: trip_details(1 = TripDetails) → trip_id(1).
        private const val TRIP_DETAILS_FIELD = 1
        private const val TRIP_ID_FIELD = 1
    }
}
