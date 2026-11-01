package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.NativeCheckoutState
import com.vayunmathur.shop.data.ShopProfile

/** Checkout never takes buyer input; every session field is derived from the saved profile. */
object ProfileCheckout {
    /** Every write carries the full saved profile, including the delivery address when one is saved. */
    fun inputs(profile: ShopProfile, state: NativeCheckoutState): CheckoutInputs {
        val inputs = profile.checkoutInputs()
        return if (profile.hasAddress) inputs.copy(shippingRequested = true, options = state.inputs.options) else inputs
    }
}
