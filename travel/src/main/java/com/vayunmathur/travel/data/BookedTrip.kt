package com.vayunmathur.travel.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A booked trip, persisted after a successful order so it shows up under "My
 * trips" and survives restarts. Keyed by [orderId] (Duffel `ord_…`).
 * [bookingReference] is the airline PNR; [route] is a pre-rendered summary like
 * "LHR → JFK"; [passengersJson] is the JSON-encoded passenger list for detail.
 * [type] is `flight` or `stay`; hold orders carry [awaitingPayment] +
 * [paymentRequiredBy] until settled.
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
    val type: String = "flight",
    val awaitingPayment: Boolean = false,
    val paymentRequiredBy: String = "",
    val remoteSyncedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
