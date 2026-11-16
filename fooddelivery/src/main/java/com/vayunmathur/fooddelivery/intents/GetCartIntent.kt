package com.vayunmathur.fooddelivery.intents

import com.vayunmathur.library.intents.fooddelivery.CartData
import com.vayunmathur.library.intents.fooddelivery.GetCart
import com.vayunmathur.library.util.AssistantIntent

/** Assistant tool `food_get_cart`: reads the provider-owned [com.vayunmathur.fooddelivery.data.CartStore]. */
class GetCartIntent : AssistantIntent<GetCart, CartData>(
    GetCart.serializer(),
    CartData.serializer(),
) {
    override suspend fun performCalculation(input: GetCart): CartData =
        getCart(this, foodAssistantBackend())
}
