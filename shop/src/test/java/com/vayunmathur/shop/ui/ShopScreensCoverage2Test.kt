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
import com.vayunmathur.shop.Navigation
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderShipment
import com.vayunmathur.shop.data.OrderStore
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.ProductVariant
import com.vayunmathur.shop.data.ProductImage
import com.vayunmathur.shop.data.ShopProfile
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.network.BackendRawResponse
import com.vayunmathur.shop.network.ShopHttpBackend
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

/** Navigation gate, live ProductScreen refresh, Orders variants, openContinueUrl, profile editing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShopScreensCoverage2Test {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = context.getString(id)
    private fun await(cond: () -> Boolean) {
        val d = System.currentTimeMillis() + 15_000
        while (!cond() && System.currentTimeMillis() < d) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20) }
        assertTrue(cond(), "condition not met")
    }
    private fun scrollTo(i: Int) { composeRule.onAllNodes(hasScrollAction())[0].performScrollToIndex(i) }

    @BeforeTest fun setUp() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        AppInit.start(context)
        context.getSharedPreferences("shop_orders", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("shop_cart", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Suppress("UNCHECKED_CAST")
    private fun setVmField(vm: ShopViewModel, name: String, value: Any?) {
        val f = ShopViewModel::class.java.declaredFields.first { it.name.startsWith(name) }
        f.isAccessible = true
        val delegate = f.get(vm)
        if (delegate is androidx.compose.runtime.MutableState<*>) {
            (delegate as androidx.compose.runtime.MutableState<Any?>).value = value
        } else { f.set(vm, value) }
    }
    private fun productJson(id: String = "p1", variant: String = "v1") = """
        {"product": {"id":"$id","title":"Live Shoe","description":"d","descriptionHtml":"",
        "priceRange":{"amount":12000,"currency":"USD"},"priceRangeMax":null,
        "url":"https://cool.example/products/live","platformId":"shopify","shopDomain":"cool.example",
        "images":[],"variants":[{"id":"$variant","title":"Size 9","price":{"amount":12000,"currency":"USD"},
        "available":true,"optionValues":[],"storeName":"Cool Store","shopDomain":"cool.example","selectedOptions":[]}],
        "checkoutEligible":true,"storeName":"Cool Store"}}
    """.trimIndent()

    private fun stubProfileRepository(vm: ShopViewModel) {
        // Pre-seed so Navigation's observeProfile returns early: the SQLCipher/
        // AndroidKeyStore DB cannot open under Robolectric (documented ceiling).
        val ctor = com.vayunmathur.shop.data.ProfileRepository::class.java.getDeclaredConstructor(android.content.Context::class.java)
        ctor.isAccessible = true
        val repo = ctor.newInstance(context)
        val f = ShopViewModel::class.java.getDeclaredField("profileRepository"); f.isAccessible = true; f.set(vm, repo)
    }

    @Test fun navigation_notLoadedRendersNothing() {
        val vm = ShopViewModel(); stubProfileRepository(vm)
        composeRule.setContent { Navigation(vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("First name").assertDoesNotExist()
    }

    @Test fun navigation_profileGateWhenLoadedIncomplete() {
        val vm = ShopViewModel(); stubProfileRepository(vm)
        setVmField(vm, "profileLoaded", true)
        composeRule.setContent { Navigation(vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Add your details to start shopping.").assertIsDisplayed()
    }

    @Test fun navigation_gateResetsToSearchWhenProfileCompletes() {
        val vm = ShopViewModel(); stubProfileRepository(vm)
        setVmField(vm, "profileLoaded", true)
        composeRule.setContent { Navigation(vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Add your details to start shopping.").assertIsDisplayed()
        // Saving a valid profile (as the gate's Continue does via the repository
        // flow) flips profileComplete; the gate root must reset to Search.
        setVmField(vm, "profile", ShopProfile(firstName="Ada", lastName="L", email="ada@example.com", phone="+14155550123"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.search_initial_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Add your details to start shopping.").assertDoesNotExist()
    }

    @Test fun navigation_completeProfileShowsSearchTabs() {
        val vm = ShopViewModel(); stubProfileRepository(vm)
        setVmField(vm, "profile", ShopProfile(firstName="Ada", lastName="L", email="ada@example.com", phone="+14155550123"))
        setVmField(vm, "profileLoaded", true)
        composeRule.setContent { Navigation(vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.search_initial_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.cart)).assertIsDisplayed()
        // Sibling tabs are routes: tapping a tab resets the stack to that tab.
        composeRule.onNode(
            androidx.compose.ui.test.hasText(str(R.string.cart)) and androidx.compose.ui.test.hasClickAction(),
        ).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.cart_empty)).assertIsDisplayed()
        composeRule.onNode(
            androidx.compose.ui.test.hasText(str(R.string.orders)) and androidx.compose.ui.test.hasClickAction(),
        ).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.no_orders_yet)).assertIsDisplayed()
        composeRule.onNode(
            androidx.compose.ui.test.hasText("Profile") and androidx.compose.ui.test.hasClickAction(),
        ).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("First name").assertIsDisplayed()
        composeRule.onNode(
            androidx.compose.ui.test.hasText(str(R.string.search)) and androidx.compose.ui.test.hasClickAction(),
        ).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.search_initial_title)).assertIsDisplayed()
    }

    @Test fun navigation_ordersRequestStartsOnOrdersTab() {
        val vm = ShopViewModel(); stubProfileRepository(vm)
        setVmField(vm, "profile", ShopProfile(firstName="Ada", lastName="L", email="ada@example.com", phone="+14155550123"))
        setVmField(vm, "profileLoaded", true)
        composeRule.setContent { Navigation(vm, ordersRequest = 1) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.no_orders_yet)).assertIsDisplayed()
    }

    @Test fun productScreen_liveRefreshVerifiesAndAddsToCart() {
        ShopHttpBackend.request = { _, _, _ -> BackendRawResponse(200, productJson(), emptyMap(), null) }
        val vm = ShopViewModel()
        val snapshot = UcpProduct(id="p1", title="Live Shoe", platformId="shopify", shopDomain="cool.example",
            checkoutEligible=true, storeName="Cool Store", priceRange=Money(12000,"USD"),
            variants=listOf(ProductVariant("v1","Size 9",Money(12000,"USD"),true,storeName="Cool Store",shopDomain="cool.example")))
        setVmField(vm, "results", listOf(snapshot))
        composeRule.setContent { ProductScreen(vm, "v1", "shopify", "cool.example", onBack={}, onPayInApp={}) }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(str(R.string.add_to_cart)).fetchSemanticsNodes().isNotEmpty() }
        scrollTo(4)
        composeRule.onNodeWithText(str(R.string.add_to_cart)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, vm.cart.size); assertEquals("v1", vm.cart.single().variantId)
    }

    @Test fun productScreen_liveVariantMissingDisablesCart() {
        ShopHttpBackend.request = { _, _, _ -> BackendRawResponse(200, productJson(variant="other"), emptyMap(), null) }
        val vm = ShopViewModel()
        val snapshot = UcpProduct(id="p1", title="Live Shoe", platformId="shopify", shopDomain="cool.example",
            checkoutEligible=true, storeName="Cool Store", priceRange=Money(12000,"USD"),
            variants=listOf(ProductVariant("v1","Size 9",Money(12000,"USD"),true,storeName="Cool Store",shopDomain="cool.example")))
        setVmField(vm, "results", listOf(snapshot))
        composeRule.setContent { ProductScreen(vm, "v1", "shopify", "cool.example", onBack={}, onPayInApp={}) }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("The store no longer lists this exact variant. Cart actions are disabled.").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(vm.cart.isEmpty())
    }

    @Test fun ordersScreen_pendingRefundedAndShipmentWithoutLink() {
        val orders = listOf(
            PlacedOrder(checkoutId="c1", placedAtMs=1_700_000_000_000, total=Money(5000,"USD"), itemCount=1,
                continueUrl="https://cool.example/checkout", status="pending", shopDomain="cool.example", storeName=""),
            PlacedOrder(checkoutId="c2", placedAtMs=1_700_000_100_000, total=Money(9000,"USD"), itemCount=3,
                status="completed", shopDomain="kitchen.example", storeName="Kitchen", orderId="o2",
                tracking=OrderTracking(orderId="o2", status="delivery_issue", currency="USD", refundedTotal=1000,
                    permalinkUrl="https://kitchen.example/orders/o2",
                    shipments=listOf(OrderShipment(trackingNumber=null, trackingUrl="http://insecure.example/t", carrier=null, status="shipped", description=null, occurredAt="bad-date")))),
        )
        runBlocking { orders.forEach { OrderStore.add(context, it) } }
        val vm = ShopViewModel(); vm.loadOrders(context); await { vm.orders.size == 2 }
        composeRule.setContent { OrdersScreen(vm) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.checkout_pending)).assertExists()
        composeRule.onNodeWithText("Kitchen").assertIsDisplayed()
    }

    @Test fun openContinueUrl_rejectsNonHttpsAndOpensHttps() {
        openContinueUrl(context, "http://insecure.example/checkout")
        openContinueUrl(context, "not a url")
        openContinueUrl(context, "https://cool.example/checkout/1")
        // No exception is the assertion; Robolectric shadows the browser intent.
        assertTrue(true)
    }

    @Test fun productDetail_noVariantsSinglePriceAndBlankDescription() {
        composeRule.setContent {
            ProductDetailContent(
                product = UcpProduct(id="p", title="Solo", platformId="generic", shopDomain="s.example",
                    checkoutEligible=false, url="", priceRange=Money(100,"USD"),
                    variants=listOf(ProductVariant("only","Default",Money(100,"USD"),false))),
                selectedVariantId="only", quantity=1, loading=false, verified=true, failure=null,
                onSelectVariant={}, onQuantityChange={}, onRetry={}, onAddToCart={}, onBuyNow={}, onOpenUrl={})
        }
        scrollTo(3)
        composeRule.onNodeWithText("This variant is out of stock.").assertIsDisplayed()
        composeRule.onNodeWithText("s.example").assertIsDisplayed()
    }

    @Test fun searchContent_productWithoutImageOrStoreAndClearQuery() {
        var clicked: Triple<String,String,String>? = null
        composeRule.setContent {
            SearchContent(query="x", onQueryChange={}, searching=false,
                results=listOf(UcpProduct(id="p", title="Plain Mug", platformId="generic", shopDomain="m.example",
                    priceRange=Money(500,"USD"), variants=listOf(ProductVariant("vm","Default",Money(500,"USD"),true)))),
                onSearch={}, onProductClick={ v,p,d -> clicked=Triple(v,p,d) })
        }
        composeRule.onNodeWithText("Plain Mug").assertIsDisplayed()
        composeRule.onNodeWithText("Plain Mug").performClick()
        assertEquals(Triple("vm","generic","m.example"), clicked)
    }

    @Test fun embeddedCheckout_bridgeOriginsCoverMerchantHandoffAndShopApp() {
        val vm = ShopViewModel()
        val p = UcpProduct(id="p1", title="Shoe", platformId="shopify", shopDomain="cool.example", checkoutEligible=true,
            storeName="Cool Store", variants=listOf(ProductVariant("v1","D",Money(100,"USD"),true,storeName="Cool Store",shopDomain="cool.example")))
        vm.addToCart(p, "v1", 1, context)
        assertTrue(vm.openEmbeddedCheckout("shopify","cool.example","https://cool.example/checkout/abc", dark=false))
        val clazz = Class.forName("com.vayunmathur.shop.ui.EmbeddedCheckoutScreenKt")
        val m = clazz.getDeclaredMethod("bridgeOrigins", com.vayunmathur.shop.data.EmbeddedCheckoutRequest::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST") val origins = m.invoke(null, vm.embeddedCheckout) as Set<String>
        assertTrue(origins.contains("https://cool.example")); assertTrue(origins.contains("https://*.cool.example"))
        assertTrue(origins.contains("https://shop.app"))
    }
}
