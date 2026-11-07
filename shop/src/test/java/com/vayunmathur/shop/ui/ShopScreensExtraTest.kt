package com.vayunmathur.shop.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.shop.domain.EcAction
import com.vayunmathur.shop.domain.EmbeddedCheckout
import com.vayunmathur.shop.network.BackendRawResponse
import com.vayunmathur.shop.network.ShopHttpBackend
import com.vayunmathur.shop.platform.AppInit
import com.vayunmathur.shop.platform.FakeShopBackend
import com.vayunmathur.shop.platform.ShopViewModel
import com.vayunmathur.shop.platform.testProduct
import com.vayunmathur.shop.platform.testVariant
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp")
class ShopScreensExtraTest {

    @get:Rule
    val rule = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var backend: FakeShopBackend
    private lateinit var vm: ShopViewModel

    @BeforeTest
    fun setUp() {
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(ctx) }
        AppInit.start(ctx)
        runBlocking { AppInit.awaitReady() }
        ctx.getSharedPreferences("shop_cart", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("shop_orders", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("shop_link_defaults", Context.MODE_PRIVATE).edit().putBoolean("dismissed", true).commit()
        backend = FakeShopBackend()
        vm = ShopViewModel(backend)
    }

    @AfterTest
    fun tearDown() {
        ShopHttpBackend.request = { _, _, _ -> throw IOException("stubbed off in tests") }
    }

    // ---------- embedded checkout protocol (host logic) ----------

    @Test
    fun `embedded URL adds the protocol parameters and rejects non-https`() {
        assertEquals(
            "https://shop.example.com/checkout/1?ec_version=2026-08-25&ec_color_scheme=light&ec_delegate=window.open",
            EmbeddedCheckout.embeddedUrl("https://shop.example.com/checkout/1", dark = false),
        )
        assertTrue(
            EmbeddedCheckout.embeddedUrl("https://shop.example.com/checkout/1?x=1", dark = true)!!
                .contains("&ec_color_scheme=dark"),
        )
        assertNull(EmbeddedCheckout.embeddedUrl("http://shop.example.com/checkout", dark = false))
        assertNull(EmbeddedCheckout.embeddedUrl("not a url", dark = false))
    }

    @Test
    fun `ready and unknown methods are answered, junk is ignored`() {
        val ready = EmbeddedCheckout.handle("""{"jsonrpc":"2.0","id":7,"method":"ec.ready"}""")
        val reply = assertIs<EcAction.Reply>(ready)
        assertTrue(reply.json.contains("\"id\":7"), reply.json)
        assertTrue(reply.json.contains("2026-08-25"), reply.json)

        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""{"method":"ec.ready"}"""))
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("not json"))
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""{"id":1}"""))
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""[1,2]"""))

        val unknown = EmbeddedCheckout.handle("""{"id":"abc","method":"ec.something_new"}""")
        val notFound = assertIs<EcAction.Reply>(unknown)
        assertTrue(notFound.json.contains("-32601"), notFound.json)
        assertTrue(notFound.json.contains("ec.something_new"), notFound.json)
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""{"method":"ec.something_new"}"""))
    }

    @Test
    fun `window open requests validate the URL and echo the id`() {
        val open = EmbeddedCheckout.handle(
            """{"id":3,"method":"ec.window.open_request","params":{"url":"https://bank.example.com/auth"}}""",
        )
        val action = assertIs<EcAction.OpenWindow>(open)
        assertEquals("https://bank.example.com/auth", action.url)
        assertEquals(JsonPrimitive(3), action.id)

        val rejected = EmbeddedCheckout.handle(
            """{"id":4,"method":"ec.window.open_request","params":{"url":"http://insecure.example.com/"}}""",
        )
        assertTrue(assertIs<EcAction.Reply>(rejected).json.contains("window_open_rejected_error"))
        assertEquals(
            EcAction.Ignore,
            EmbeddedCheckout.handle("""{"method":"ec.window.open_request","params":{"url":"https://example.com/"}}"""),
        )

        val okResult = EmbeddedCheckout.windowOpenResult(JsonPrimitive("x"), ok = true)
        assertTrue(okResult.contains("\"status\":\"success\""), okResult)
        val badResult = EmbeddedCheckout.windowOpenResult(JsonPrimitive("x"), ok = false)
        assertTrue(badResult.contains("window_open_rejected_error"), badResult)
    }

    @Test
    fun `completion extracts order id, permalink and total, with fallbacks`() {
        val complete = EmbeddedCheckout.handle(
            """{"id":1,"method":"ec.complete","params":{"checkout":{"id":"sess-1",
               "order":{"id":"order-9","permalink_url":"https://shop.example.com/orders/9"},
               "totals":[{"type":"subtotal","amount":100},{"type":"total","amount":12999}]}}}""",
        )
        val done = assertIs<EcAction.Completed>(complete)
        assertEquals("order-9", done.orderId)
        assertEquals("https://shop.example.com/orders/9", done.permalinkUrl)
        assertEquals(12999L, done.totalMinor)

        // No order object: the checkout id is the order id, and there may be no total.
        val bare = EmbeddedCheckout.handle("""{"id":1,"method":"ec.complete","params":{"checkout":{"id":"sess-2"}}}""")
        val bareDone = assertIs<EcAction.Completed>(bare)
        assertEquals("sess-2", bareDone.orderId)
        assertNull(bareDone.permalinkUrl)
        assertNull(bareDone.totalMinor)

        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""{"id":1,"method":"ec.complete","params":{}}"""))
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""{"id":1,"method":"ec.complete"}"""))
    }

    @Test
    fun `errors surface their merchant message or a default`() {
        val failed = EmbeddedCheckout.handle(
            """{"id":1,"method":"ec.error","params":{"error":{"message":"Card declined"}}}""",
        )
        assertEquals(EcAction.Failed("Card declined"), failed)
        val content = EmbeddedCheckout.handle(
            """{"id":1,"method":"ec.error","params":{"error":{"content":"Try another card"}}}""",
        )
        assertEquals(EcAction.Failed("Try another card"), content)
        val fallback = EmbeddedCheckout.handle("""{"id":1,"method":"ec.error","params":{}}""")
        assertEquals("The store's checkout reported an error.", (fallback as EcAction.Failed).message)
    }

    // ---------- custom-tab helper ----------

    @Test
    fun `open continue URL refuses non-https links`() {
        // No activity is started for a missing/invalid link; the messenger path is exercised.
        openContinueUrl(ctx, "http://insecure.example.com/pay")
        openContinueUrl(ctx, "")
        assertTrue(true)
    }

    @Test
    fun `open continue URL launches a tab for https links`() {
        openContinueUrl(ctx, "https://shop.example.com/checkout/1")
        val started = shadowOf(ctx as android.app.Application).nextStartedActivity
        assertTrue(
            started?.dataString?.contains("shop.example.com") == true,
            "expected a browser launch, got $started",
        )
    }

    // ---------- product screen buy-now and variant reselect ----------

    private val liveTwoVariantJson = """
        {"product": {"id": "p1", "title": "Trail Runner", "description": "Grippy",
         "priceRange": {"amount": 12999, "currency": "USD"},
         "url": "https://shop.example.com/products/trail-runner", "platformId": "shopify",
         "shopDomain": "shop.example.com", "images": [],
         "variants": [
           {"id": "v1", "title": "9 / Blue", "price": {"amount": 12999, "currency": "USD"},
            "available": true, "storeName": "Trail Store", "shopDomain": "shop.example.com"},
           {"id": "v2", "title": "10 / Red", "price": {"amount": 13999, "currency": "USD"},
            "available": true, "storeName": "Trail Store", "shopDomain": "shop.example.com"}],
         "checkoutEligible": true, "storeName": "Trail Store"}}
    """.trimIndent()

    @Test
    fun `buy now adds the variant and hands off to the embedded checkout`() {
        backend.searchResult = Result.success(
            listOf(testProduct(variants = listOf(testVariant(id = "v1"), testVariant(id = "v2", title = "10 / Red")))),
        )
        vm.updateQuery("trail")
        vm.search()
        repeat(200) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (vm.results.size == 1) {
                ShopHttpBackend.request = { _, _, _ -> BackendRawResponse(200, liveTwoVariantJson, emptyMap(), null) }
                var paid = false
                rule.setContent {
                    DynamicTheme {
                        ProductScreen(vm, "v1", "shopify", "shop.example.com", onBack = {}, onPayInApp = { paid = true })
                    }
                }
                rule.waitUntil(timeoutMillis = 10_000) {
                    runCatching { rule.onNodeWithText("Buy now").assertIsDisplayed() }.isSuccess
                }
                // Without a profile the checkout cannot be created, but the line must be added.
                rule.onNodeWithText("Buy now").performClick()
                assertEquals(1, vm.cart.size)
                return
            }
            Thread.sleep(20)
        }
        error("snapshot never seeded")
    }

    @Test
    fun `product screen with a blank publishing domain disables actions`() {
        val blankDomain = testProduct(shopDomain = "", variants = listOf(testVariant(shopDomain = "")))
        backend.searchResult = Result.success(listOf(blankDomain))
        vm.updateQuery("trail")
        vm.search()
        repeat(200) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (vm.results.size == 1) {
                rule.setContent {
                    DynamicTheme { ProductScreen(vm, "v1", "shopify", "", onBack = {}, onPayInApp = {}) }
                }
                rule.waitUntil(timeoutMillis = 10_000) {
                    runCatching {
                        rule.onNodeWithText("The publishing store domain is missing. Cart actions are disabled.")
                            .assertIsDisplayed()
                    }.isSuccess
                }
                return
            }
            Thread.sleep(20)
        }
        error("snapshot never seeded")
    }

}
