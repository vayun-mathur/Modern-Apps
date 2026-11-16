package com.vayunmathur.fooddelivery.intents

import com.vayunmathur.library.intents.fooddelivery.GetOrders
import com.vayunmathur.library.intents.fooddelivery.OrderData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.builtins.ListSerializer

/** Assistant tool `get_food_orders`: the signed-in customer's past orders. */
class GetOrdersIntent : AssistantIntent<GetOrders, List<OrderData>>(
    GetOrders.serializer(),
    ListSerializer(OrderData.serializer()),
) {
    override suspend fun performCalculation(input: GetOrders): List<OrderData> =
        getOrders(this, foodAssistantBackend())
}
