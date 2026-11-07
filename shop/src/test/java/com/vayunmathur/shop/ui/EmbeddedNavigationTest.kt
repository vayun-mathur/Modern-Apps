package com.vayunmathur.shop.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.shop.Navigation
import com.vayunmathur.shop.Route
import com.vayunmathur.shop.platform.AppInit
import com.vayunmathur.shop.platform.FakeProfileStore
import com.vayunmathur.shop.platform.FakeShopBackend
import com.vayunmathur.shop.platform.ShopViewModel
import com.vayunmathur.shop.platform.completeProfile
import com.vayunmathur.shop.platform.testProduct
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp")
class EmbeddedNavigationTest {

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
        ctx.getSharedPreferences("shop_link_defaults", Context.MODE_PRIVATE).edit()
            .putBoolean("dismissed", true).commit()
        backend = FakeShopBackend()
    }

    private fun vmWithProfile(profile: com.vayunmathur.shop.data.ShopProfile?): ShopViewModel {
        val store = FakeProfileStore(profile)
        val vm = ShopViewModel(backend)
        vm.profileStoreFactory = { store }
        vm.observeProfile(ctx)
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (vm.profileLoaded) return vm
            Thread.sleep(20)
        }
        error("profile did not load")
    }

    // ---------- embedded checkout ----------

    @Test
    fun `embedded checkout without a request closes immediately`() {
        val vm = ShopViewModel(backend)
        var closes = 0
        rule.setContent { DynamicTheme { EmbeddedCheckoutScreen(vm, onClose = { closes++ }) } }
        rule.waitUntil(timeoutMillis = 10_000) { closes == 1 }
    }

    // ---------- navigation ----------

    @Test
    fun `navigation gates on the profile form until the profile is complete`() {
        val vm = vmWithProfile(null)
        rule.setContent { DynamicTheme { Navigation(vm) } }
        rule.onNodeWithText("Add your details to start shopping.").assertIsDisplayed()
    }

    @Test
    fun `navigation with a complete profile lands on the shop tabs`() {
        val vm = vmWithProfile(completeProfile)
        rule.setContent { DynamicTheme { Navigation(vm) } }
        rule.onNodeWithText("Search products").assertIsDisplayed()
        rule.onNodeWithText("No products found").assertIsDisplayed()
        // Switch to the cart tab through the bottom navigation.
        rule.onNodeWithText("Cart").assertIsDisplayed()
        rule.onNodeWithText("Cart").performClick()
        rule.onNodeWithText("Your cart is empty").assertIsDisplayed()
        rule.onNodeWithText("Orders").performClick()
        rule.onNodeWithText("No orders yet").assertIsDisplayed()
        rule.onNodeWithText("Profile").performClick()
        rule.onNodeWithText("Delivery address (optional)").assertIsDisplayed()
    }

    @Test
    fun `a pending product route opens the detail over the tabs`() {
        val vm = vmWithProfile(completeProfile)
        backend.searchResult = Result.success(listOf(testProduct()))
        // Seed a snapshot so the detail has something to paint while it refreshes.
        vm.updateQuery("trail")
        vm.search()
        var seeded = false
        var attempts = 0
        while (!seeded && attempts < 200) {
            shadowOf(Looper.getMainLooper()).idle()
            seeded = vm.results.size == 1
            if (!seeded) { Thread.sleep(20); attempts++ }
        }
        check(seeded) { "search did not seed a snapshot" }
        com.vayunmathur.shop.network.ShopHttpBackend.request = { _, _, _ ->
            com.vayunmathur.shop.network.BackendRawResponse(502, "{}", emptyMap(), null)
        }
        rule.setContent { DynamicTheme { Navigation(vm) } }
        // The tabs are up (search results from the seeded snapshot are showing).
        rule.onNodeWithText("Trail Runner").assertExists()
        backend.handleResult = Result.success(testProduct())
        vm.openLink("https://shop.example.com/products/trail-runner")
        rule.waitUntil(timeoutMillis = 10_000) { vm.pendingRoute is Route.ProductDetail || vm.linkedProducts.isNotEmpty() }
        // The navigation effect consumes the route and pushes the detail.
        rule.waitUntil(timeoutMillis = 10_000) {
            runCatching { rule.onNodeWithText("Trail Runner").assertIsDisplayed() }.isSuccess
        }
    }

    // ---------- routes ----------

    @Test
    fun `routes round-trip through their serializers`() {
        assertEquals(Route.Search, Json.decodeFromString(Route.serializer(), Json.encodeToString(Route.serializer(), Route.Search)))
        assertEquals(Route.Cart, Json.decodeFromString(Route.serializer(), Json.encodeToString(Route.serializer(), Route.Cart)))
        assertEquals(Route.Orders, Json.decodeFromString(Route.serializer(), Json.encodeToString(Route.serializer(), Route.Orders)))
        assertEquals(
            Route.EmbeddedCheckout,
            Json.decodeFromString(Route.serializer(), Json.encodeToString(Route.serializer(), Route.EmbeddedCheckout)),
        )
        val detail = Route.ProductDetail("v1", "shopify", "shop.example.com")
        assertEquals(detail, Json.decodeFromString(Route.serializer(), Json.encodeToString(Route.serializer(), detail)))
    }

}
