package com.vayunmathur.shop.network

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.UcpError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShopBackendTest {
    private fun ok(body: String) = BackendRawResponse(200, body, emptyMap(), null)

    private fun stub(response: BackendRawResponse) {
        ShopHttpBackend.request = { _, _, _ -> response }
    }

    private val productJson = """
        {"products": [{
            "id": "gid://shopify/Product/1", "title": "Trail Runner",
            "description": "Grippy shoes", "descriptionHtml": "",
            "priceRange": {"amount": 12999, "currency": "USD"},
            "priceRangeMax": {"amount": 14999, "currency": "USD"},
            "url": "https://shop.example.com/p/1",
            "platformId": "shopify", "shopDomain": "shop.example.com",
            "images": [{"url": "https://api.maproj.org/shop/v1/image?url=x", "alt": "Shoe"}],
            "variants": [{"id": "v11", "title": "9 / Blue",
                "price": {"amount": 12999, "currency": "USD"}, "available": true,
                "optionValues": ["9", "Blue"], "storeName": "Trail Store",
                "shopDomain": "shop.example.com",
                "selectedOptions": [{"name": "Size", "label": "9"}]}],
            "checkoutEligible": true, "storeName": "Trail Store"
        }]}
    """.trimIndent()

    private val sessionJson = """
        {"checkoutId": "checkout-1", "status": "incomplete", "currency": "USD",
         "totals": {"subtotal": {"amount": 1000, "currency": "USD"}, "total": {"amount": 1200, "currency": "USD"}},
         "totalEntries": [
            {"type": "subtotal", "display_text": "Items", "amount": 1000, "currency": "USD"},
            {"type": "total", "display_text": "Total", "amount": 1200, "currency": "USD"}],
         "continueUrl": "https://shop.example.com/checkout/1",
         "expiresAt": "2026-10-27T00:00:00Z", "messages": [], "version": "2026-08-25",
         "review": {"shipping": [], "links": [], "browserOnlyReason": null, "availableShippingLineIds": []},
         "submittedLines": [{"variantId": "v11", "quantity": 1}]}
    """.trimIndent()

    @Test fun searchDecodesDisplayProducts() = runBlocking {
        stub(ok(productJson))
        val products = ShopHttpBackend.search("trail shoes").getOrThrow()
        assertEquals(1, products.size)
        val product = products.single()
        assertEquals("Trail Runner", product.title)
        assertEquals(Money(12999, "USD"), product.priceRange)
        assertEquals(Money(14999, "USD"), product.priceRangeMax)
        assertEquals("shop.example.com", product.shopDomain)
        assertTrue(product.checkoutEligible)
        assertEquals("https://api.maproj.org/shop/v1/image?url=x", product.images.single().url)
        assertEquals("v11", product.variants.single().id)
        assertEquals("Size", product.variants.single().selectedOptions.single().name)
        return@runBlocking
    }

    @Test fun searchRejectsBlankQueryLocally() = runBlocking {
        stub(ok("{}"))
        val result = ShopHttpBackend.search("   ")
        val error = (result.exceptionOrNull() as? ShopBackendException)?.error
        assertIs<UcpError.Malformed>(error)
        return@runBlocking
    }

    @Test fun productDecodesDetail() = runBlocking {
        val inner = productJson.substringAfter("\"products\": [").substringBeforeLast("]")
        stub(ok("{\"product\": $inner}"))
        val product = ShopHttpBackend.getProduct("shop.example.com", "gid://shopify/Product/1").getOrThrow()
        assertEquals("gid://shopify/Product/1", product.id)
        return@runBlocking
    }

    @Test fun checkoutCreateDecodesSession() = runBlocking {
        stub(ok(sessionJson))
        val lines = listOf(CartLine(variantId = "v11", productTitle = "Trail Runner", unitPrice = Money(12999, "USD"), platformId = "shopify", shopDomain = "shop.example.com", quantity = 1))
        val checkout = ShopHttpBackend.createCheckout("shop.example.com", lines, CheckoutInputs(), "intent-1").getOrThrow()
        assertEquals("checkout-1", checkout.checkoutId)
        assertEquals("incomplete", checkout.status)
        assertEquals(Money(1200, "USD"), checkout.totals.total)
        assertEquals(1, checkout.submittedLines.size)
        assertEquals("v11", checkout.submittedLines.single().variantId)
        return@runBlocking
    }

    @Test fun checkoutRejectsBlankIdempotencyKeyLocally() = runBlocking {
        stub(ok(sessionJson))
        val lines = listOf(CartLine(variantId = "v11", shopDomain = "shop.example.com", quantity = 1))
        val result = ShopHttpBackend.createCheckout("shop.example.com", lines, CheckoutInputs(), "")
        assertIs<UcpError.Malformed>((result.exceptionOrNull() as? ShopBackendException)?.error)
        return@runBlocking
    }

    @Test fun merchantsDecodeDirectory() = runBlocking {
        val body = """{"merchants": [{"id": "m1", "name": "Trail Store", "domain": "shop.example.com",
            "siteUrl": "https://shop.example.com", "description": "Shoes",
            "directoryStatus": "live", "directoryVerifiedAt": "2026-10-01"}]}"""
        stub(ok(body))
        val merchants = ShopHttpBackend.listMerchants().getOrThrow()
        assertEquals("shop.example.com", merchants.single().domain)
        assertEquals("live", merchants.single().directoryStatus)
        return@runBlocking
    }

    @Test fun errorEnvelopeMapsToUcpErrors() = runBlocking {
        suspend fun errorFor(code: String, status: Int): UcpError? {
            val body = ShopHttpBackend.json.encodeToString(ShopErrorEnvelope(ShopErrorBody(code, "m", null)))
            stub(BackendRawResponse(status, body, emptyMap(), null))
            return (ShopHttpBackend.search("trail").exceptionOrNull() as? ShopBackendException)?.error
        }
        assertIs<UcpError.Unsupported>(errorFor("unsupported", 422))
        assertIs<UcpError.Transport>(errorFor("uncertain", 502))
        assertIs<UcpError.Transport>(errorFor("transport", 502))
        assertIs<UcpError.RateLimited>(errorFor("rate_limited", 429))
        assertIs<UcpError.Rpc>(errorFor("rpc", 502))
        assertIs<UcpError.Malformed>(errorFor("malformed", 400))
        assertIs<UcpError.Http>(errorFor("http", 502))
        // Unknown codes fall back to the merchant website.
        assertIs<UcpError.Unsupported>(errorFor("something_new", 503))
        return@runBlocking
    }

    @Test fun plainHttpErrorWithoutEnvelopeMapsToHttp() = runBlocking {
        stub(BackendRawResponse(503, "<html>down</html>", emptyMap(), "7"))
        val error = (ShopHttpBackend.search("trail").exceptionOrNull() as? ShopBackendException)?.error
        assertIs<UcpError.Http>(error)
        stub(BackendRawResponse(429, "busy", emptyMap(), "60"))
        val limited = (ShopHttpBackend.search("trail").exceptionOrNull() as? ShopBackendException)?.error as? UcpError.RateLimited
        assertEquals(60_000L, limited?.retryAfterMs)
        return@runBlocking
    }

    @Test fun splitByShopGroupsPerMerchant() {
        val lines = listOf(
            CartLine(variantId = "a", platformId = "shopify", shopDomain = "one.example", quantity = 1),
            CartLine(variantId = "b", platformId = "shopify", shopDomain = "one.example", quantity = 2),
            CartLine(variantId = "c", platformId = "generic", shopDomain = "two.example", quantity = 1),
        )
        val grouped = splitByShop(lines)
        assertEquals(2, grouped.size)
        assertEquals(2, grouped[Pair("shopify", "one.example")]?.size)
        assertEquals(1, grouped[Pair("generic", "two.example")]?.size)
    }
}
