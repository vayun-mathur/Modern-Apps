package com.vayunmathur.shop.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.shop.network.BackendRawResponse
import com.vayunmathur.shop.network.ShopHttpBackend
import com.vayunmathur.shop.platform.AppInit
import com.vayunmathur.shop.platform.FakeShopBackend
import com.vayunmathur.shop.platform.ShopViewModel
import com.vayunmathur.shop.platform.testProduct
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp")
class ProductScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var backend: FakeShopBackend
    private lateinit var vm: ShopViewModel

    private val liveProductJson = """
        {"product": {"id": "p1", "title": "Trail Runner", "description": "Grippy",
         "priceRange": {"amount": 12999, "currency": "USD"},
         "url": "https://shop.example.com/products/trail-runner", "platformId": "shopify",
         "shopDomain": "shop.example.com", "images": [],
         "variants": [{"id": "v1", "title": "9 / Blue", "price": {"amount": 12999, "currency": "USD"},
           "available": true, "storeName": "Trail Store", "shopDomain": "shop.example.com"}],
         "checkoutEligible": true, "storeName": "Trail Store"}}
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(ctx) }
        AppInit.start(ctx)
        runBlocking { AppInit.awaitReady() }
        ctx.getSharedPreferences("shop_cart", Context.MODE_PRIVATE).edit().clear().commit()
        backend = FakeShopBackend()
        vm = ShopViewModel(backend)
    }

    @AfterTest
    fun tearDown() {
        ShopHttpBackend.request = { _, _, _ -> throw IOException("stubbed off in tests") }
    }

    private fun seedSnapshot() {
        backend.searchResult = Result.success(listOf(testProduct()))
        vm.updateQuery("trail")
        vm.search()
        repeat(200) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (vm.results.size == 1) return
            Thread.sleep(20)
        }
        assertEquals(1, vm.results.size)
    }

    private fun setScreen(onPayInApp: () -> Unit = {}) {
        rule.setContent {
            DynamicTheme {
                ProductScreen(
                    viewModel = vm, variantId = "v1", platformId = "shopify",
                    shopDomain = "shop.example.com", onBack = {}, onPayInApp = onPayInApp,
                )
            }
        }
    }

    @Test
    fun `a live refresh verifies the snapshot and adding to cart works`() {
        seedSnapshot()
        ShopHttpBackend.request = { _, _, _ -> BackendRawResponse(200, liveProductJson, emptyMap(), null) }
        setScreen()
        rule.waitUntil(timeoutMillis = 10_000) {
            runCatching { rule.onNodeWithText("Add to cart").assertIsEnabled() }.isSuccess
        }
        rule.onNodeWithText("Trail Runner").assertIsDisplayed()
        rule.onNodeWithText("Add to cart").performClick()
        assertEquals(1, vm.cart.size)
        assertEquals("v1", vm.cart.single().variantId)
    }

    @Test
    fun `a failed refresh keeps the snapshot unverified with a retry`() {
        seedSnapshot()
        ShopHttpBackend.request = { _, _, _ -> BackendRawResponse(502, "{}", emptyMap(), null) }
        setScreen()
        rule.waitUntil(timeoutMillis = 10_000) {
            runCatching { rule.onNodeWithText("Retry").assertIsDisplayed() }.isSuccess
        }
        rule.onNodeWithText("Add to cart").assertExists()
    }

    @Test
    fun `a refresh that drops the variant disables cart actions`() {
        seedSnapshot()
        val dropped = liveProductJson.replace("\"id\": \"v1\"", "\"id\": \"v-other\"")
        ShopHttpBackend.request = { _, _, _ -> BackendRawResponse(200, dropped, emptyMap(), null) }
        setScreen()
        rule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                rule.onNodeWithText("The store no longer lists this exact variant. Cart actions are disabled.").assertIsDisplayed()
            }.isSuccess
        }
    }

    @Test
    fun `without a snapshot and a failing backend the product is unavailable`() {
        ShopHttpBackend.request = { _, _, _ -> throw IOException("offline") }
        setScreen()
        // No snapshot: the screen first checks, then offers a retry. The backend used here is
        // the HTTP backend (the snapshot lookup found nothing, so the refresh uses the variant
        // id as the product id and fails at the transport seam).
        rule.waitUntil(timeoutMillis = 10_000) {
            runCatching { rule.onNodeWithText("Retry").assertIsDisplayed() }.isSuccess ||
                runCatching { rule.onNodeWithText("This product is unavailable. Please retry.").assertIsDisplayed() }.isSuccess
        }
        assertTrue(vm.cart.isEmpty())
    }
}
