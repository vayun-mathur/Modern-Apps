package com.vayunmathur.shop.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** Single-row buyer profile. Lives only in the SQLCipher database; never logged or put in history. */
@Entity
data class ShopProfile(
    @PrimaryKey val id: Int = 0,
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val phone: String = "",
    val street: String = "",
    val extendedAddress: String = "",
    val city: String = "",
    val region: String = "",
    val country: String = "",
    val postalCode: String = "",
) {
    val hasAddress: Boolean get() = checkoutInputs().hasAddress

    fun checkoutInputs(shippingRequested: Boolean = false, options: Map<String, String> = emptyMap()) = CheckoutInputs(
        firstName = firstName.trim(), lastName = lastName.trim(), email = email.trim(), phone = phone.trim(),
        street = street.trim(), extendedAddress = extendedAddress.trim(), city = city.trim(),
        region = region.trim(), country = country.trim(), postalCode = postalCode.trim(),
        options = options, shippingRequested = shippingRequested,
    )

    fun validationError(): String? =
        if (listOf(firstName, lastName, email, phone).any { it.isBlank() }) {
            "Enter your first name, last name, email and phone number."
        } else {
            checkoutInputs().validationError()
        }

    override fun toString(): String = "ShopProfile(redacted)"
}
