package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/**
 * An add-on service available on an [OfferDto] (extra baggage today). [type] is
 * Duffel's service type (e.g. `baggage`); [title] is a human label.
 */
@Serializable
data class ServiceDto(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val totalAmount: String = "0",
    val totalCurrency: String = "USD",
    val maxQuantity: Long = 1,
    val segmentIds: List<String> = emptyList(),
    val passengerIds: List<String> = emptyList(),
)

/** One seat on a seat map. [available] gates selection; [serviceId] (+ amount) is set when it costs extra. */
@Serializable
data class SeatDto(
    val designator: String = "",
    val available: Boolean = false,
    val serviceId: String? = null,
    val totalAmount: String? = null,
    val totalCurrency: String? = null,
)

/** A flattened row of seats. */
@Serializable
data class SeatRowDto(
    val seats: List<SeatDto> = emptyList(),
)

/** A cabin's seat map for a single segment. */
@Serializable
data class SeatCabinDto(
    val segmentId: String = "",
    val cabinClass: String = "",
    val rows: List<SeatRowDto> = emptyList(),
)

/** A selected add-on service to include at order time. */
@Serializable
data class ServiceSelectionDto(
    val id: String = "",
    val quantity: Long = 1,
)
