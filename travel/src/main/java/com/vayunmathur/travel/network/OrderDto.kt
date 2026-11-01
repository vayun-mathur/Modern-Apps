package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/**
 * A passport captured for a passenger. [type] is `passport`; [expiresOn] is
 * `YYYY-MM-DD`. Passport fields are shown as optional unless checkout reports
 * them missing (HTTP 422 `missingFields`).
 */
@Serializable
data class IdentityDocumentDto(
    val type: String = "passport",
    val uniqueIdentifier: String = "",
    val issuingCountryCode: String = "",
    val expiresOn: String = "",
)

/** A frequent-flyer account tied to an airline, for a passenger. */
@Serializable
data class LoyaltyAccountDto(
    val airlineIataCode: String = "",
    val accountNumber: String = "",
)

/**
 * A passenger the app collects and sends when booking. [id] is the booking
 * passenger id carried on the offer's passenger list; [title] is `mr`/`ms`/…,
 * [gender] is `m`/`f`, [bornOn] is `YYYY-MM-DD`.
 */
@Serializable
data class PassengerInputDto(
    val id: String = "",
    val title: String = "",
    val givenName: String = "",
    val familyName: String = "",
    val bornOn: String = "",
    val gender: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val identityDocument: IdentityDocumentDto? = null,
    val loyaltyProgrammeAccounts: List<LoyaltyAccountDto> = emptyList(),
    val infantPassengerId: String? = null,
)

/** Contact details defaulting from the first passenger when omitted. */
@Serializable
data class TripContactDto(
    val email: String = "",
    val phoneNumber: String = "",
)

/** The trip-creation body posted to `POST /api/travel/trips`. */
@Serializable
data class TripRequestDto(
    val offerIds: List<String> = emptyList(),
    val passengers: List<PassengerInputDto> = emptyList(),
    val contact: TripContactDto? = null,
)

/** One item on a trip (a flight leg bundle or a hotel). */
@Serializable
data class TripItemDto(
    val itemId: String = "",
    val kind: String = "",
    val status: String = "",
)

/**
 * A booking trip from `POST /api/travel/trips` / `GET /api/travel/trips/:id`.
 * [status] is `draft` | `pending_payment` | `processing` | `confirmed` |
 * `partial` | `failed` | `cancelled` | `expired`. [bookingReference] is empty
 * until the booking is confirmed.
 */
@Serializable
data class TripDto(
    val tripId: String = "",
    val status: String = "",
    val bookingReference: String = "",
    val totalAmount: String = "0",
    val currency: String = "USD",
    val items: List<TripItemDto> = emptyList(),
    val airlineReferences: List<String> = emptyList(),
    val failureMessage: String? = null,
)

/**
 * Ancillaries for a trip (`GET /api/travel/trips/:id/ancillaries`): bag
 * services plus seat maps. [seatMapsPending] means the seat maps are still
 * being priced — the seat page shows a loading state with a retry.
 */
@Serializable
data class TripAncillariesDto(
    val status: String = "",
    val services: List<ServiceDto> = emptyList(),
    val seatMaps: List<SeatCabinDto> = emptyList(),
    val seatMapsPending: Boolean = false,
)

/** `202 pricing` body: the trip is still being priced. */
@Serializable
data class TripPricingDto(
    val status: String = "",
    val retryAfterSeconds: Int = 5,
)

/** Body for `POST /api/travel/trips/:id/ancillaries` (replaces all ancillaries). */
@Serializable
data class AncillariesSelectionRequestDto(
    val services: List<ServiceSelectionDto> = emptyList(),
)

/** Totals returned after saving ancillary selections. */
@Serializable
data class AncillariesSelectionResultDto(
    val totalAmount: String = "0",
    val currency: String = "USD",
)

/**
 * Payment details from `POST /api/travel/trips/:id/checkout`: confirm
 * [clientSecret] with the native payment sheet (initialized with
 * [publishableKey]), then call `POST /trips/:id/confirm`. When [priceChanged],
 * show [originalAmount] vs [totalAmount] and require an explicit accept first.
 */
@Serializable
data class CheckoutDto(
    val clientSecret: String = "",
    val publishableKey: String = "",
    val quoteId: String = "",
    val totalAmount: String = "0",
    val currency: String = "USD",
    val expiresAt: String = "",
    val priceChanged: Boolean = false,
    val originalAmount: String? = null,
)

/**
 * A booked trip's detail (`GET /api/travel/bookings/:ref?last_name=`).
 * [status] is `confirmed` | `processing` | `cancelled` | `failed` | `partial`;
 * [paymentStatus] is `paid`. [kind] is `flight` | `hotel`.
 */
@Serializable
data class OrderDetailDto(
    val orderId: String = "",
    val bookingReference: String = "",
    val totalAmount: String = "0",
    val currency: String = "USD",
    val status: String = "confirmed",
    val paymentStatus: String = "paid",
    val paymentRequiredBy: String? = null,
    val passengerNames: List<String> = emptyList(),
    val slices: List<SliceDto> = emptyList(),
    val kind: String = "flight",
    val canCancel: Boolean = false,
    val canChange: Boolean = false,
    val ticketNumbers: List<String> = emptyList(),
    val hotel: StayBookingHotelDto? = null,
)

/** Hotel detail carried on a booked stay's [OrderDetailDto]. */
@Serializable
data class StayBookingHotelDto(
    val name: String = "",
    val address: String = "",
    val checkIn: String = "",
    val checkOut: String = "",
    val roomName: String = "",
    val boardName: String = "",
)
