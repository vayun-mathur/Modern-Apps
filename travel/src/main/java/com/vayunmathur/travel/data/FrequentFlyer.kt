package com.vayunmathur.travel.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved frequent-flyer account. Keyed by [airlineIata] (one account per
 * airline). [airlineName] is cached for display; [accountNumber] is the member
 * number sent as loyalty pricing at search and pre-filled at booking.
 */
@Entity
data class FrequentFlyer(
    @PrimaryKey val airlineIata: String,
    val accountNumber: String,
    val airlineName: String = "",
)
