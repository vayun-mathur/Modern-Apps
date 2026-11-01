package com.vayunmathur.travel.domain

import com.vayunmathur.travel.network.OfferPassengerDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.ServiceSelectionDto

/**
 * Per-passenger seat selection. The selected-seats map is keyed
 * `"<segmentId>|<passengerId>"`: at most one seat per passenger per segment,
 * and a seat belongs to at most one passenger. Pure: covered by unit tests.
 */

fun seatKey(segmentId: String, passengerId: String): String = "$segmentId|$passengerId"

/** Passengers who occupy a seat (infants on a lap don't). */
fun seatedPassengers(passengers: List<OfferPassengerDto>): List<OfferPassengerDto> =
    passengers.filter { it.type != "infant_without_seat" }

/** The passenger holding [designator] on [segmentId], if any. */
fun seatHolder(selected: Map<String, SeatElementDto>, segmentId: String, designator: String): String? =
    selected.entries
        .firstOrNull { (key, seat) -> key.startsWith("$segmentId|") && seat.designator == designator }
        ?.key
        ?.substringAfter('|')

/**
 * Tap [seat] for [passengerId] on [segmentId]: tapping the passenger's own seat
 * clears it; otherwise the seat replaces their previous one. Seats held by
 * someone else, unavailable seats and seats without a service are ignored.
 */
fun assignSeat(
    selected: Map<String, SeatElementDto>,
    segmentId: String,
    passengerId: String,
    seat: SeatElementDto,
): Map<String, SeatElementDto> {
    if (!seat.available || seat.serviceId == null) return selected
    val key = seatKey(segmentId, passengerId)
    val holder = seatHolder(selected, segmentId, seat.designator)
    return when (holder) {
        passengerId -> selected - key
        null -> selected + (key to seat)
        else -> selected
    }
}

/** The passenger to pick for next: the first seated passenger without a seat on [segmentId]. */
fun nextPassengerToSeat(
    selected: Map<String, SeatElementDto>,
    segmentId: String,
    passengers: List<OfferPassengerDto>,
): String? {
    val seated = seatedPassengers(passengers)
    return seated.firstOrNull { seatKey(segmentId, it.id) !in selected }?.id ?: seated.firstOrNull()?.id
}

/** Seat selections for the server, each tagged with its passenger. */
fun seatSelections(selected: Map<String, SeatElementDto>): List<ServiceSelectionDto> =
    selected.mapNotNull { (key, seat) ->
        val passengerId = key.substringAfter('|', "").ifBlank { null }
        seat.serviceId?.let { ServiceSelectionDto(id = it, quantity = 1, passengerId = passengerId) }
    }
