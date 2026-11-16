package com.vayunmathur.fooddelivery.intents

import com.vayunmathur.library.intents.fooddelivery.CartData
import com.vayunmathur.library.intents.fooddelivery.SetCart
import com.vayunmathur.library.util.AssistantIntent

/**
 * Assistant tool `food_set_cart` (provider side): re-validates every line
 * against the live menu, then replaces the provider cart in one atomic
 * [com.vayunmathur.fooddelivery.data.CartStore] write. A different merchant
 * replaces the previous cart (single-merchant rule). Never creates an order.
 */
class SetCartIntent : AssistantIntent<SetCart, CartData>(
    SetCart.serializer(),
    CartData.serializer(),
) {
    override suspend fun performCalculation(input: SetCart): CartData =
        setCart(this, input, foodAssistantBackend())
}
