package com.vayunmathur.taxi.data

/** Immutable value describing lat lng. */
data class LatLng(
    val latitude: Double,
    val longitude: Double,
)

/** Immutable value describing place. */
data class Place(
    val name: String,
    val address: String?,
    val location: LatLng,
)

/**
 * A trip handed to the app from a co-signed caller (the maps app) via the `taxi://book` deep
 * link — the origin + destination to pre-fill on the ride screen and immediately compare.
 */
data class BookingTrip(
    val pickup: Place,
    val destination: Place,
)

/** Kinds of provider. */
enum class Provider(val label: String, val packageName: String) {
    UBER("Uber", "com.ubercab"),
    LYFT("Lyft", "me.lyft.android"),
}

/**
 * A bookable option from one provider. Fares are in minor currency units so the two
 * providers stay comparable without float rounding; [fareLowMinor] equals [fareHighMinor]
 * when the provider quotes an exact price rather than a range.
 *
 * [fareLowMinor]/[fareHighMinor] are the **actual** price the rider pays, i.e. after any
 * applicable promotion/coupon. When a promotion applies, [originalFareLowMinor]/
 * [originalFareHighMinor] carry the pre-discount amount (for a struck-through "was" price); they
 * are null when there is no discount.
 *
 * The trailing [offerId]/[offerToken]/[costToken]/[rideType]/[costTokenExpiryMs] fields carry
 * the tokens an in-app booking needs. They are populated only for providers that support
 * in-app booking (Lyft) and stay null everywhere else, so quote-only and deep-link paths are
 * unaffected. The cost token expires (see [costTokenExpiryMs]); a stale token must be
 * re-quoted before booking.
 */
data class RideQuote(
    val provider: Provider,
    val productId: String,
    val displayName: String,
    val fareLowMinor: Long,
    val fareHighMinor: Long,
    val currency: String,
    val pickupEtaMinutes: Int?,
    val tripDurationMinutes: Int?,
    val surgeMultiplier: Double?,
    val capacity: Int?,
    val offerId: String? = null,
    val offerToken: String? = null,
    val costToken: String? = null,
    val rideType: String? = null,
    val costTokenExpiryMs: Long? = null,
    val originalFareLowMinor: Long? = null,
    val originalFareHighMinor: Long? = null,
) {
    val isRange: Boolean get() = fareLowMinor != fareHighMinor

    /** True when a promotion reduced the fare below its original amount. */
    val hasDiscount: Boolean get() = originalFareLowMinor != null || originalFareHighMinor != null
}

/** Contract for quote result. */
sealed interface QuoteResult {
    /**
     * [purchaseSessionId] and [offersResponseId] are response-level identifiers from the offers
     * call, reused when creating a ride so the server can deduplicate a retried booking (see
     * `DuplicatedRideRequestException` in the API notes). Null for providers that do not return
     * them.
     */
    data class Success(
        val quotes: List<RideQuote>,
        val purchaseSessionId: String? = null,
        val offersResponseId: String? = null,
    ) : QuoteResult

    /** The provider is reachable but we are not signed in to it. */
    data object NotSignedIn : QuoteResult

    /** Immutable value describing failed; carries message. */
    data class Failed(val message: String) : QuoteResult
}

/**
 * A Lyft payment method, fetched live and held only in memory — never persisted. [id] is the
 * charge-account id; [chargeToken] is the token the booking request passes when this account is
 * the one being charged (may be null when the account id alone is used). [label] is a
 * display string such as "Visa ••1234".
 */
data class ChargeAccount(
    val id: String,
    val chargeToken: String?,
    val label: String,
    val isDefault: Boolean,
)

/** Result of fetching a provider's payment methods (in-memory, never persisted). */
sealed interface PaymentMethodsResult {
    /** Immutable value describing success; carries accounts. */
    data class Success(val accounts: List<ChargeAccount>) : PaymentMethodsResult

    /** Singleton holding not signed in state and operations. */
    data object NotSignedIn : PaymentMethodsResult

    /** Immutable value describing failed; carries message. */
    data class Failed(val message: String) : PaymentMethodsResult

    /** The provider does not support in-app payment management. */
    data object Unsupported : PaymentMethodsResult
}

/** Result of a payment-method mutation (set default / remove). */
sealed interface PaymentActionResult {
    /** [accounts] is the refreshed list when the server returned one, else null. */
    data class Success(val accounts: List<ChargeAccount>?) : PaymentActionResult

    /** Immutable value describing failed; carries message. */
    data class Failed(val message: String) : PaymentActionResult

    /** Singleton holding unsupported state and operations. */
    data object Unsupported : PaymentActionResult
}

/**
 * A raw card the user typed in, held only in memory for the duration of an add-card call and
 * never persisted or logged in full. [number] and [cvc] leave the process only as a processor
 * token/nonce (Stripe/Braintree), never as-is to Lyft.
 */
data class NewCard(
    val number: String,
    val expMonth: Int,
    val expYear: Int,
    val cvc: String,
    val postalCode: String,
) {
    /** Last four digits — safe to display/log. */
    val last4: String get() = number.takeLast(4)

    /** Issuer identification number (leading digits) — used to pick a tokenizer strategy. */
    val bin: String get() = number.take(8)
}

/** Result of adding a card. Mirrors [PaymentActionResult]; [accounts] is the refreshed list. */
sealed interface AddCardResult {
    /** Immutable value describing success; carries accounts. */
    data class Success(val accounts: List<ChargeAccount>?) : AddCardResult

