package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/**
 * A Duffel customer user, returned by the `/api/travel/customers` endpoints.
 * Orders are associated with one so bookings/payments can be tracked per
 * customer.
 */
@Serializable
data class CustomerDto(
    val id: String = "",
    val email: String = "",
    val givenName: String = "",
    val familyName: String = "",
    val phoneNumber: String? = null,
)

/** Body for `POST /api/travel/customers` to create a customer user. */
@Serializable
data class CustomerUserInputDto(
    val email: String = "",
    val givenName: String = "",
    val familyName: String = "",
    val phoneNumber: String? = null,
)
