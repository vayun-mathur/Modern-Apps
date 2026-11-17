package com.vayunmathur.shop.intents

import android.content.Context
import com.vayunmathur.library.intents.shop.AddToCartRequest
import com.vayunmathur.library.intents.shop.CartData
import com.vayunmathur.library.intents.shop.CartLineData
import com.vayunmathur.library.intents.shop.CartStoreData
import com.vayunmathur.library.intents.shop.GetProductQuery
import com.vayunmathur.library.intents.shop.OrderData
import com.vayunmathur.library.intents.shop.ProductData
import com.vayunmathur.library.intents.shop.ProductDetailData
import com.vayunmathur.library.intents.shop.ProductVariantData
import com.vayunmathur.library.intents.shop.RemoveFromCartRequest
import com.vayunmathur.library.intents.shop.SearchProductsQuery
import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.OrderStore
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.ShopCartStore
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.network.ShopBackend
import com.vayunmathur.shop.network.ShopHttpBackend
import com.vayunmathur.shop.platform.AppInit

/** Typed failure codes returned in DTO `error` fields (never thrown). */
internal const val ERROR_INVALID_REQUEST = "INVALID_REQUEST"
internal const val ERROR_INVALID_QUANTITY = "INVALID_QUANTITY"
internal const val ERROR_VARIANT_NOT_FOUND = "VARIANT_NOT_FOUND"
internal const val ERROR_VARIANT_UNAVAILABLE = "VARIANT_UNAVAILABLE"
internal const val ERROR_NOT_CHECKOUT_ELIGIBLE = "NOT_CHECKOUT_ELIGIBLE"
internal const val ERROR_BACKEND_FAILED = "BACKEND_FAILED"

internal const val DEFAULT_SEARCH_LIMIT = 10
internal const val MAX_SEARCH_LIMIT = 50
internal const val MAX_QUANTITY = 99

/**
 * Seam behind the shop assistant intents. Production delegates catalog reads
 * to [ShopBackend] and cart/order reads to the local stores; tests install a
 * fake so provider logic (exact-variant verification, grouping, limits) is
 * asserted without network or live checkout (shop/AGENTS.md sanctions fake
 * transports, never live checkout).
 *
 * Prepare-only: this seam deliberately exposes no checkout call, no
 * idempotency key and no buyer-profile access.
 */
internal interface ShopAssistantBackend {
    suspend fun search(query: String, limit: Int): Result<List<UcpProduct>>
    suspend fun getProduct(shop: String, productId: String, variantId: String?): Result<UcpProduct>
    suspend fun cartLines(context: Context): List<CartLine>
    suspend fun saveCart(context: Context, lines: List<CartLine>)
    suspend fun orders(context: Context): List<PlacedOrder>
}

internal class RealShopAssistantBackend(
    private val backend: ShopBackend = ShopHttpBackend,
) : ShopAssistantBackend {
    override suspend fun search(query: String, limit: Int): Result<List<UcpProduct>> =
        backend.search(query, limit = limit)

    override suspend fun getProduct(
        shop: String,
        productId: String,
        variantId: String?,
    ): Result<UcpProduct> = backend.getProduct(shop, productId, variantId)

    override suspend fun cartLines(context: Context): List<CartLine> =
        ShopCartStore.getAll(context)

    override suspend fun saveCart(context: Context, lines: List<CartLine>) {
        ShopCartStore.save(context, lines)
    }

    override suspend fun orders(context: Context): List<PlacedOrder> =
        OrderStore.getAll(context)
}

/** Test seam, mirroring the `overrideForTest` singletons elsewhere. */
internal var shopAssistantBackendForTest: ShopAssistantBackend? = null

internal fun shopAssistantBackend(): ShopAssistantBackend =
    shopAssistantBackendForTest ?: RealShopAssistantBackend()

/**
 * Network calls must await the trust bundle (shop/AGENTS.md). Only the
 * production backend touches the network, so tests with a fake skip this —
 * [AppInit.start] is also what a NoDisplay intent entry point must call,
 * since MainActivity may never have run in this process.
 */
internal suspend fun awaitShopReady(context: Context) {
    if (shopAssistantBackendForTest != null) return
    AppInit.start(context)
    AppInit.awaitReady()
}

internal fun UcpProduct.toProductDataList(): List<ProductData> {
    val image = images.firstOrNull()?.url.orEmpty()
    if (variants.isEmpty()) {
        return listOf(
            ProductData(
                title = title, productId = id, variantId = "",
                shopDomain = shopDomain, storeName = storeName,
                price = priceRange.amount, currency = priceRange.currency,
                imageUrl = image, checkoutEligible = checkoutEligible,
            ),
        )
    }
    return variants.map { variant ->
        ProductData(
            title = title, productId = id, variantId = variant.id,
            shopDomain = variant.shopDomain.ifBlank { shopDomain },
            storeName = variant.storeName.ifBlank { storeName },
            price = variant.price.amount, currency = variant.price.currency,
            imageUrl = image, checkoutEligible = checkoutEligible,
        )
    }
}

internal suspend fun searchProducts(
    input: SearchProductsQuery,
    backend: ShopAssistantBackend,
): List<ProductData> {
    val query = input.query.trim()
    if (query.isEmpty()) return emptyList()
    val limit = if (input.limit <= 0) DEFAULT_SEARCH_LIMIT else input.limit.coerceAtMost(MAX_SEARCH_LIMIT)
    val products = backend.search(query, limit).getOrElse { return emptyList() }
    return products.flatMap { it.toProductDataList() }.take(limit)
}

