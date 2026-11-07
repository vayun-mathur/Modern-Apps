package com.vayunmathur.shop.network

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CatalogOptionSelection
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderStatusQuery
import com.vayunmathur.shop.data.UcpError
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ShopBackendExtraTest {

    private fun ok(body: String) = BackendRawResponse(200, body, emptyMap(), null)

    private fun stub(response: BackendRawResponse) {
        ShopHttpBackend.request = { _, _, _ -> response }
    }

    private val productJson = """
        {"id": "p1", "title": "Trail Runner", "description": "Grippy",
         "priceRange": {"amount": 12999, "currency": "USD"}, "priceRangeMax": null,
         "url": "https://shop.example.com/p/1", "platformId": "shopify",
         "shopDomain": "shop.example.com",
         "images": [{"url": "https://img.example/x.png"}],
         "variants": [{"id": "v1", "title": "9", "price": {"amount": 12999, "currency": "USD"},
           "available": true, "optionValues": ["9"], "sku": "SKU-1"}],
         "checkoutEligible": true, "storeName": "Trail Store"}
    """.trimIndent()

    @Test
    fun `get product encodes every parameter into the URL`() = runBlocking<Unit> {
        var url: String? = null
        var method: String? = null
        ShopHttpBackend.request = { m, u, _ -> method = m; url = u; ok("{\"product\": $productJson}") }
        val product = ShopHttpBackend.getProduct(
            "shop.example.com", "p 1", variantId = "v1", title = "Trail Runner",
            selectedOptions = listOf(CatalogOptionSelection("Size", "9", "size-9")),
        ).getOrThrow()
        assertEquals("p1", product.id)
        assertEquals("SKU-1", product.variants.single().sku)
        assertEquals(null, product.priceRangeMax)
        assertEquals("GET", method)
        assertTrue(url!!.contains("shop=shop.example.com"), url)
        assertTrue(url!!.contains("id=p+1"), url)
        assertTrue(url!!.contains("variant=v1"), url)
        assertTrue(url!!.contains("title=Trail+Runner"), url)
        assertTrue(url!!.contains("selected="), url)
    }

    @Test
    fun `get product by handle builds the handle URL`() = runBlocking<Unit> {
        var url: String? = null
        ShopHttpBackend.request = { _, u, _ -> url = u; ok("{\"product\": $productJson}") }
        ShopHttpBackend.getProductByHandle("shop.example.com", "trail-runner").getOrThrow()
        assertTrue(url!!.endsWith("/product?shop=shop.example.com&handle=trail-runner"), url)
    }

    @Test
    fun `product endpoints reject blank identifiers locally`() = runBlocking<Unit> {
        stub(ok("{}"))
        assertIs<UcpError.Malformed>((ShopHttpBackend.getProduct("", "p1").exceptionOrNull() as ShopBackendException).error)
        assertIs<UcpError.Malformed>((ShopHttpBackend.getProduct("shop.example.com", " ").exceptionOrNull() as ShopBackendException).error)
        assertIs<UcpError.Malformed>((ShopHttpBackend.getProductByHandle("shop.example.com", "").exceptionOrNull() as ShopBackendException).error)
    }

    private val sessionJson = """
        {"checkoutId": "checkout-1", "status": "incomplete", "currency": "USD",
         "totals": {"subtotal": {"amount": 1000, "currency": "USD"}, "total": {"amount": 1200, "currency": "USD"}},
         "totalEntries": [], "continueUrl": "https://shop.example.com/checkout/1",
         "messages": [{"type": "info", "text": "Ships Friday", "code": "ship", "severity": "info"}],
         "review": {"shipping": [{"id": "m1", "groups": [{"id": "g1", "selected_option_id": "o1",
            "options": [{"id": "o1", "title": "Standard", "description": "3 days",
              "totals": [{"type": "shipping", "display_text": "", "amount": 200, "currency": "USD"}]}]}]}],
           "links": [{"type": "terms", "title": "Terms", "url": "https://shop.example.com/terms"}],
           "browserOnlyReason": null, "availableShippingLineIds": ["l1"]},
         "submittedLines": [{"variantId": "v1", "quantity": 2}], "trackingToken": "tok-9"}
    """.trimIndent()

    @Test
    fun `get checkout decodes the full review projection`() = runBlocking<Unit> {
        var url: String? = null
        ShopHttpBackend.request = { _, u, _ -> url = u; ok(sessionJson) }
        val checkout = ShopHttpBackend.getCheckout("shop.example.com", "checkout-1").getOrThrow()
        assertTrue(url!!.contains("/checkout-sessions?shop=shop.example.com&id=checkout-1"), url)
        assertEquals("tok-9", checkout.trackingToken)
        assertEquals("Ships Friday", checkout.messages.single().text)
        assertEquals("o1", checkout.review.shipping.single().groups.single().selectedOptionId)
        val option = checkout.review.shipping.single().groups.single().options.single()
        assertEquals("Standard", option.title)
        // A blank display_text falls back to the entry type.
        assertEquals("shipping", option.totals.single().label)
        assertEquals(Money(200, "USD"), option.totals.single().money)
        assertEquals("Terms", checkout.review.links.single().title)
        assertEquals(listOf("l1"), checkout.review.availableShippingLineIds)
        assertEquals(2, checkout.submittedLines.single().quantity)
    }

    @Test
    fun `update checkout sends a PUT with the session and lines`() = runBlocking<Unit> {
        var method: String? = null
        var body: String? = null
        ShopHttpBackend.request = { m, _, b -> method = m; body = b; ok(sessionJson) }
        val lines = listOf(CartLine(variantId = "v1", shopDomain = "shop.example.com", quantity = 2))
        val checkout = ShopHttpBackend.updateCheckout(
            "shop.example.com", "checkout-1", lines, CheckoutInputs(firstName = "Ada"), "intent-2",
        ).getOrThrow()
        assertEquals("checkout-1", checkout.checkoutId)
        assertEquals("PUT", method)
        assertTrue(body!!.contains("\"sessionId\":\"checkout-1\""), body)
        assertTrue(body!!.contains("\"quantity\":2"), body)
        assertTrue(body!!.contains("\"firstName\":\"Ada\""), body)
    }

    @Test
    fun `checkout writes reject invalid lines and keys locally`() = runBlocking<Unit> {
        stub(ok(sessionJson))
        val good = listOf(CartLine(variantId = "v1", shopDomain = "shop.example.com", quantity = 1))
        val inputs = CheckoutInputs()
        suspend fun malformed(block: suspend () -> Any?): UcpError? =
            (block() as? Result<*>)?.exceptionOrNull()?.let { (it as? ShopBackendException)?.error }

        assertIs<UcpError.Malformed>(malformed { ShopHttpBackend.createCheckout("shop.example.com", emptyList(), inputs, "k") })
        assertIs<UcpError.Malformed>(malformed {
            ShopHttpBackend.createCheckout("shop.example.com", listOf(CartLine(variantId = "v1", shopDomain = "other.example", quantity = 1)), inputs, "k")
        })
        assertIs<UcpError.Malformed>(malformed {
            ShopHttpBackend.createCheckout("shop.example.com", listOf(CartLine(variantId = "v1", shopDomain = "shop.example.com", quantity = 0)), inputs, "k")
        })
        assertIs<UcpError.Malformed>(malformed { ShopHttpBackend.createCheckout("shop.example.com", good, inputs, "k".repeat(257)) })
        assertIs<UcpError.Malformed>(malformed { ShopHttpBackend.updateCheckout("shop.example.com", "", good, inputs, "k") })
        assertIs<UcpError.Malformed>(malformed { ShopHttpBackend.updateCheckout("shop.example.com", "s1", good, inputs, "") })
        assertIs<UcpError.Malformed>(malformed { ShopHttpBackend.getCheckout("", "s1") })
        assertIs<UcpError.Malformed>(malformed { ShopHttpBackend.getCheckout("shop.example.com", "") })
    }

    @Test
    fun `search validates query, limit and country locally`() = runBlocking<Unit> {
        stub(ok("{\"products\": []}"))
        assertIs<UcpError.Malformed>((ShopHttpBackend.search("x".repeat(201)).exceptionOrNull() as ShopBackendException).error)
        assertIs<UcpError.Malformed>((ShopHttpBackend.search("shoes", limit = 0).exceptionOrNull() as ShopBackendException).error)
        assertIs<UcpError.Malformed>((ShopHttpBackend.search("shoes", limit = 51).exceptionOrNull() as ShopBackendException).error)
        assertIs<UcpError.Malformed>((ShopHttpBackend.search("shoes", country = "usa").exceptionOrNull() as ShopBackendException).error)
        // Boundary values are accepted.
        assertTrue(ShopHttpBackend.search("x".repeat(200), limit = 50, country = "GB").isSuccess)
        assertTrue(ShopHttpBackend.search("s", limit = 1).isSuccess)
    }

    @Test
    fun `search URL carries the scope only when one is set`() = runBlocking<Unit> {
        val urls = mutableListOf<String>()
        ShopHttpBackend.request = { _, u, _ -> urls += u; ok("{\"products\": []}") }
        ShopHttpBackend.search("rain boots", shop = null, limit = 5, country = "CA").getOrThrow()
        ShopHttpBackend.search("rain boots", shop = "shop.example.com", limit = 5, country = "CA").getOrThrow()
        assertTrue(urls[0].endsWith("/search?query=rain+boots&limit=5&country=CA"), urls[0])
        assertTrue(urls[1].endsWith("&shop=shop.example.com"), urls[1])
    }

    @Test
    fun `order status validates token count and order id length`() = runBlocking<Unit> {
        stub(ok("{\"orders\": []}"))
        assertIs<UcpError.Malformed>((ShopHttpBackend.orderStatus(emptyList()).exceptionOrNull() as ShopBackendException).error)
        assertIs<UcpError.Malformed>(
            (ShopHttpBackend.orderStatus((1..51).map { OrderStatusQuery("a".repeat(32)) }).exceptionOrNull() as ShopBackendException).error,
        )
        assertIs<UcpError.Malformed>(
            (ShopHttpBackend.orderStatus(listOf(OrderStatusQuery("a".repeat(32), "x".repeat(501)))).exceptionOrNull() as ShopBackendException).error,
        )
        assertTrue(ShopHttpBackend.orderStatus(listOf(OrderStatusQuery("a".repeat(32), "x".repeat(500)))).isSuccess)
    }

    @Test
    fun `merchants refresh flag reaches the URL`() = runBlocking<Unit> {
        val urls = mutableListOf<String>()
        ShopHttpBackend.request = { _, u, _ -> urls += u; ok("{\"merchants\": []}") }
        ShopHttpBackend.listMerchants().getOrThrow()
        ShopHttpBackend.listMerchants(refresh = true).getOrThrow()
        assertTrue(urls[0].endsWith("/merchants"), urls[0])
        assertTrue(urls[1].endsWith("/merchants?refresh=true"), urls[1])
    }

    @Test
    fun `a malformed success body and a transport throw both become failures, never throws`() = runBlocking<Unit> {
        stub(ok("{not json"))
        assertIs<UcpError.Malformed>((ShopHttpBackend.search("shoes").exceptionOrNull() as ShopBackendException).error)

        ShopHttpBackend.request = { _, _, _ -> throw IOException("socket closed") }
        assertIs<UcpError.Transport>((ShopHttpBackend.search("shoes").exceptionOrNull() as ShopBackendException).error)

        // Error envelope with a blank message falls back to the default text per code.
        val body = ShopHttpBackend.json.encodeToString(ShopErrorEnvelope(ShopErrorBody("transport", "")))
        stub(BackendRawResponse(502, body, emptyMap(), null))
        val error = (ShopHttpBackend.search("shoes").exceptionOrNull() as ShopBackendException).error
        assertIs<UcpError.Transport>(error)
    }

    @Test
    fun `url encoding escapes spaces and reserved characters`() {
        assertEquals("a+b", "a b".encode())
        assertEquals("a%26b%3Dc", "a&b=c".encode())
        assertEquals("plain", "plain".encode())
    }
}
