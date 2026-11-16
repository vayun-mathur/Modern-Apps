package com.vayunmathur.shop.intents

import com.vayunmathur.library.intents.shop.GetProductQuery
import com.vayunmathur.library.intents.shop.ProductDetailData
import com.vayunmathur.library.util.AssistantIntent

/** Assistant tool: product detail + variants for one catalog product. */
class GetProductIntent : AssistantIntent<GetProductQuery, ProductDetailData>(
    GetProductQuery.serializer(),
    ProductDetailData.serializer(),
) {
    override suspend fun performCalculation(input: GetProductQuery): ProductDetailData {
        awaitShopReady(this)
        return getProductDetail(input, shopAssistantBackend())
    }
}
