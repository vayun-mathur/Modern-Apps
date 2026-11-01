package com.vayunmathur.travel.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Everything the flight search form shows, hoisted to the home screen so it is
 * still filled in after coming back from results (and across tab switches).
 * [originText]/[destinationText] are the visible field text (e.g.
 * "JFK · New York"); [origin]/[destination] are the codes searched.
 */
data class FlightFormState(
    val tripType: TripType = TripType.ROUND_TRIP,
    val origin: String = "",
    val originText: String = "",
    val destination: String = "",
    val destinationText: String = "",
    val depart: String = "",
    val returnDate: String = "",
    val mix: PassengerMix = PassengerMix(),
    val cabin: Cabin = Cabin.ECONOMY,
    val maxStops: MaxStops = MaxStops.TWO_PLUS,
) {
    fun swapped() = copy(
        origin = destination,
        originText = destinationText,
        destination = origin,
        destinationText = originText,
    )
}

/** Stay search form state, hoisted like [FlightFormState]. */
data class StayFormState(
    val place: String = "",
    val placeText: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val checkIn: String = "",
    val checkOut: String = "",
    val rooms: Int = 1,
    val guests: Int = 2,
)

val FlightFormSaver: Saver<FlightFormState, Any> = listSaver(
    save = {
        listOf(
            it.tripType.name, it.origin, it.originText, it.destination, it.destinationText,
            it.depart, it.returnDate, it.mix.adults, ArrayList(it.mix.childAges),
            it.mix.infantsInSeat, it.mix.infantsOnLap, it.cabin.name, it.maxStops.name,
        )
    },
    restore = {
        @Suppress("UNCHECKED_CAST")
        FlightFormState(
            tripType = TripType.valueOf(it[0] as String),
            origin = it[1] as String,
            originText = it[2] as String,
            destination = it[3] as String,
            destinationText = it[4] as String,
            depart = it[5] as String,
            returnDate = it[6] as String,
            mix = PassengerMix(
                adults = it[7] as Int,
                childAges = it[8] as List<Int>,
                infantsInSeat = it[9] as Int,
                infantsOnLap = it[10] as Int,
            ),
            cabin = Cabin.valueOf(it[11] as String),
            maxStops = MaxStops.valueOf(it[12] as String),
        )
    },
)

val StayFormSaver: Saver<StayFormState, Any> = listSaver(
    save = {
        listOf(
            it.place, it.placeText, it.latitude ?: Double.NaN, it.longitude ?: Double.NaN,
            it.checkIn, it.checkOut, it.rooms, it.guests,
        )
    },
    restore = {
        StayFormState(
            place = it[0] as String,
            placeText = it[1] as String,
            latitude = (it[2] as Double).takeUnless(Double::isNaN),
            longitude = (it[3] as Double).takeUnless(Double::isNaN),
            checkIn = it[4] as String,
            checkOut = it[5] as String,
            rooms = it[6] as Int,
            guests = it[7] as Int,
        )
    },
)
