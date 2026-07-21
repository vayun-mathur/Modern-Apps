package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/** A summarized accommodation from a stays search. */
@Serializable
data class StaySearchResultDto(
    val id: String = "",
    val name: String = "",
    val rating: Long = 0,
    val reviewScore: Double = 0.0,
    val photoUrl: String = "",
    val photos: List<String> = emptyList(),
    val amenities: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String = "",
    val cheapestAmount: String = "0",
    val cheapestCurrency: String = "USD",
)

/** A location suggestion for the stays search box (free-text → coordinates). */
@Serializable
data class StaySuggestionDto(
    val name: String = "",
    val kind: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** One bookable rate on a room. */
@Serializable
data class StayRateDto(
    val id: String = "",
    val boardType: String = "",
    val totalAmount: String = "0",
    val totalCurrency: String = "USD",
    val cancellation: String = "",
)

/** A room type with its rates. */
@Serializable
data class StayRoomDto(
    val name: String = "",
    val rates: List<StayRateDto> = emptyList(),
)

/** Full rate detail for one accommodation. */
@Serializable
data class StayRatesDto(
    val name: String = "",
    val rating: Long = 0,
    val reviewScore: Double = 0.0,
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val photos: List<String> = emptyList(),
    val amenities: List<String> = emptyList(),
    val rooms: List<StayRoomDto> = emptyList(),
)

/** A confirmed price quote for a chosen rate. */
@Serializable
data class StayQuoteDto(
    val id: String = "",
    val totalAmount: String = "0",
    val totalCurrency: String = "USD",
)

/** A hotel loyalty membership for a guest. */
@Serializable
data class StayLoyaltyAccountDto(
    val programmeName: String = "",
    val accountNumber: String = "",
)

/** A guest supplied when booking a stay. */
@Serializable
data class StayGuestInputDto(
    val givenName: String = "",
    val familyName: String = "",
    val bornOn: String = "",
    val loyaltyProgrammeAccount: StayLoyaltyAccountDto? = null,
)

/** The stay-booking body posted to `POST /api/stays/bookings`. */
@Serializable
data class StayBookingRequestDto(
    val quoteId: String = "",
    val guests: List<StayGuestInputDto> = emptyList(),
    val email: String = "",
    val phoneNumber: String = "",
)

/** A stay booking returned after booking / in the bookings list. */
@Serializable
data class StayBookingResultDto(
    val id: String = "",
    val reference: String = "",
    val status: String = "confirmed",
    val accommodationName: String = "",
    val checkInDate: String = "",
    val checkOutDate: String = "",
    val totalAmount: String = "0",
    val totalCurrency: String = "USD",
)