    /** Immutable value describing failed; carries message. */
    data class Failed(val message: String) : AddCardResult

    /** Singleton holding unsupported state and operations. */
    data object Unsupported : AddCardResult
}

/**
 * Result of creating a ride. [DryRun] carries the exact request that *would* be sent so the UI
 * and logs can verify it against a real capture before any live send — no charge occurs.
 */
sealed interface BookingResult {
    /** Immutable value describing dry run; carries requestJson, account. */
    data class DryRun(val requestJson: String, val account: ChargeAccount?) : BookingResult

    /** Immutable value describing created; carries rideId, status, raw. */
    data class Created(val rideId: String?, val status: String?, val raw: String) : BookingResult

    /** Immutable value describing failed; carries message. */
    data class Failed(val message: String) : BookingResult

    /** Singleton holding unsupported state and operations. */
    data object Unsupported : BookingResult
}

/** The driver's live position on the map. [bearing] is degrees clockwise from north, if known. */
data class DriverLocation(
    val latitude: Double,
    val longitude: Double,
    val bearing: Double?,
)

/** The assigned driver. All fields are optional — the server omits what it hasn't populated yet. */
data class DriverInfo(
    val firstName: String?,
    val lastName: String?,
    val imageUrl: String?,
    val phoneNumber: String?,
    val rating: Double?,
) {
    /** First + last name joined; blank when the server hasn't sent a name yet. */
    val displayName: String
        get() = listOfNotNull(firstName, lastName).joinToString(" ").trim()
}

/** The assigned vehicle. */
data class VehicleInfo(
    val make: String?,
    val model: String?,
    val color: String?,
    val licensePlate: String?,
    val imageUrl: String?,
) {
    /** "Color Make Model", omitting whatever is missing; blank when nothing is known. */
    val description: String
        get() = listOfNotNull(color, make, model).joinToString(" ").trim()
}

/** A pickup / waypoint / dropoff stop on the ride, with the live per-stop ETA when present. */
data class RideStopInfo(
    val location: LatLng?,
    val name: String?,
    val kind: String?,
    val etaSeconds: Int?,
    val completed: Boolean,
) {
    val isPickup: Boolean get() = kind?.equals("pickup", ignoreCase = true) == true
    val isDropoff: Boolean get() = kind?.equals("dropoff", ignoreCase = true) == true
}

/**
 * The lifecycle state of a ride (`PassengerRideStatus`). [fromWire] maps the server's string onto
 * this enum, falling back to [UNKNOWN] for anything unrecognised.
 */
enum class RideStatus(val wire: String) {
    IDLE("idle"),
    LAPSED("lapsed"),
    PENDING("pending"),
    ACCEPTED("accepted"),
    APPROACHING("approaching"),
    CANCELED("canceled"),
    ARRIVED("arrived"),
    PICKED_UP("pickedUp"),
    DROPPED_OFF("droppedOff"),
    COMPLETED("completed"),
    PROCESSED("processed"),
    UNKNOWN("");

    /** Driver assigned and on the way to the pickup, but the rider isn't in the car yet. */
    val isPrePickup: Boolean get() = this == PENDING || this == ACCEPTED || this == APPROACHING || this == ARRIVED

    /** The ride has finished (or was cancelled) — polling should stop. */
    val isTerminal: Boolean get() = this == DROPPED_OFF || this == COMPLETED || this == PROCESSED || this == CANCELED

    /** There is a live ride worth tracking (pre-pickup or in-trip). */
    val isActive: Boolean get() = isPrePickup || this == PICKED_UP

    /** Shared constants and helpers for RideStatus. */
    companion object {
        fun fromWire(raw: String?): RideStatus {
            if (raw.isNullOrBlank()) return UNKNOWN
            return entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

/**
 * A ride in progress, parsed from `PassengerRide`. [driverLocation] is the live driver position;
 * [stops] carries per-stop ETAs. [raw] is a truncated copy of the response for display/debugging.
 */
data class ActiveRide(
    val rideId: String?,
    val status: RideStatus,
    val statusRaw: String?,
    val driver: DriverInfo?,
    val vehicle: VehicleInfo?,
    val driverLocation: DriverLocation?,
    val stops: List<RideStopInfo>,
    val raw: String,
) {
    /** Live "N seconds away" from the pickup stop, or the first stop still incomplete. */
    val pickupEtaSeconds: Int?
        get() = stops.firstOrNull { it.isPickup && !it.completed }?.etaSeconds
            ?: stops.firstOrNull { !it.completed }?.etaSeconds

    /** True when the ride carries enough to be worth surfacing (id, known status, or a driver). */
    val hasContent: Boolean
        get() = rideId != null || status != RideStatus.UNKNOWN || driverLocation != null || driver != null
}

/** Result of reading the active ride. */
sealed interface RideStatusResult {
    /** Immutable value describing active; carries ride. */
    data class Active(val ride: ActiveRide) : RideStatusResult

    /** No ride in progress. */
    data object None : RideStatusResult

    /** Immutable value describing failed; carries message. */
    data class Failed(val message: String) : RideStatusResult

    /** Singleton holding unsupported state and operations. */
    data object Unsupported : RideStatusResult
}

/** Result of a cancel request. Server response is surfaced verbatim — a cancel may incur a fee. */
sealed interface CancelResult {
    /** Immutable value describing done; carries message. */
    data class Done(val message: String) : CancelResult

    /** Immutable value describing failed; carries message. */
    data class Failed(val message: String) : CancelResult

    /** Singleton holding unsupported state and operations. */
    data object Unsupported : CancelResult
}
