package com.vayunmathur.taxi.intents

import android.net.Uri
import androidx.core.net.toUri
import com.vayunmathur.taxi.data.BookingTrip
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place

/**
 * Wire contract for the `taxi://book?…` cross-app booking deep link exposed to a co-signed app
 * (the maps app): a fire-and-forget VIEW hand-off that opens the taxi app on the ride screen with a
 * trip (pickup + destination lat/lng + labels) pre-filled and immediately compared.
 *
 * The live fare/ETA estimate that used to live alongside this as a signature-guarded
 * ContentProvider is now the `com.vayunmathur.taxi.intents.RideEstimateIntent`
 * [AssistantIntent][com.vayunmathur.library.util.AssistantIntent] — a request/response fits
 * time-varying quote data better than a cursor. Only the booking deep link remains here.
 *
 * The two apps deliberately do NOT share a module: maps keeps its own byte-for-byte mirror of
 * these deep-link constants (see maps' `RideEstimateClient`).
 */
object RideHandoffContract {
    // ---- deep-link parameters (caller → app) ----
    const val PARAM_PICKUP_LAT = "pickup_lat"
    const val PARAM_PICKUP_LNG = "pickup_lng"
    const val PARAM_PICKUP_LABEL = "pickup_label"
    const val PARAM_DEST_LAT = "dest_lat"
    const val PARAM_DEST_LNG = "dest_lng"
    const val PARAM_DEST_LABEL = "dest_label"

    // ---- deep link (open the ride screen with a trip pre-filled) ----
    const val DEEP_LINK_SCHEME = "taxi"
    const val DEEP_LINK_HOST = "book"

    /** Builds the `taxi://book?…` deep link that pre-fills a booking for a co-signed caller. */
    fun bookingDeepLink(
        pickupLat: Double,
        pickupLng: Double,
        pickupLabel: String?,
        destLat: Double,
        destLng: Double,
        destLabel: String?,
    ): String =
        "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST".toUri().buildUpon().apply {
            appendQueryParameter(PARAM_PICKUP_LAT, pickupLat.toString())
            appendQueryParameter(PARAM_PICKUP_LNG, pickupLng.toString())
            if (!pickupLabel.isNullOrBlank()) appendQueryParameter(PARAM_PICKUP_LABEL, pickupLabel)
            appendQueryParameter(PARAM_DEST_LAT, destLat.toString())
            appendQueryParameter(PARAM_DEST_LNG, destLng.toString())
            if (!destLabel.isNullOrBlank()) appendQueryParameter(PARAM_DEST_LABEL, destLabel)
        }.build().toString()

    /**
     * Parses a `taxi://book?…` deep link into a [BookingTrip]. Returns null for any other URI or
     * when the required coordinates are missing/malformed, so a bad link degrades to a plain
     * launch rather than a crash.
     */
    fun parseBooking(uriString: String?): BookingTrip? {
        val uri = uriString?.toUri() ?: return null
        if (!isBookingUri(uri)) return null
        val (pickup, destination) = bookingPlaces(uri) ?: return null
        return BookingTrip(pickup, destination)
    }

    private fun isBookingUri(uri: Uri): Boolean =
        uri.scheme == DEEP_LINK_SCHEME && uri.host == DEEP_LINK_HOST

    private fun bookingPlaces(uri: Uri): Pair<Place, Place>? {
        val pickupLat = uri.getQueryParameter(PARAM_PICKUP_LAT)?.toDoubleOrNull() ?: return null
        val pickupLng = uri.getQueryParameter(PARAM_PICKUP_LNG)?.toDoubleOrNull() ?: return null
        val destLat = uri.getQueryParameter(PARAM_DEST_LAT)?.toDoubleOrNull() ?: return null
        val destLng = uri.getQueryParameter(PARAM_DEST_LNG)?.toDoubleOrNull() ?: return null
        val pickupLabel = uri.getQueryParameter(PARAM_PICKUP_LABEL)?.ifBlank { null }
        val destLabel = uri.getQueryParameter(PARAM_DEST_LABEL)?.ifBlank { null }
        return Place(pickupLabel.orEmpty(), null, LatLng(pickupLat, pickupLng)) to
            Place(destLabel.orEmpty(), null, LatLng(destLat, destLng))
    }
}
