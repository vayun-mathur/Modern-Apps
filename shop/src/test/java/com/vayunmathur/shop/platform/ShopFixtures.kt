package com.vayunmathur.shop.platform

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CatalogOptionSelection
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.MerchantListing
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderStatusQuery
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.ProductImage
import com.vayunmathur.shop.data.ProductVariant
import com.vayunmathur.shop.data.ProfileStore
import com.vayunmathur.shop.data.ShopProfile
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.network.ShopBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

fun testVariant(
    id: String = "v1",
    title: String = "9 / Blue",
    price: Long = 12999,
    available: Boolean = true,
    shopDomain: String = "shop.example.com",
    storeName: String = "Trail Store",
) = ProductVariant(
    id = id, title = title, price = Money(price, "USD"), available = available,
    optionValues = listOf("9", "Blue"), storeName = storeName, shopDomain = shopDomain,
    selectedOptions = listOf(CatalogOptionSelection("Size", "9", "size-9")),
)

fun testProduct(
    id: String = "p1",
    title: String = "Trail Runner",
    shopDomain: String = "shop.example.com",
    variants: List<ProductVariant> = listOf(testVariant(shopDomain = shopDomain)),
    checkoutEligible: Boolean = true,
    url: String = "https://shop.example.com/products/trail-runner",
    description: String = "Grippy shoes",
) = UcpProduct(
    id = id, title = title, description = description,
    priceRange = Money(12999, "USD"), priceRangeMax = Money(14999, "USD"),
    url = url, platformId = "shopify", shopDomain = shopDomain,
    images = listOf(ProductImage("https://api.maproj.org/shop/v1/image?url=x", "Shoe")),
    variants = variants, checkoutEligible = checkoutEligible,
    storeName = "Trail Store",
)

fun testLine(
    variantId: String = "v1",
    title: String = "Trail Runner",
    price: Long = 12999,
    quantity: Int = 1,
    shopDomain: String = "shop.example.com",
    platformId: String = "shopify",
    storeName: String = "Trail Store",
) = CartLine(
    variantId = variantId, productTitle = title, variantTitle = "9 / Blue",
    unitPrice = Money(price, "USD"), platformId = platformId, shopDomain = shopDomain,
    quantity = quantity, storeName = storeName,
)

val completeProfile = ShopProfile(
    firstName = "Ada", lastName = "Buyer", email = "ada@example.com", phone = "+14155550123",
    street = "1 Main St", city = "Springfield", region = "IL", country = "US", postalCode = "62701",
)

class FakeProfileStore(initial: ShopProfile? = null) : ProfileStore {
    val flow = MutableStateFlow(initial)
    override val profile: Flow<ShopProfile?> get() = flow
    val saved = mutableListOf<ShopProfile>()
    override suspend fun save(profile: ShopProfile) {
        saved += profile
        flow.value = profile
    }
}

open class FakeShopBackend : ShopBackend {
    var searchResult: Result<List<UcpProduct>> = Result.success(emptyList())
    var productResult: Result<UcpProduct> = Result.success(testProduct())
    var handleResult: Result<UcpProduct> = Result.success(testProduct())
    var checkoutResult: Result<UcpCheckout> = Result.success(UcpCheckout(checkoutId = "c1", continueUrl = "https://shop.example.com/checkout/1"))
    var merchantsResult: Result<List<MerchantListing>> = Result.success(emptyList())
    var statusResult: Result<Map<String, OrderTracking>> = Result.success(emptyMap())
    var searchGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    val searchCalls = mutableListOf<Triple<String, String?, Int>>()
    val handleCalls = mutableListOf<Pair<String, String>>()
    var merchantCalls = 0
    var statusCalls = 0

    override suspend fun search(query: String, shop: String?, limit: Int, country: String): Result<List<UcpProduct>> {
        searchCalls += Triple(query, shop, limit)
        searchGate?.await()
        return searchResult
    }

    override suspend fun getProduct(
        shop: String, productId: String, variantId: String?, title: String?,
        selectedOptions: List<CatalogOptionSelection>,
    ): Result<UcpProduct> = productResult

    override suspend fun getProductByHandle(shop: String, handle: String): Result<UcpProduct> {
        handleCalls += shop to handle
        return handleResult
    }

    override suspend fun createCheckout(shop: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String): Result<UcpCheckout> = checkoutResult
    override suspend fun getCheckout(shop: String, sessionId: String): Result<UcpCheckout> = checkoutResult
    override suspend fun updateCheckout(shop: String, sessionId: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String): Result<UcpCheckout> = checkoutResult

    override suspend fun listMerchants(refresh: Boolean): Result<List<MerchantListing>> {
        merchantCalls++
        return merchantsResult
    }

    override suspend fun orderStatus(orders: List<OrderStatusQuery>): Result<Map<String, OrderTracking>> {
        statusCalls++
        return statusResult
    }
}
