package com.vayunmathur.shop.intents

import com.vayunmathur.library.intents.shop.ProductData
import com.vayunmathur.library.intents.shop.SearchProductsQuery
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.builtins.ListSerializer

/** Assistant tool: catalog search. Prepare-only; never starts checkout. */
class SearchProductsIntent : AssistantIntent<SearchProductsQuery, List<ProductData>>(
    SearchProductsQuery.serializer(),
    ListSerializer(ProductData.serializer()),
) {
    override suspend fun performCalculation(input: SearchProductsQuery): List<ProductData> {
        awaitShopReady(this)
        return searchProducts(input, shopAssistantBackend())
    }
}