internal suspend fun getProductDetail(
    input: GetProductQuery,
    backend: ShopAssistantBackend,
): ProductDetailData {
    val shop = input.shopDomain.trim()
    val productId = input.productId.trim()
    if (shop.isEmpty() || productId.isEmpty()) {
        return ProductDetailData(error = ERROR_INVALID_REQUEST)
    }
    val product = backend.getProduct(shop, productId, input.variantId?.takeIf { it.isNotBlank() })
        .getOrElse { return ProductDetailData(error = ERROR_BACKEND_FAILED) }
    val selected = input.variantId?.let { id -> product.variants.firstOrNull { it.id == id } }
    val price = selected?.price ?: product.priceRange
    return ProductDetailData(
        title = product.title,
        productId = product.id.ifBlank { productId },
        shopDomain = product.shopDomain.ifBlank { shop },
        storeName = product.storeName,
        description = product.description,
        price = price.amount,
        currency = price.currency,
        imageUrl = product.images.firstOrNull()?.url.orEmpty(),
        checkoutEligible = product.checkoutEligible,
        variants = product.variants.map { variant ->
            ProductVariantData(
                variantId = variant.id, title = variant.title,
                price = variant.price.amount, currency = variant.price.currency,
                available = variant.available, sku = variant.sku,
            )
        },
    )
}

internal fun List<CartLine>.toCartData(error: String? = null): CartData {
    val stores = groupBy { it.shopDomain }.map { (domain, lines) ->
        CartStoreData(
            shopDomain = domain,
            storeName = lines.firstOrNull()?.displayName.orEmpty(),
            lines = lines.map { line ->
                CartLineData(
                    title = line.productTitle, variantTitle = line.variantTitle,
                    variantId = line.variantId, shopDomain = line.shopDomain,
                    storeName = line.displayName,
                    price = line.unitPrice.amount, currency = line.unitPrice.currency,
                    quantity = line.quantity,
                )
            },
        )
    }
    return CartData(stores = stores, totalItems = sumOf { it.quantity }, error = error)
}

internal suspend fun getCart(context: Context, backend: ShopAssistantBackend): CartData =
    backend.cartLines(context).toCartData()

internal suspend fun addToCart(
    context: Context,
    input: AddToCartRequest,
    backend: ShopAssistantBackend,
): CartData {
    val current = backend.cartLines(context)
    val shop = input.shopDomain.trim()
    val variantId = input.variantId.trim()
    if (shop.isEmpty() || variantId.isEmpty() || input.quantity !in 1..MAX_QUANTITY) {
        return current.toCartData(
            if (shop.isEmpty() || variantId.isEmpty()) ERROR_INVALID_REQUEST else ERROR_INVALID_QUANTITY,
        )
    }
    // Exact-variant verification before any cart write (shop/AGENTS.md):
    // re-fetch the product and confirm this variant exists in the live
    // response. As in ProductScreen, the variant id doubles as the product
    // id when no separate product id is carried by the request. Cached
    // snapshots are not verification, and a failed/uncertain read is never
    // retried automatically — the cart is simply left unchanged.
    val product = backend.getProduct(shop, variantId, variantId)
        .getOrElse { return current.toCartData(ERROR_BACKEND_FAILED) }
    val variant = product.variants.firstOrNull { it.id == variantId }
        ?: return current.toCartData(ERROR_VARIANT_NOT_FOUND)
    if (!variant.available) return current.toCartData(ERROR_VARIANT_UNAVAILABLE)
    if (!product.checkoutEligible) return current.toCartData(ERROR_NOT_CHECKOUT_ELIGIBLE)
    val lineShop = variant.shopDomain.ifBlank { product.shopDomain }.ifBlank { shop }
    val existing = current.indexOfFirst {
        it.variantId == variantId && it.platformId == product.platformId && it.shopDomain == lineShop
    }
    val next = if (existing >= 0) {
        current.toMutableList().also {
            it[existing] = it[existing].copy(
                quantity = (it[existing].quantity + input.quantity).coerceAtMost(MAX_QUANTITY),
            )
        }
    } else {
        current + CartLine(
            variantId = variant.id,
            productTitle = product.title,
            variantTitle = variant.title,
            unitPrice = variant.price,
            platformId = product.platformId,
            shopDomain = lineShop,
            quantity = input.quantity,
            storeName = variant.storeName.ifBlank { product.storeName },
        )
    }
    backend.saveCart(context, next)
    return next.toCartData()
}

internal suspend fun removeFromCart(
    context: Context,
    input: RemoveFromCartRequest,
    backend: ShopAssistantBackend,
): CartData {
    val current = backend.cartLines(context)
    val variantId = input.variantId.trim()
    if (variantId.isEmpty()) return current.toCartData(ERROR_INVALID_REQUEST)
    val next = current.filterNot { it.variantId == variantId }
    if (next.size != current.size) backend.saveCart(context, next)
    return next.toCartData()
}

internal suspend fun getOrders(
    context: Context,
    backend: ShopAssistantBackend,
): List<OrderData> = backend.orders(context).map { order ->
    OrderData(
        orderId = order.orderId,
        checkoutId = order.checkoutId,
        shopDomain = order.shopDomain,
        storeName = order.displayName,
        status = order.tracking?.status?.takeIf { it.isNotBlank() } ?: order.status,
        total = order.total.amount,
        currency = order.total.currency,
        itemCount = order.itemCount,
        placedAtMs = order.placedAtMs,
    )
}
