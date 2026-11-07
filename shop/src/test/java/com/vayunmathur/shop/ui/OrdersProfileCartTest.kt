package com.vayunmathur.shop.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderShipment
import com.vayunmathur.shop.data.OrderStore
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.platform.AppInit
import com.vayunmathur.shop.platform.FakeProfileStore
import com.vayunmathur.shop.platform.FakeShopBackend
import com.vayunmathur.shop.platform.ShopViewModel
import com.vayunmathur.shop.platform.completeProfile
import com.vayunmathur.shop.platform.testLine
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp")
class OrdersProfileCartTest {

    @get:Rule
    val rule = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var backend: FakeShopBackend

    @BeforeTest
    fun setUp() {
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(ctx) }
        AppInit.start(ctx)
        runBlocking { AppInit.awaitReady() }
        ctx.getSharedPreferences("shop_cart", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("shop_orders", Context.MODE_PRIVATE).edit().clear().commit()
        backend = FakeShopBackend()
    }

    private fun awaitVm(vm: ShopViewModel, condition: (ShopViewModel) -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition(vm)) return
            Thread.sleep(20)
        }
        assertTrue(condition(vm), "view model condition not met; orders=${vm.orders.size}")
    }

    private fun orderVm(orders: List<PlacedOrder>): ShopViewModel {
        val vm = ShopViewModel(backend)
        runBlocking { orders.forEach { OrderStore.add(ctx, it) } }
        vm.loadOrders(ctx)
        awaitVm(vm) { it.orders.size == orders.size }
        return vm
    }

    // ---------- orders ----------

    @Test
    fun `empty orders say so`() {
        val vm = orderVm(emptyList())
        rule.setContent { DynamicTheme { OrdersScreen(vm) } }
        rule.onNodeWithText("No orders yet").assertIsDisplayed()
    }

    @Test
    fun `a completed order with tracking and shipments renders fully`() {
        val order = PlacedOrder(
            checkoutId = "c1", placedAtMs = 1700000000000L, total = Money(12999, "USD"), itemCount = 2,
            status = "completed", platformId = "shopify", shopDomain = "shop.example.com",
            storeName = "Trail Store", orderId = "o1", trackingToken = "a".repeat(32),
            tracking = OrderTracking(
                orderId = "o1", label = "#1042", permalinkUrl = "https://shop.example.com/orders/o1",
                status = "in_transit", refundedTotal = 500, currency = "USD",
                shipments = listOf(
                    OrderShipment(
                        trackingNumber = "1Z999", trackingUrl = "https://www.ups.com/track/1Z999",
                        carrier = "UPS", status = "in_transit", description = "Arrived at facility",
                        occurredAt = "2026-03-26T09:15:00-04:00",
                    ),
                ),
            ),
        )
        val vm = orderVm(listOf(order))
        rule.setContent { DynamicTheme { OrdersScreen(vm) } }
        rule.onNodeWithText("Trail Store").assertIsDisplayed()
        rule.onNodeWithText("#1042").assertIsDisplayed()
        rule.onNodeWithText("2 item(s) · $129.99").assertIsDisplayed()
        rule.onNodeWithText("In transit").assertIsDisplayed()
        rule.onNodeWithText("UPS · In transit", substring = true).assertIsDisplayed()
        rule.onNodeWithText("1Z999").assertIsDisplayed()
        rule.onNodeWithText("Track package").assertIsDisplayed()
        rule.onNodeWithText("View order").assertIsDisplayed()
    }

    @Test
    fun `a pending order without tracking offers the browser handoff`() {
        val order = PlacedOrder(
            checkoutId = "c1", placedAtMs = 1700000000000L, total = Money(500, "USD"), itemCount = 1,
            status = "pending", shopDomain = "shop.example.com", storeName = "Trail Store",
            continueUrl = "https://shop.example.com/checkout/1",
        )
        val vm = orderVm(listOf(order))
        rule.setContent { DynamicTheme { OrdersScreen(vm) } }
        rule.onNodeWithText("Payment not confirmed").assertIsDisplayed()
        rule.onNodeWithText("Continue in browser").assertIsDisplayed()
    }

    @Test
    fun `a delivery issue is shown with the shipped status strings`() {
        val order = PlacedOrder(
            checkoutId = "c1", placedAtMs = 1700000000000L, total = Money(500, "USD"), itemCount = 1,
            status = "completed", shopDomain = "shop.example.com", storeName = "Trail Store",
            tracking = OrderTracking(status = "delivery_issue"),
        )
        val vm = orderVm(listOf(order))
        rule.setContent { DynamicTheme { OrdersScreen(vm) } }
        rule.onNodeWithText("Delivery issue").assertIsDisplayed()
    }

    // ---------- profile ----------

    @Test
    fun `the required profile gate explains and saves a valid profile`() {
        val store = FakeProfileStore(null)
        val vm = ShopViewModel(backend)
        vm.profileStoreFactory = { store }
        vm.observeProfile(ctx)
        awaitVm(vm) { it.profileLoaded }
        rule.setContent { DynamicTheme { ProfileScreen(vm, required = true) } }
        rule.onNodeWithText("Add your details to start shopping.").assertIsDisplayed()
        rule.onNodeWithText("Continue").assertIsNotEnabled()

        rule.onNodeWithText("First name").performTextInput("Ada")
        rule.onNodeWithText("Last name").performTextInput("Buyer")
        rule.onNodeWithText("Email").performTextInput("ada@example.com")
        rule.onNodeWithText("Phone (international, e.g. +14155550123)").performTextInput("+14155550123")
        rule.onNodeWithText("Continue").assertIsEnabled()
        rule.onNodeWithText("Continue").performClick()
        awaitVm(vm) { store.saved.size == 1 }
        assertEquals("Ada", store.saved.single().firstName)
    }

    @Test
    fun `an invalid email keeps the optional profile unsavable and shows why`() {
        val store = FakeProfileStore(completeProfile)
        val vm = ShopViewModel(backend)
        vm.profileStoreFactory = { store }
        vm.observeProfile(ctx)
        awaitVm(vm) { it.profileComplete }
        rule.setContent { DynamicTheme { ProfileScreen(vm, required = false) } }
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Email").performTextReplacement("not-an-email")
        // The field now holds the appended text, which is invalid; the error must surface.
        rule.onAllNodesWithText("Enter a valid email address.", substring = true)[0].assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `the country field is uppercased while typing`() {
        val store = FakeProfileStore(null)
        val vm = ShopViewModel(backend)
        vm.profileStoreFactory = { store }
        vm.observeProfile(ctx)
        awaitVm(vm) { it.profileLoaded }
        rule.setContent { DynamicTheme { ProfileScreen(vm, required = false) } }
        rule.onNodeWithText("Country (two-letter code)").performTextInput("us")
        rule.onAllNodesWithText("US")[0].assertIsDisplayed()
    }

    // ---------- cart content (extra states) ----------

    @Test
    fun `cart groups by store with a checkout per store and merged totals`() {
        var checkedOut: Pair<String, String>? = null
        var removed = 0
        rule.setContent {
            DynamicTheme {
                CartContent(
                    cart = listOf(
                        testLine(variantId = "v1", title = "Shoes", price = 1000, quantity = 2, storeName = "Trail Store"),
                        testLine(variantId = "v2", title = "Hat", price = 500, quantity = 1, shopDomain = "other.example.com", storeName = "Hat Shop"),
                    ),
                    onRemove = { removed++ },
                    onCheckoutStore = { p, s -> checkedOut = p to s },
                )
            }
        }
        rule.onNodeWithText("Trail Store").assertIsDisplayed()
        rule.onNodeWithText("Hat Shop").assertIsDisplayed()
        rule.onNodeWithText("Checkout with Trail Store").assertIsDisplayed()
        rule.onNodeWithText("Checkout with Hat Shop").performClick()
        assertEquals("shopify" to "other.example.com", checkedOut)
        rule.onAllNodesWithText("Remove")[0].performClick()
        assertEquals(1, removed)
        rule.onNodeWithText("Estimated subtotal").assertIsDisplayed()
        rule.onNodeWithText("$25.00").assertIsDisplayed()
    }

    @Test
    fun `the starting store shows progress and disables other checkouts`() {
        rule.setContent {
            DynamicTheme {
                CartContent(
                    cart = listOf(testLine()),
                    startingStore = "shopify" to "shop.example.com",
                )
            }
        }
        // The busy store's button swaps its label for a progress indicator.
        rule.onNodeWithText("Checkout").assertDoesNotExist()
        rule.onNodeWithText("Estimated subtotal").assertIsDisplayed()
    }

    @Test
    fun `cart screen through the view model removes lines`() {
        val vm = ShopViewModel(backend)
        vm.addToCart(com.vayunmathur.shop.platform.testProduct(), "v1", 1, ctx)
        rule.setContent { DynamicTheme { CartScreen(vm, onPayInApp = {}) } }
        rule.onNodeWithText("Trail Runner").assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()
        rule.onNodeWithText("Your cart is empty").assertIsDisplayed()
        assertTrue(vm.cart.isEmpty())
    }
}
