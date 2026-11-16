package com.vayunmathur.shop.intents

import com.vayunmathur.library.intents.shop.CartData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.builtins.serializer

/** Assistant tool: local cart, grouped by store. */
class GetCartIntent : AssistantIntent<Unit, CartData>(
    Unit.serializer(),
    CartData.serializer(),
) {
    override suspend fun performCalculation(input: Unit): CartData =
        getCart(this, shopAssistantBackend())
}
