package com.vayunmathur.shop.intents

import android.content.Context
import com.vayunmathur.library.intents.shop.AddToCartRequest
import com.vayunmathur.library.intents.shop.CartData
import com.vayunmathur.library.intents.shop.GetProductQuery
import com.vayunmathur.library.intents.shop.RemoveFromCartRequest
import com.vayunmathur.library.intents.shop.SearchProductsQuery
import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.ProductImage
import com.vayunmathur.shop.data.ProductVariant
import com.vayunmathur.shop.data.UcpProduct
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shop assistant provider intents against a fake [ShopAssistantBackend]
 * (fake transport sanctioned by shop/AGENTS.md; never live checkout).
 * Asserts exact-variant re-verification before cart writes, integer
 * quantities, grouping, and that no intent can create a checkout — the
 * seam exposes no checkout/profile call at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShopAssistantIntentsTest {

    private class FakeBackend(
        var searchResult: Result<List<UcpProduct>> = Result.success(emptyList()),
        var productResult: Result<UcpProduct> = Result.success(UcpProduct()),
        var cart: List<CartLine> = emptyList(),
        var orders: List<PlacedOrder> = emptyList(),
    ) : ShopAssistantBackend {
        var searchCalls = 0
        var getProductCalls = 0
        var saveCalls = 0
        var lastGetProduct: Triple<String, String, String?>? = null

        override suspend fun search(query: String, limit: Int): Result<List<UcpProduct>> {
            searchCalls++
            return searchResult
        }

        override suspend fun getProduct(
            shop: String,
            productId: String,
            variantId: String?,
        ): Result<UcpProduct> {
            getProductCalls++
            lastGetProduct = Triple(shop, productId, variantId)
            return productResult
        }

        override suspend fun cartLines(context: Context): List<CartLine> = cart

        override suspend fun saveCart(context: Context, lines: List<CartLine>) {
            saveCalls++
            cart = lines
        }

        override suspend fun orders(context: Context): List<PlacedOrder> = orders
    }

    private fun product(
        id: String = "p1",
        shop: String = "shop.example.com",
        eligible: Boolean = true,
        variants: List<ProductVariant> = listOf(
            ProductVariant(
                id = "v1", title = "9 / Blue", price = Money(12_999, "USD"),
                available = true, sku = "SKU-1", storeName = "Trail Store",
                shopDomain = shop,
            ),
            ProductVariant(
                id = "v2", title = "10 / Blue", price = Money(13_999, "USD"),
                available = false, storeName = "Trail Store", shopDomain = shop,
            ),
        ),
    ) = UcpProduct(
        id = id, title = "Trail Runner", description = "Grippy",
        priceRange = Money(12_999, "USD"), platformId = "shopify",
        shopDomain = shop, images = listOf(ProductImage("https://img.example/x.png")),
        variants = variants, checkoutEligible = eligible, storeName = "Trail Store",
    )

    private fun line(
        variantId: String = "v1",
        shop: String = "shop.example.com",
        store: String = "Trail Store",
        qty: Int = 2,
    ) = CartLine(
        variantId = variantId, productTitle = "Trail Runner", variantTitle = "9 / Blue",
        unitPrice = Money(12_999, "USD"), platformId = "shopify", shopDomain = shop,
        quantity = qty, storeName = store,
    )

    private var fake = FakeBackend()

    @BeforeTest
    fun setUp() {
        fake = FakeBackend()
        shopAssistantBackendForTest = fake
    }

    @AfterTest
    fun tearDown() {
        shopAssistantBackendForTest = null
    }

    // --- SearchProductsIntent ---

    @Test
    fun searchExpandsVariantsAndCapsLimit() = runBlocking {
        fake.searchResult = Result.success(listOf(product()))
        val out = SearchProductsIntent().performCalculation(SearchProductsQuery(query = "runner"))
        assertEquals(listOf("v1", "v2"), out.map { it.variantId })
        assertEquals("p1", out[0].productId)
        assertEquals(12_999L, out[0].price)
        assertEquals("Trail Store", out[0].storeName)
        assertTrue(out[0].checkoutEligible)
        assertEquals(1, SearchProductsIntent().performCalculation(
            SearchProductsQuery(query = "runner", limit = 1),
        ).size)
        assertTrue(SearchProductsIntent().performCalculation(SearchProductsQuery(query = " ")).isEmpty())
        // Blank query is refused provider-side, without a backend call.
        assertEquals(2, fake.searchCalls)
    }

    @Test
    fun searchFailureReturnsEmpty() = runBlocking {
        fake.searchResult = Result.failure(RuntimeException("boom"))
        assertTrue(SearchProductsIntent().performCalculation(SearchProductsQuery(query = "x")).isEmpty())
    }

    // --- GetProductIntent ---

    @Test
    fun getProductReturnsDetailAndSelectedVariantPrice() = runBlocking {
        fake.productResult = Result.success(product())
        val out = GetProductIntent().performCalculation(
            GetProductQuery(shopDomain = "shop.example.com", productId = "p1", variantId = "v2"),
        )
        assertNull(out.error)
        assertEquals(13_999L, out.price)
        assertEquals(listOf("v1", "v2"), out.variants.map { it.variantId })
        assertEquals("SKU-1", out.variants[0].sku)
    }

    @Test
    fun getProductInvalidAndFailureAreTyped() = runBlocking {
        assertEquals(
            ERROR_INVALID_REQUEST,
            GetProductIntent().performCalculation(GetProductQuery()).error,
        )
        fake.productResult = Result.failure(RuntimeException("boom"))
        assertEquals(
            ERROR_BACKEND_FAILED,
            GetProductIntent().performCalculation(
                GetProductQuery(shopDomain = "shop.example.com", productId = "p1"),
            ).error,
        )
    }

    // --- GetCartIntent ---

    @Test
    fun getCartGroupsLinesByStore() = runBlocking {
        fake.cart = listOf(
            line(),
            line(variantId = "v9", shop = "other.example.com", store = "", qty = 1),
        )
        val out = GetCartIntent().performCalculation(Unit)
        assertNull(out.error)
        assertEquals(3, out.totalItems)
        assertEquals(listOf("shop.example.com", "other.example.com"), out.stores.map { it.shopDomain })
        assertEquals("Trail Store", out.stores[0].storeName)
        // Domain fallback when no store name is known (shop/AGENTS.md).
        assertEquals("other.example.com", out.stores[1].storeName)
        assertEquals(12_999L, out.stores[0].lines[0].price)
    }

    // --- AddToCartIntent ---

    @Test
    fun addToCartReFetchesExactVariantBeforeWriting() = runBlocking {
        fake.productResult = Result.success(product())
        val out = AddToCartIntent().performCalculation(
            AddToCartRequest(shopDomain = "shop.example.com", variantId = "v1", quantity = 2),
        )
        assertNull(out.error)
        assertEquals(1, fake.getProductCalls)
        assertEquals(
            Triple("shop.example.com", "v1", "v1"),
            fake.lastGetProduct,
        )
        assertEquals(1, fake.saveCalls)
        assertEquals(2, out.totalItems)
        assertEquals("v1", out.stores.single().lines.single().variantId)
        assertEquals("Trail Store", out.stores.single().storeName)
    }

    @Test
    fun addToCartMergesAndCapsAt99() = runBlocking {
        fake.cart = listOf(line(qty = 98))
        fake.productResult = Result.success(product())
        val out = AddToCartIntent().performCalculation(
            AddToCartRequest(shopDomain = "shop.example.com", variantId = "v1", quantity = 5),
        )
        assertNull(out.error)
        assertEquals(99, out.totalItems)
    }

    @Test
    fun addToCartRefusesUnverifiedUnavailableAndInvalidWithoutWriting() = runBlocking {
        fake.cart = listOf(line(qty = 1))
        // Invalid quantity / blank ids: no re-fetch, no write.
        assertEquals(
            ERROR_INVALID_QUANTITY,
            AddToCartIntent().performCalculation(
                AddToCartRequest(shopDomain = "shop.example.com", variantId = "v1", quantity = 0),
            ).error,
        )
        assertEquals(
            ERROR_INVALID_QUANTITY,
            AddToCartIntent().performCalculation(
                AddToCartRequest(shopDomain = "shop.example.com", variantId = "v1", quantity = 100),
            ).error,
        )
        assertEquals(
            ERROR_INVALID_REQUEST,
            AddToCartIntent().performCalculation(AddToCartRequest()).error,
        )
        assertEquals(0, fake.getProductCalls)
        assertEquals(0, fake.saveCalls)
        // Exact variant absent from the live detail response.
        fake.productResult = Result.success(product(variants = listOf(
            ProductVariant(id = "other", price = Money(1, "USD"), available = true),
        )))
        assertEquals(
            ERROR_VARIANT_NOT_FOUND,
            AddToCartIntent().performCalculation(
                AddToCartRequest(shopDomain = "shop.example.com", variantId = "v1", quantity = 1),
            ).error,
        )
        // Present but unavailable.
        fake.productResult = Result.success(product())
        assertEquals(
            ERROR_VARIANT_UNAVAILABLE,
            AddToCartIntent().performCalculation(
                AddToCartRequest(shopDomain = "shop.example.com", variantId = "v2", quantity = 1),
            ).error,
        )
        // Not checkout-eligible.
        fake.productResult = Result.success(product(eligible = false))
        assertEquals(
            ERROR_NOT_CHECKOUT_ELIGIBLE,
            AddToCartIntent().performCalculation(
                AddToCartRequest(shopDomain = "shop.example.com", variantId = "v1", quantity = 1),
            ).error,
        )
        // Backend failure is not retried and leaves the cart untouched.
        fake.productResult = Result.failure(RuntimeException("boom"))
        val failed = AddToCartIntent().performCalculation(
            AddToCartRequest(shopDomain = "shop.example.com", variantId = "v1", quantity = 1),
        )
        assertEquals(ERROR_BACKEND_FAILED, failed.error)
        assertEquals(1, failed.totalItems)
        assertEquals(0, fake.saveCalls)
    }

    // --- RemoveFromCartIntent ---

    @Test
    fun removeFromCartRemovesOnlyThatVariant() = runBlocking {
        fake.cart = listOf(line(), line(variantId = "v9"))
        val out = RemoveFromCartIntent().performCalculation(RemoveFromCartRequest(variantId = "v1"))
        assertNull(out.error)
        assertEquals(listOf("v9"), out.stores.single().lines.map { it.variantId })
        assertEquals(1, fake.saveCalls)
        assertEquals(
            ERROR_INVALID_REQUEST,
            RemoveFromCartIntent().performCalculation(RemoveFromCartRequest()).error,
        )
    }

    // --- GetOrdersIntent ---

    @Test
    fun getOrdersSummarisesLocalRecordsWithoutClaimingPayment() = runBlocking {
        fake.orders = listOf(
            PlacedOrder(
                checkoutId = "c1", placedAtMs = 7L, total = Money(2_000, "USD"),
                itemCount = 2, status = "pending", shopDomain = "shop.example.com",
                storeName = "Trail Store", orderId = "",
            ),
            PlacedOrder(
                checkoutId = "c2", placedAtMs = 9L, total = Money(3_000, "USD"),
                itemCount = 1, status = "completed", shopDomain = "shop.example.com",
                orderId = "o2", tracking = OrderTracking(orderId = "o2", status = "in_transit"),
            ),
        )
        val out = GetOrdersIntent().performCalculation(Unit)
        // A pending browser handoff stays "pending"; tracking wins when known.
        assertEquals(listOf("pending", "in_transit"), out.map { it.status })
        assertEquals("Trail Store", out[0].storeName)
        assertEquals(2_000L, out[0].total)
        assertEquals("o2", out[1].orderId)
    }

    // --- DTO wire compat ---

    @Test
    fun shopDtosRoundTripAndDecodeWithDefaults() {
        val json = Json { ignoreUnknownKeys = true }
        val cart = CartData(totalItems = 2)
        assertEquals(
            cart,
            json.decodeFromString(CartData.serializer(), json.encodeToString(CartData.serializer(), cart)),
        )
        assertEquals(
            SearchProductsQuery(),
            json.decodeFromString(SearchProductsQuery.serializer(), "{}"),
        )
        assertEquals(
            AddToCartRequest(variantId = "v1"),
            json.decodeFromString(AddToCartRequest.serializer(), """{"variantId":"v1"}"""),
        )
    }
}
