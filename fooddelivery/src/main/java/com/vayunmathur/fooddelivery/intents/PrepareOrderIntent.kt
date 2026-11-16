package com.vayunmathur.fooddelivery.intents

import com.vayunmathur.library.intents.fooddelivery.OrderSummaryData
import com.vayunmathur.library.intents.fooddelivery.PrepareOrder
import com.vayunmathur.library.util.AssistantIntent

/**
 * Assistant tool `food_prepare_order`: prices the provider cart host-side
 * WITHOUT creating an order (no checkout call, no client secret). The user
 * places/pays in-app via the returned `fooddelivery://checkout` deep link.
 */
class PrepareOrderIntent : AssistantIntent<PrepareOrder, OrderSummaryData>(
    PrepareOrder.serializer(),
    OrderSummaryData.serializer(),
) {
    override suspend fun performCalculation(input: PrepareOrder): OrderSummaryData =
        prepareOrder(this, input, foodAssistantBackend())
}
