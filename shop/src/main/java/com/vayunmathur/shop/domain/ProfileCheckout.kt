package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.NativeCheckoutState
import com.vayunmathur.shop.data.ShopProfile
import com.vayunmathur.shop.data.UcpCheckout

/** Checkout never takes buyer input; every session field is derived from the saved profile. */
object ProfileCheckout {
    fun needsDeliveryAddress(checkout: UcpCheckout?): Boolean = checkout != null && (
        checkout.review.availableShippingLineIds.isNotEmpty() || checkout.review.shipping.isNotEmpty() ||
            checkout.messages.any { it.code == "delivery_address_required" }
        )

    /** The address is only sent once the merchant has issued shipping lines it can attach to. */
    fun inputs(profile: ShopProfile, state: NativeCheckoutState): CheckoutInputs {
        val shipping = profile.hasAddress && needsDeliveryAddress(state.checkout)
        val inputs = profile.checkoutInputs()
        return if (shipping) inputs.copy(shippingRequested = true, options = state.inputs.options)
        else inputs.copy(street = "", extendedAddress = "", city = "", region = "", country = "", postalCode = "")
    }
}
