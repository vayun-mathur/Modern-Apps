package com.vayunmathur.shop.network

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderStatusQuery
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.domain.ServerCheckoutGateway
import com.vayunmathur.shop.platform.FakeShopBackend
import com.vayunmathur.shop.platform.testLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ShopDtosAndGatewayTest {

    private val json = ShopHttpBackend.json

    @Test
    fun `the server gateway delegates create update and get to the backend`() = runBlocking {
        val backend = FakeShopBackend().apply {
            checkoutResult = Result.success(UcpCheckout(checkoutId = "c9", continueUrl = "https://shop.example.com/c"))
        }
        val gateway = ServerCheckoutGateway(backend)
        val lines = listOf(testLine())
        val inputs = CheckoutInputs(firstName = "Ada")
        assertEquals("c9", gateway.create("shop.example.com", lines, inputs, "i1").getOrThrow().checkoutId)
        assertEquals("c9", gateway.update("shop.example.com", "c9", lines, inputs, "i2").getOrThrow().checkoutId)
        assertEquals("c9", gateway.get("shop.example.com", "c9").getOrThrow().checkoutId)

        backend.checkoutResult = Result.failure(IllegalStateException("down"))
        assertTrue(gateway.create("shop.example.com", lines, inputs, "i3").isFailure)
        Unit
    }

    @Test
    fun `checkout request DTOs serialize to the wire shape`() {
        val line = ShopCheckoutLine("v1", 2)
        assertEquals("""{"variantId":"v1","quantity":2}""", json.encodeToString(line))

        val inputs = ShopCheckoutInputs(
            firstName = "Ada", lastName = "Buyer", email = "ada@example.com", phone = "+14155550123",
            street = "1 Main", city = "Springfield", region = "IL", country = "US", postalCode = "62701",
            options = mapOf("gift" to "yes"), shippingRequested = true,
        )
        val create = ShopCreateCheckoutRequest("shop.example.com", listOf(line), inputs, "intent-1")
        val encoded = json.encodeToString(create)
        assertTrue(encoded.contains("\"shop\":\"shop.example.com\""), encoded)
        assertTrue(encoded.contains("\"idempotencyKey\":\"intent-1\""), encoded)
        assertTrue(encoded.contains("\"shippingRequested\":true"), encoded)
        // Defaults are encoded (encodeDefaults is on) so the server sees explicit empties.
        val update = ShopUpdateCheckoutRequest("shop.example.com", "s1", listOf(line), ShopCheckoutInputs(), "intent-2")
        assertTrue(json.encodeToString(update).contains("\"firstName\":\"\""))
        assertEquals(create.copy(idempotencyKey = "intent-9").idempotencyKey, "intent-9")
        assertNotEquals(create, update as Any)
    }

    @Test
    fun `cart lines and checkout inputs convert to request DTOs`() {
        val cartLine = CartLine(
            variantId = "v1", productTitle = "Shoes", unitPrice = Money(100, "USD"),
            platformId = "shopify", shopDomain = "shop.example.com", quantity = 3, storeName = "Trail",
        )
        assertEquals(ShopCheckoutLine("v1", 3), cartLine.toRequestLine())

        val inputs = CheckoutInputs(
            firstName = "Ada", lastName = "Buyer", email = "ada@example.com", phone = "+1",
            street = "1 Main", extendedAddress = "Apt 2", city = "Springfield", region = "IL",
            country = "US", postalCode = "62701", options = mapOf("k" to "v"), shippingRequested = true,
        )
        val dto = inputs.toRequestInputs()
        assertEquals("Ada", dto.firstName)
        assertEquals("Apt 2", dto.extendedAddress)
        assertEquals(mapOf("k" to "v"), dto.options)
        assertTrue(dto.shippingRequested)
        // Buyer-bearing models never leak into logs.
        assertEquals("CheckoutInputs(redacted)", inputs.toString())
    }

    @Test
    fun `response DTOs decode with defaults and round-trip`() {
        val money = json.decodeFromString<ShopMoney>("""{"amount": 250, "currency": "EUR"}""")
        assertEquals(250L, money.amount)
        assertEquals(Money(250, "EUR"), money.toModel())
        assertEquals(ShopMoney(0, "USD"), json.decodeFromString<ShopMoney>("{}"))
        assertEquals("""{"amount":0,"currency":"USD"}""", json.encodeToString(ShopMoney()))

        val totals = json.decodeFromString<ShopTotals>("""{"subtotal": {"amount": 1}, "total": {"amount": 2}}""")
        assertEquals(1L, totals.subtotal.amount)
        assertEquals("USD", totals.total.currency)

        val entry = ShopTotalEntry("total", "", 1200, "USD")
        assertEquals("total", entry.displayText.ifBlank { entry.type })

        val message = json.decodeFromString<ShopMessage>("""{"type":"error","text":"Add phone","path":"$.phone","code":"missing","severity":"recoverable"}""")
        assertEquals("$.phone", message.path)
        assertEquals(ShopMessage(), json.decodeFromString<ShopMessage>("{}"))

        val option = ShopShippingOption("o1", "Standard", "3 days", listOf(ShopTotalEntry("shipping", "Shipping", 200, "USD")))
        val group = ShopShippingGroup("g1", "o1", listOf(option))
        val method = ShopShippingMethod("m1", listOf(group))
        assertEquals("o1", method.groups.single().selectedOptionId)
        assertEquals(200L, method.groups.single().options.single().totals.single().amount)

        val link = ShopLegalLink("terms", "Terms", "https://shop.example.com/terms")
        val review = ShopReview(listOf(method), listOf(link), "browser only", listOf("l1"))
        assertEquals("browser only", review.browserOnlyReason)
        assertEquals(ShopReview(), ShopReview(emptyList(), emptyList(), null, emptyList()).copy(browserOnlyReason = null))

        val status = ShopOrderStatus("t", true, null)
        assertEquals(true, status.found)
        assertEquals("""{"orders":[]}""", json.encodeToString(ShopOrderStatusResponse()))
        assertEquals("""{"orders":[{"token":"abc","orderId":"o1"}]}""", json.encodeToString(ShopOrderStatusRequest(listOf(OrderStatusQuery("abc", "o1")))))
    }

    @Test
    fun `product and merchant DTOs map every field to display models`() {
        val product = ShopProduct(
            id = "p1", title = "Shoes", description = "Desc", priceRange = ShopMoney(100, "USD"),
            priceRangeMax = ShopMoney(200, "USD"), url = "https://shop.example.com/p/1",
            platformId = "shopify", shopDomain = "shop.example.com",
            images = listOf(ShopProductImage("https://img.example/x.png", "alt text")),
            variants = listOf(
                ShopVariant(
                    id = "v1", title = "9", price = ShopMoney(100, "USD"), available = true,
                    optionValues = listOf("9"), sku = "SKU", storeName = "Trail",
                    shopDomain = "shop.example.com",
                    selectedOptions = listOf(ShopCatalogOption("Size", "9", "size-9")),
                ),
            ),
            checkoutEligible = true, descriptionHtml = "<p>Desc</p>", storeName = "Trail",
        )
        val model = product.toModel()
        assertEquals("alt text", model.images.single().alt)
        assertEquals("size-9", model.variants.single().selectedOptions.single().id)
        assertEquals(Money(200, "USD"), model.priceRangeMax)
        assertEquals("<p>Desc</p>", model.descriptionHtml)

        val merchant = ShopMerchant("m1", "Trail", "shop.example.com", "https://shop.example.com", "Shoes", "live", "2026-10-01")
        val listing = merchant.toModel()
        assertEquals("live", listing.directoryStatus)
        assertEquals("2026-10-01", listing.directoryVerifiedAt)
        assertEquals(ShopMerchant(), ShopMerchant("", "", "", ""))
    }

    @Test
    fun `error envelope DTOs carry code message retry and continue URL`() {
        val body = ShopErrorBody("unsupported", "Needs website", 1500, "https://shop.example.com/checkout")
        val envelope = ShopErrorEnvelope(body)
        assertEquals(1500L, envelope.error.retryAfterMs)
        assertEquals("https://shop.example.com/checkout", envelope.error.continueUrl)
        val decoded = json.decodeFromString<ShopErrorEnvelope>(
            """{"error": {"code": "rate_limited", "message": "slow down", "retryAfterMs": 250}}""",
        )
        assertEquals("rate_limited", decoded.error.code)
        assertEquals(250L, decoded.error.retryAfterMs)
        assertEquals(Json { ignoreUnknownKeys = true }.decodeFromString<ShopErrorEnvelope>("""{"error": {}}"""), ShopErrorEnvelope())
    }
}
