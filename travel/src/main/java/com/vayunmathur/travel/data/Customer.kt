package com.vayunmathur.travel.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved Duffel customer user. [id] is the Duffel customer-user id (`icu_…`)
 * used to associate orders so bookings/payments can be tracked per customer.
 */
@Entity
data class Customer(
    @PrimaryKey val id: String,
    val email: String,
    val givenName: String,
    val familyName: String,
    val phoneNumber: String = "",
) {
    val displayName: String
        get() = listOf(givenName, familyName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { email }
}
