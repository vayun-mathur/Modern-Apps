package com.vayunmathur.shop.intents

import com.vayunmathur.library.intents.shop.CartData
import com.vayunmathur.library.intents.shop.RemoveFromCartRequest
import com.vayunmathur.library.util.AssistantIntent

/** Assistant tool: remove a variant from the local cart. Local-only. */
class RemoveFromCartIntent : AssistantIntent<RemoveFromCartRequest, CartData>(
    RemoveFromCartRequest.serializer(),
    CartData.serializer(),
) {
    override suspend fun performCalculation(input: RemoveFromCartRequest): CartData =
        removeFromCart(this, input, shopAssistantBackend())
}
