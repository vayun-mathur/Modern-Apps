package com.vayunmathur.travel.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** Trip types stored in [BookedTrip.type]. */
const val TRIP_TYPE_FLIGHT = "flight"
const val TRIP_TYPE_STAY = "stay"

/** Trip lifecycle statuses from the server trip / bookings endpoints. */
const val TRIP_STATUS_PENDING_PAYMENT = "pending_payment"
const val TRIP_STATUS_PROCESSING = "processing"
const val TRIP_STATUS_CONFIRMED = "confirmed"
const val TRIP_STATUS_PARTIAL = "partial"
const val TRIP_STATUS_FAILED = "failed"
const val TRIP_STATUS_CANCELLED = "cancelled"

/**
 * A booked trip, persisted when checkout starts so it shows up under
 * "My trips" and survives restarts. Keyed by [orderId]: the trip id until a
 * booking reference exists, then kept stable (the reference is stored in
 * [bookingReference] separately). [tripId] is the server trip id used for
 * polling/status refresh; [checkoutExpiresAt] gates the "Complete payment"
 * button; [lastName] authenticates servicing calls. [route] is a pre-rendered
 * summary like "LHR → JFK"; [passengersJson] is the JSON-encoded passenger
 * list for detail. [type] is `flight` or `stay`.
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
    val customerId: String = "",
    val tripId: String = "",
    val checkoutExpiresAt: String = "",
    val lastName: String = "",
    /** Server failure message when status is failed (blank otherwise). */
    val failureMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
