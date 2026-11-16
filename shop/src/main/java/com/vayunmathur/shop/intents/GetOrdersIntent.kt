package com.vayunmathur.shop.intents

import com.vayunmathur.library.intents.shop.OrderData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Assistant tool: placed-order / handoff summaries from the local store.
 * A `pending` handoff is not a paid order; merchant pages stay authoritative.
 */
class GetOrdersIntent : AssistantIntent<Unit, List<OrderData>>(
    Unit.serializer(),
    ListSerializer(OrderData.serializer()),
) {
    override suspend fun performCalculation(input: Unit): List<OrderData> =
        getOrders(this, shopAssistantBackend())
}
