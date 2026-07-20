package com.vayunmathur.travel.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A booked flight, persisted after a successful order so it shows up under
 * "My trips" and survives restarts. Keyed by [orderId] (Duffel `ord_…`).
 * [bookingReference] is the airline PNR; [route] is a pre-rendered summary like
 * "LHR → JFK"; [passengersJson] is the JSON-encoded passenger list for detail.
 */
@Entity
data class BookedTrip(
    @PrimaryKey val orderId: String,
    val bookingReference: String,
    val route: String,
    val departDate: String,
    val amount: String,
    val currency: String,
    val passengersJson: String = "",
    val status: String = "confirmed",
    val createdAt: Long = System.currentTimeMillis(),
)
