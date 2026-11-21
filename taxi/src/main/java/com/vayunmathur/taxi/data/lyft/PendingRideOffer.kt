package com.vayunmathur.taxi.data.lyft

import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.RideQuote
import kotlinx.serialization.Serializable

/**
 * A Lyft offer the user confirmed, persisted before the create-ride call so process
 * death mid-booking cannot lose it (and a later launch cannot book it a second time
 * without first checking for a ride the server already created).
 *
 * Only the charge-account *id* is stored: charge accounts are fetched live and their
 * tokens stay in memory only (see ChargeAccount). No auth token is stored here either;
 * this lives in the same DataStore as [LyftTokenStore] but is not sealed because it
 * carries no secret — pickup/dropoff are the user's own trip inputs.
 */
@Serializable
data class PendingRideOffer(
    val offerId: String,
    val offerToken: String? = null,
    val costToken: String? = null,
    val rideType: String? = null,
    val costTokenExpiryMs: Long? = null,
    val productId: String = "",
    val displayName: String = "",
    val fareLowMinor: Long = 0,
    val fareHighMinor: Long = 0,
    val currency: String = "USD",
    val pickupEtaMinutes: Int? = null,
    val tripDurationMinutes: Int? = null,
    val pickupName: String = "",
    val pickupAddress: String? = null,
    val pickupLat: Double = 0.0,
    val pickupLng: Double = 0.0,
    val dropoffName: String = "",
    val dropoffAddress: String? = null,
    val dropoffLat: Double = 0.0,
    val dropoffLng: Double = 0.0,
    val chargeAccountId: String? = null,
    val purchaseSessionId: String? = null,
    val savedAtMs: Long = 0,
) {
    fun toQuote(): RideQuote = RideQuote(
        provider = Provider.LYFT,
        productId = productId,
        displayName = displayName,
        fareLowMinor = fareLowMinor,
        fareHighMinor = fareHighMinor,
        currency = currency,
        pickupEtaMinutes = pickupEtaMinutes,
        tripDurationMinutes = tripDurationMinutes,
        surgeMultiplier = null,
        capacity = null,
        offerId = offerId,
        offerToken = offerToken,
        costToken = costToken,
        rideType = rideType,
        costTokenExpiryMs = costTokenExpiryMs,
    )

    fun pickup(): Place = Place(pickupName, pickupAddress, LatLng(pickupLat, pickupLng))

    fun dropoff(): Place = Place(dropoffName, dropoffAddress, LatLng(dropoffLat, dropoffLng))

    /** A cost token past its expiry (with the same 30s lead RideScreen re-quotes on) is unusable. */
    fun isExpired(nowMs: Long): Boolean {
        val expiry = costTokenExpiryMs ?: return false
        return expiry - EXPIRY_LEAD_MS <= nowMs
    }

    companion object {
        private const val EXPIRY_LEAD_MS = 30_000L

        fun from(
            quote: RideQuote,
            pickup: Place,
            dropoff: Place,
            chargeAccountId: String?,
            purchaseSessionId: String?,
            savedAtMs: Long,
        ): PendingRideOffer = PendingRideOffer(
            offerId = quote.offerId.orEmpty(),
            offerToken = quote.offerToken,
            costToken = quote.costToken,
            rideType = quote.rideType,
            costTokenExpiryMs = quote.costTokenExpiryMs,
            productId = quote.productId,
            displayName = quote.displayName,
            fareLowMinor = quote.fareLowMinor,
            fareHighMinor = quote.fareHighMinor,
            currency = quote.currency,
            pickupEtaMinutes = quote.pickupEtaMinutes,
            tripDurationMinutes = quote.tripDurationMinutes,
            pickupName = pickup.name,
            pickupAddress = pickup.address,
            pickupLat = pickup.location.latitude,
            pickupLng = pickup.location.longitude,
            dropoffName = dropoff.name,
            dropoffAddress = dropoff.address,
            dropoffLat = dropoff.location.latitude,
            dropoffLng = dropoff.location.longitude,
            chargeAccountId = chargeAccountId,
            purchaseSessionId = purchaseSessionId,
            savedAtMs = savedAtMs,
        )
    }
}

/** What launch reconciliation should do with a persisted pending offer. */
enum class PendingRideAction { RestoreTracking, KeepPending, Discard }

/**
 * Launch-reconcile decision: an already-active ride wins (restore tracking, never
 * book again); no ride yet keeps a still-valid offer pending (the create may not
 * have reached the server, and the active-ride endpoint can lag a just-booked ride);
 * an expired offer is discarded rather than re-booked on a stale cost token.
 */
fun pendingRideAction(pending: PendingRideOffer, hasActiveRide: Boolean, nowMs: Long): PendingRideAction =
    when {
        hasActiveRide -> PendingRideAction.RestoreTracking
        pending.isExpired(nowMs) -> PendingRideAction.Discard
        else -> PendingRideAction.KeepPending
    }
