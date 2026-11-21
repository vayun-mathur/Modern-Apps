package com.vayunmathur.travel

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding flights-home state and operations. */
    @Serializable
    data object FlightsHome : Route

    /** Singleton holding stays-home state and operations. */
    @Serializable
    data object StaysHome : Route

    /** Immutable value describing flight results. */
    @Serializable
    data class FlightResults(
        val slices: String,
        val adults: Int,
        val children: String,
        val infants: Int,
        val cabin: String,
        val maxConnections: Int,
    ) : Route

    /** Immutable value describing offer review; carries offerId. */
    @Serializable
    data class OfferReview(val offerId: String) : Route

    /** Immutable value describing outbound select. */
    @Serializable
    data class OutboundSelect(
        val slices: String,
        val adults: Int,
        val children: String,
        val infants: Int,
        val cabin: String,
        val maxConnections: Int,
    ) : Route


    /** Immutable value describing fare select; carries outboundSignature, inboundSignature. */
    @Serializable
    data class FareSelect(val outboundSignature: String, val inboundSignature: String) : Route

    /** Immutable value describing ancillaries; carries offerId. */
    @Serializable
    data class Ancillaries(val offerId: String) : Route

    /** Immutable value describing seat map; carries offerId, segmentId. */
    @Serializable
    data class SeatMap(val offerId: String, val segmentId: String) : Route

    /** Immutable value describing passengers; carries offerId. */
    @Serializable
    data class Passengers(val offerId: String) : Route

    /** Immutable value describing payment; carries offerId. */
    @Serializable
    data class Payment(val offerId: String) : Route

    /** Immutable value describing confirmation; carries orderId. */
    @Serializable
    data class Confirmation(val orderId: String) : Route

    /** Immutable value describing order detail; carries orderId. */
    @Serializable
    data class OrderDetail(val orderId: String) : Route

    /** Immutable value describing cancel; carries orderId. */
    @Serializable
    data class Cancel(val orderId: String) : Route

    /** Immutable value describing change; carries orderId. */
    @Serializable
    data class Change(val orderId: String) : Route

    /** Immutable value describing stay results. */
    @Serializable
    data class StayResults(
        val place: String,
        val checkIn: String,
        val checkOut: String,
        val rooms: Int,
        val adults: Int,
        val latitude: Double = Double.NaN,
        val longitude: Double = Double.NaN,
    ) : Route

    /** Immutable value describing stay detail; carries searchResultId, name. */
    @Serializable
    data class StayDetail(val searchResultId: String, val name: String) : Route

    /** Immutable value describing stay guests; carries rateId. */
    @Serializable
    data class StayGuests(val rateId: String) : Route

    /** Immutable value describing stay confirmation; carries bookingId. */
    @Serializable
    data class StayConfirmation(val bookingId: String) : Route

    /** Singleton holding trips state and operations. */
    @Serializable
    data object Trips : Route

    /** Singleton holding settings state and operations. */
    @Serializable
    data object Settings : Route
}
