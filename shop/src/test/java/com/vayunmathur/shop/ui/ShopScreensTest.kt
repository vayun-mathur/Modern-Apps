package com.vayunmathur.shop.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderShipment
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.ProductVariant
import com.vayunmathur.shop.data.OrderStore
import com.vayunmathur.shop.data.ProductImage
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.platform.AppInit
import com.vayunmathur.shop.platform.ShopViewModel
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Search, product detail, orders, profile and tabs with fixture data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShopScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun str(id: Int): String = context.getString(id)

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        AppInit.start(context)
        context.getSharedPreferences("shop_orders", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("shop_cart", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(condition(), "condition not met")
    }

    private fun scrollListTo(index: Int) { composeRule.onAllNodes(hasScrollAction())[0].performScrollToIndex(index) }

    private fun product(
        title: String = "Trail Shoes", eligible: Boolean = true, available: Boolean = true,
    ) = UcpProduct(
        id = "p-$title", title = title, description = "Light trail shoe", descriptionHtml = "<p>Light <b>trail</b> shoe</p>",
        url = "https://cool.example/products/trail", platformId = "shopify", shopDomain = "cool.example",
        checkoutEligible = eligible, storeName = "Cool Store", priceRange = Money(12_000, "USD"),
        priceRangeMax = Money(15_000, "USD"),
        images = listOf(ProductImage("https://cdn.shopify.com/shoe.png", "shoe")),
        variants = listOf(
            ProductVariant("v1", "Size 9", Money(12_000, "USD"), available, storeName = "Cool Store", shopDomain = "cool.example"),
            ProductVariant("v2", "Size 10", Money(15_000, "USD"), false, storeName = "Cool Store", shopDomain = "cool.example"),
        ),
    )

    @Test
    fun searchContent_emptyShowsPromptAndLinkCardActions() {
        var settings = 0
        var dismissed = 0
        composeRule.setContent {
            SearchContent(
                query = "", onQueryChange = {}, searching = false, results = emptyList(),
                onSearch = {}, onProductClick = { _, _, _ -> },
                linkPrompt = LinkPrompt(onOpenSettings = { settings++ }, onDismiss = { dismissed++ }),
            )
        }
        composeRule.onNodeWithText(str(R.string.no_products_found)).assertIsDisplayed()
        composeRule.onNodeWithText("Open store links in Shop").assertIsDisplayed()
        composeRule.onNodeWithText("Open settings").performClick()
        composeRule.onNodeWithText("Not now").performClick()
        assertEquals(1, settings)
        assertEquals(1, dismissed)
    }

    @Test
    fun searchContent_resultsRenderAndClickOpensFirstVariant() {
        var clicked: Triple<String, String, String>? = null
        var cleared = 0
        composeRule.setContent {
            SearchContent(
                query = "shoe", onQueryChange = {}, searching = false,
                results = listOf(product(), product("Mug")),
                onSearch = {}, onProductClick = { v, p, d -> clicked = Triple(v, p, d) },
                scopeName = "Cool Store", onClearScope = { cleared++ },
            )
        }
        composeRule.onNodeWithText("Trail Shoes").assertIsDisplayed()
        composeRule.onNodeWithText("Cool Store").assertIsDisplayed()
        composeRule.onNodeWithText("Results from Cool Store").assertIsDisplayed()
        composeRule.onNodeWithText("Trail Shoes").performClick()
        assertEquals(Triple("v1", "shopify", "cool.example"), clicked)
        composeRule.onNodeWithText("Results from Cool Store").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun searchContent_searchingShowsNoEmptyPrompt() {
        composeRule.setContent {
            SearchContent(
                query = "shoe", onQueryChange = {}, searching = true, results = emptyList(),
                onSearch = {}, onProductClick = { _, _, _ -> },
            )
        }
        composeRule.onNodeWithText(str(R.string.no_products_found)).assertDoesNotExist()
    }

    @Test
    fun productDetail_verifiedSelectsVariantAndAdds() {
        var selected = "v1"
        var added = 0
        var bought = 0
        var qty = 1
        composeRule.setContent {
            ProductDetailContent(
                product = product(), selectedVariantId = selected, quantity = qty,
                loading = false, verified = true, failure = null,
                onSelectVariant = { selected = it }, onQuantityChange = { qty = it },
                onRetry = {}, onAddToCart = { added++ }, onBuyNow = { bought++ }, onOpenUrl = {},
            )
        }
        composeRule.onNodeWithText("Trail Shoes").assertDoesNotExist() // title is in the host scaffold, not content
        composeRule.onNodeWithText("Cool Store").assertIsDisplayed()
        scrollListTo(5)
        composeRule.onAllNodesWithText("$120.00")[0].assertIsDisplayed()
        composeRule.onNodeWithText("Size 10").assertIsDisplayed()
        composeRule.onNodeWithText("Out of stock").assertExists()
        composeRule.onNodeWithText(str(R.string.add_to_cart)).performClick()
        composeRule.onNodeWithText(str(R.string.buy_now)).performClick()
        assertEquals(1, added)
        assertEquals(1, bought)
    }

    @Test
    fun productDetail_unverifiedShowsFailureRetryAndMerchantFallback() {
        var retries = 0
        var opened: String? = null
        composeRule.setContent {
            ProductDetailContent(
                product = product(eligible = false), selectedVariantId = "v1", quantity = 1,
                loading = false, verified = false, failure = "Store unreachable",
                onSelectVariant = {}, onQuantityChange = {}, onRetry = { retries++ },
                onAddToCart = {}, onBuyNow = {}, onOpenUrl = { opened = it },
            )
        }
        scrollListTo(5)
        composeRule.onNodeWithText("Store unreachable").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        composeRule.onNodeWithText("View on merchant site").performClick()
        assertEquals("https://cool.example/products/trail", opened)
    }

    @Test
    fun productDetail_loadingShowsCheckingText() {
        composeRule.setContent {
            ProductDetailContent(
                product = product(), selectedVariantId = "v1", quantity = 1,
                loading = true, verified = false, failure = null,
                onSelectVariant = {}, onQuantityChange = {}, onRetry = {},
                onAddToCart = {}, onBuyNow = {}, onOpenUrl = {},
            )
        }
        scrollListTo(5)
        composeRule.onNodeWithText("Checking live price and availability...").assertIsDisplayed()
    }

    @Test
    fun ordersScreen_showsTrackedOrderAndOpensOrderPage() {
        val order = PlacedOrder(
            checkoutId = "chk1", placedAtMs = 1_700_000_000_000, total = Money(12_000, "USD"),
            itemCount = 2, continueUrl = "https://cool.example/orders/1", status = "completed",
            platformId = "shopify", shopDomain = "cool.example", storeName = "Cool Store", orderId = "ord1",
            tracking = OrderTracking(
                orderId = "ord1", status = "shipped", currency = "USD",
                shipments = listOf(
                    OrderShipment(
                        trackingNumber = "1Z999", trackingUrl = "https://track.example/1Z999",
                        carrier = "UPS", status = "in_transit", description = "Left the depot",
                        occurredAt = "2023-11-14T00:00:00Z",
                    ),
                ),
            ),
        )
        runBlocking { OrderStore.add(context, order) }
        val vm = ShopViewModel()
        vm.loadOrders(context)
        await { vm.orders.size == 1 }
        composeRule.setContent { OrdersScreen(vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cool Store").assertIsDisplayed()
        composeRule.onNodeWithText("2 item(s) · $120.00").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.view_order)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.track_package)).assertIsDisplayed()
    }

    @Test
    fun ordersScreen_emptyShowsPrompt() {
        val vm = ShopViewModel()
        composeRule.setContent { OrdersScreen(vm) }
        composeRule.onNodeWithText(str(R.string.no_orders_yet)).assertIsDisplayed()
    }

    @Test
    fun profileScreen_requiredGateShowsFieldsAndContinue() {
        val vm = ShopViewModel()
        composeRule.setContent { ProfileScreen(vm, required = true) }
        composeRule.onNodeWithText("Add your details to start shopping.").assertIsDisplayed()
        composeRule.onNodeWithText("First name").assertIsDisplayed()
        composeRule.onNodeWithText("Email").assertIsDisplayed()
        scrollListTo(3)
        composeRule.onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test
    fun profileScreen_editModeShowsSave() {
        val vm = ShopViewModel()
        composeRule.setContent { ProfileScreen(vm, required = false) }
        scrollListTo(2)
        composeRule.onNodeWithText("Delivery address (optional)").assertIsDisplayed()
        scrollListTo(3)
        composeRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun embeddedCheckout_withoutRequestClosesImmediately() {
        var closed = 0
        val vm = ShopViewModel()
        composeRule.setContent { EmbeddedCheckoutScreen(vm, onClose = { closed++ }) }
        composeRule.waitForIdle()
        await { closed == 1 }
        assertEquals(1, closed)
    }

    @Test
    fun cartScreenWrapper_showsVmCartAndRemoveEmptiesIt() {
        val vm = ShopViewModel()
        vm.addToCart(product(), "v1", 2, context)
        composeRule.setContent { CartScreen(vm, onPayInApp = {}) }
        composeRule.onNodeWithText("Trail Shoes").assertIsDisplayed()
        composeRule.onNodeWithText("Remove").performClick()
        assertTrue(vm.cart.isEmpty())
        composeRule.onNodeWithText(str(R.string.cart_empty)).assertIsDisplayed()
    }

    @Test
    fun productScreen_blankDomainShowsMissingDomainFailureWithoutNetwork() {
        val vm = ShopViewModel()
        composeRule.setContent {
            ProductScreen(vm, variantId = "v1", platformId = "shopify", shopDomain = "", onBack = {}, onPayInApp = {})
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("The publishing store domain is missing. Cart actions are disabled.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

}

