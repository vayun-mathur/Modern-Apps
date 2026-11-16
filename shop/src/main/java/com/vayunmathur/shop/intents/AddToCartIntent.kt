package com.vayunmathur.shop.intents

import com.vayunmathur.library.intents.shop.AddToCartRequest
import com.vayunmathur.library.intents.shop.CartData
import com.vayunmathur.library.util.AssistantIntent

/**
 * Assistant tool: add an exact, re-verified variant to the local cart.
 * Prepare-only — this never creates/updates a checkout, never holds an
 * idempotency key, and the user still checks out in the shop app
 * (`shop://cart` handoff). A failed verification leaves the cart unchanged.
 */
class AddToCartIntent : AssistantIntent<AddToCartRequest, CartData>(
    AddToCartRequest.serializer(),
    CartData.serializer(),
) {
    override suspend fun performCalculation(input: AddToCartRequest): CartData {
        awaitShopReady(this)
        return addToCart(this, input, shopAssistantBackend())
    }
}
