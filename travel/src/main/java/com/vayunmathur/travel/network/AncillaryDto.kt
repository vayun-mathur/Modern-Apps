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

/**
 * One element in a seat-map row section. [type] is `seat`/`restricted_seat_general`
 * (a real seat) or an amenity/spacer (`empty`, `lavatory`, `galley`, `exit_row`,
 * `bassinet`, `closet`, `stairs`). Seats are selectable only when [available].
 */
@Serializable
data class SeatElementDto(
    val type: String = "",
    val designator: String = "",
    val available: Boolean = false,
    val serviceId: String? = null,
    val totalAmount: String? = null,
    val totalCurrency: String? = null,
    val name: String? = null,
) {
    val isSeat: Boolean get() = type == "seat" || type == "restricted_seat_general"
}

/** A contiguous group of elements between aisles. */
@Serializable
data class SeatSectionDto(
    val elements: List<SeatElementDto> = emptyList(),
)

/** A cabin row, split into sections by the aisle(s). */
@Serializable
data class SeatRowDto(
    val sections: List<SeatSectionDto> = emptyList(),
)

/** A cabin's seat map for a single segment. */
@Serializable
data class SeatCabinDto(
    val segmentId: String = "",
    val cabinClass: String = "",
    val deck: Long = 0,
    val aisles: Long = 0,
    val rows: List<SeatRowDto> = emptyList(),
)

/** A selected add-on service to include at order time. */
@Serializable
data class ServiceSelectionDto(
    val id: String = "",
    val quantity: Long = 1,
    val passengerId: String? = null,
)
