package com.vayunmathur.taxi.network.lyft

import android.content.Context
import com.vayunmathur.taxi.data.AddCardResult
import com.vayunmathur.taxi.data.BookingResult
import com.vayunmathur.taxi.data.CancelResult
import com.vayunmathur.taxi.data.ChargeAccount
import com.vayunmathur.taxi.data.DriverLocation
import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.data.PaymentActionResult
import com.vayunmathur.taxi.data.PaymentMethodsResult
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.RideStatusResult
import com.vayunmathur.taxi.platform.deeplink.RideDeepLinks
import com.vayunmathur.taxi.provider.RideProvider

/**
 * Lyft fares from `POST /v2/offerings` (`/pb.api.endpoints.v1.offers.Offers/ReadOffersV2`).
 *
 * Request/response shapes recovered from the production APK (`me.lyft.android` v2026.29.3) with
 * jadx — see `lyft-re/api-notes.md` §3.
 *
 * This class is the [RideProvider] facade; the work lives in the focused clients it delegates
 * to ([LyftQuotesClient] for fares, [LyftPaymentsClient] for charge accounts and cards,
 * [LyftRidesClient] for booking and tracking, [LyftExploratoryApi] for unverified endpoints),
 * which share one [LyftApiSession].
 */
class LyftProvider(context: Context) : RideProvider {
    override val provider = Provider.LYFT

    private val session = LyftApiSession(context.applicationContext)
    private val quotesClient = LyftQuotesClient(session)
    private val paymentsClient = LyftPaymentsClient(session)
    private val ridesClient = LyftRidesClient(session, ::isBookingLive)
    private val exploratory = LyftExploratoryApi(
        session,
        offersBody = { pickup, dropoff -> quotesClient.offersBody(pickup, dropoff, null, null) },
        locationV2Body = { place -> ridesClient.locationV2Body(place) },
    )

    private fun isBookingLive(): Boolean = BOOKING_LIVE

    override suspend fun isSignedIn(): Boolean = session.tokens.isSignedIn()

    override suspend fun quotes(pickup: Place, dropoff: Place): QuoteResult =
        quotesClient.quotes(pickup, dropoff)

    /**
     * Re-quotes an existing offer set via `/v2/offerings/update` (`ReadOffersV2Update`), carrying
     * [lastOffersId] (the previous `offers_response_id`) and [purchaseSessionId] so the server
     * refreshes the same session's prices/cost-tokens. Used to replace fares whose cost token is
     * about to expire. Response shape matches `/v2/offerings`.
     */
    suspend fun updateQuotes(
        pickup: Place,
        dropoff: Place,
        lastOffersId: String?,
        purchaseSessionId: String?,
    ): QuoteResult = quotesClient.updateQuotes(pickup, dropoff, lastOffersId, purchaseSessionId)

    override fun bookingUri(pickup: Place, dropoff: Place, quote: RideQuote?): String =
        RideDeepLinks.webUri(Provider.LYFT, pickup, dropoff, quote)

    override suspend fun paymentMethods(): PaymentMethodsResult = paymentsClient.paymentMethods()

    override suspend fun setDefaultPaymentMethod(id: String): PaymentActionResult =
        paymentsClient.setDefaultPaymentMethod(id)

    override suspend fun removePaymentMethod(id: String): PaymentActionResult =
        paymentsClient.removePaymentMethod(id)

    override suspend fun addCard(card: NewCard, makeDefault: Boolean): AddCardResult =
        paymentsClient.addCard(card, makeDefault)

    override suspend fun createRide(
        quote: RideQuote,
        pickup: Place,
        dropoff: Place,
        account: ChargeAccount?,
        purchaseSessionId: String?,
        dryRun: Boolean,
    ): BookingResult = ridesClient.createRide(quote, pickup, dropoff, account, dryRun)

    override suspend fun activeRide(): RideStatusResult = ridesClient.activeRide()

    override suspend fun driverLocation(rideId: String): DriverLocation? =
        ridesClient.driverLocation(rideId)

    override suspend fun cancelRide(rideId: String): CancelResult = ridesClient.cancelRide(rideId)

    /**
     * Signs out of Lyft: best-effort revokes the refresh and access tokens server-side, then
     * clears the local session regardless of the server's response.
     */
    suspend fun signOut() {
        session.tokens.refreshToken()?.let { LyftAuth.revoke(it) }
        session.tokens.accessToken()?.let { LyftAuth.revoke(it) }
        session.tokens.clear()
    }

    /** Reads a specific ride by id (`GET /v1/rides/{id}`), parsed like the active ride. */
    suspend fun rideById(rideId: String): RideStatusResult = ridesClient.rideById(rideId)

    /** `GET /v1/active-offer` — the current unaccepted offer, if any. */
    suspend fun activeOffer(): String? = exploratory.activeOffer()

    /** `GET /v1/rides/{id}/pickupgeofence` — the pickup geofence for a ride. */
    suspend fun pickupGeofence(rideId: String): String? = exploratory.pickupGeofence(rideId)

    /** `GET /v1/rides/{id}/paymentdetails` — payment breakdown for a ride. */
    suspend fun paymentDetails(rideId: String): String? = exploratory.paymentDetails(rideId)

    /** `POST /v1/updated-cost-estimate/{id}` — refreshed cost for an in-flight ride. */
    suspend fun updatedCostEstimate(rideId: String): String? = exploratory.updatedCostEstimate(rideId)

    /** `POST /v1/scheduledridetimeestimates` — pickup-time estimates for a scheduled ride. */
    suspend fun scheduledRideTimeEstimates(pickup: Place, dropoff: Place): String? =
        exploratory.scheduledRideTimeEstimates(pickup, dropoff)

    /** `POST /v1/offerings/overview` — offer overview for a route. */
    suspend fun offeringsOverview(pickup: Place, dropoff: Place): String? =
        exploratory.offeringsOverview(pickup, dropoff)

    /** `POST /v1/rides/{id}/pickup` — move the pickup of an existing ride. Mutates a live ride. */
    suspend fun updatePickup(rideId: String, pickup: Place): String? =
        exploratory.updatePickup(rideId, pickup)

    /** `POST /v1/rides/redispatch` — request a new driver for a ride. Mutates a live ride. */
    suspend fun redispatch(rideId: String): String? = exploratory.redispatch(rideId)

    /** Shared constants and helpers for LyftProvider. */
    companion object {
        /**
         * Master guard for live ride booking. While false, [createRide] builds and returns the
         * request but never sends it. Flip to true only after the dry-run request body has been
         * verified against a real capture.
         *
         * Note: this gates *ride creation* only. Payment-method management and [cancelRide] are
         * sent live regardless, per product decision.
         */
        const val BOOKING_LIVE = true
    }
}
