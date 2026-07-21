package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/**
 * A passport captured for a passenger when the offer requires identity
 * documents. [type] is `passport`; [expiresOn] is `YYYY-MM-DD`.
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
 * A passenger the app collects and sends when booking. [id] is the Duffel
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

/**
 * Payment instruction. Sandbox books with `{"type":"balance"}`; live card
 * payments (deferred) would carry a card token here instead.
 */
@Serializable
data class PaymentInputDto(
    val type: String = "balance",
)

/** The order-creation body posted to `POST /api/travel/orders`. */
@Serializable
data class OrderRequestDto(
    val offerId: String = "",
    val passengers: List<PassengerInputDto> = emptyList(),
    val payment: PaymentInputDto = PaymentInputDto(),
    val services: List<ServiceSelectionDto> = emptyList(),
    val hold: Boolean = false,
    val customerUserId: String? = null,
)

/**
 * A Duffel order event recorded server-side from a webhook (schedule change /
 * cancellation), surfaced so the app can show an alert banner.
 */
@Serializable
data class OrderEventDto(
    val id: String = "",
    val orderId: String = "",
    val type: String = "",
    val createdAt: String = "",
    val message: String = "",
)

/** A confirmed order returned by `POST /api/travel/orders`. */
@Serializable
data class OrderResultDto(
    val orderId: String = "",
    val bookingReference: String = "",
    val totalAmount: String = "0",
    val currency: String = "USD",
    val awaitingPayment: Boolean = false,
    val paymentRequiredBy: String? = null,
)

/**
 * A remote order synced from Duffel (`GET /api/travel/orders` / `/orders/{id}`).
 * [status] is `confirmed`/`cancelled`; [paymentStatus] is `paid`/`awaiting_payment`.
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
)
