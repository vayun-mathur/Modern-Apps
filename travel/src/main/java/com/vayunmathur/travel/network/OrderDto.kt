package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

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
)

/** A confirmed order returned by `POST /api/travel/orders`. */
@Serializable
data class OrderResultDto(
    val orderId: String = "",
    val bookingReference: String = "",
    val totalAmount: String = "0",
    val currency: String = "USD",
)
