package com.vayunmathur.shop.network

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CatalogOptionSelection
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderStatusQuery
import com.vayunmathur.shop.data.UcpError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Remaining ShopHttpBackend endpoints and local validation, stubbed at the transport seam. */
class ShopBackendMoreTest {

    private fun ok(body: String) = BackendRawResponse(200, body, emptyMap(), null)

    private val productJson = """
        {"id": "gid://shopify/Product/7", "title": "Handle Shoe",
         "description": "By handle", "descriptionHtml": "",
         "priceRange": {"amount": 5000, "currency": "USD"}, "priceRangeMax": null,
         "url": "https://shop.example.com/p/7", "platformId": "shopify",
         "shopDomain": "shop.example.com", "images": [],
         "variants": [{"id": "v7", "title": "Default",
            "price": {"amount": 5000, "currency": "USD"}, "available": true,
            "optionValues": [], "storeName": "Trail Store", "shopDomain": "shop.example.com",
            "selectedOptions": []}],
         "checkoutEligible": true, "storeName": "Trail Store"}
    """.trimIndent()

    private val sessionJson = """
        {"checkoutId": "checkout-9", "status": "incomplete", "currency": "USD",
         "totals": {"subtotal": {"amount": 5000, "currency": "USD"}, "total": {"amount": 5500, "currency": "USD"}},
         "totalEntries": [], "continueUrl": "https://shop.example.com/checkout/9",
         "expiresAt": null, "messages": [], "version": "2026-08-25",
         "review": {"shipping": [], "links": [], "browserOnlyReason": null, "availableShippingLineIds": []},
         "submittedLines": [{"variantId": "v7", "quantity": 2}]}
    """.trimIndent()

    private fun line() = CartLine(
        variantId = "v7", productTitle = "Handle Shoe", unitPrice = Money(5000, "USD"),
        platformId = "shopify", shopDomain = "shop.example.com", quantity = 2,
    )

    @Test
    fun productByHandleBuildsHandleUrlAndDecodes() = runBlocking {
        var sentUrl: String? = null
        var sentMethod: String? = null
        ShopHttpBackend.request = { method, url, _ ->
            sentMethod = method; sentUrl = url; ok("""{"product": $productJson}""")
        }
        val product = ShopHttpBackend.getProductByHandle("shop.example.com", "handle-shoe").getOrThrow()
        assertEquals("GET", sentMethod)
        assertTrue(sentUrl!!.contains("handle=handle-shoe"))
        assertTrue(sentUrl!!.contains("shop=shop.example.com"))
        assertEquals("Handle Shoe", product.title)
        assertEquals("v7", product.variants.single().id)
        return@runBlocking
    }

    @Test
    fun productByHandleRejectsBlankArgsLocally() = runBlocking {
        ShopHttpBackend.request = { _, _, _ -> ok("{}") }
        assertIs<UcpError.Malformed>(
            (ShopHttpBackend.getProductByHandle("", "x").exceptionOrNull() as? ShopBackendException)?.error,
        )
        assertIs<UcpError.Malformed>(
            (ShopHttpBackend.getProductByHandle("shop.example.com", "").exceptionOrNull() as? ShopBackendException)?.error,
        )
        return@runBlocking
    }

    @Test
    fun getProductWithOptionsEncodesSelectedOptions() = runBlocking {
        var sentUrl: String? = null
        ShopHttpBackend.request = { _, url, _ -> sentUrl = url; ok("""{"product": $productJson}""") }
        val product = ShopHttpBackend.getProduct(
            "shop.example.com", "gid://shopify/Product/7", "v7", "Handle Shoe",
            listOf(CatalogOptionSelection("Size", "9", "opt1")),
        ).getOrThrow()
        assertEquals("v7", product.variants.single().id)
        assertTrue(sentUrl!!.contains("variant=v7"))
        assertTrue(sentUrl!!.contains("selected="))
        return@runBlocking
    }

    @Test
    fun getAndUpdateCheckoutRoundTripSession() = runBlocking {
        var methods = mutableListOf<String>()
        ShopHttpBackend.request = { method, _, _ -> methods += method; ok(sessionJson) }
        val fetched = ShopHttpBackend.getCheckout("shop.example.com", "checkout-9").getOrThrow()
        assertEquals("checkout-9", fetched.checkoutId)
        assertEquals(Money(5500, "USD"), fetched.totals.total)
        val updated = ShopHttpBackend.updateCheckout(
            "shop.example.com", "checkout-9", listOf(line()), CheckoutInputs(), "intent-9",
        ).getOrThrow()
        assertEquals("checkout-9", updated.checkoutId)
        assertEquals(2, updated.submittedLines.single().quantity)
        assertEquals(listOf("GET", "PUT"), methods)
        return@runBlocking
    }

    @Test
    fun checkoutEndpointsRejectInvalidArgsLocally() = runBlocking {
        ShopHttpBackend.request = { _, _, _ -> ok(sessionJson) }
        assertIs<UcpError.Malformed>(
            (ShopHttpBackend.getCheckout("", "x").exceptionOrNull() as? ShopBackendException)?.error,
        )
        assertIs<UcpError.Malformed>(
            (ShopHttpBackend.updateCheckout("shop.example.com", "", listOf(line()), CheckoutInputs(), "k")
                .exceptionOrNull() as? ShopBackendException)?.error,
        )
        assertIs<UcpError.Malformed>(
            (ShopHttpBackend.updateCheckout("shop.example.com", "s", emptyList(), CheckoutInputs(), "k")
                .exceptionOrNull() as? ShopBackendException)?.error,
        )
        assertIs<UcpError.Malformed>(
            (ShopHttpBackend.createCheckout("shop.example.com", listOf(line().copy(shopDomain = "other.example")), CheckoutInputs(), "k")
                .exceptionOrNull() as? ShopBackendException)?.error,
        )
        return@runBlocking
    }

    @Test
    fun searchAndStatusValidationBranches() = runBlocking {
        ShopHttpBackend.request = { _, _, _ -> ok("""{"products": []}""") }
        assertIs<UcpError.Malformed>(
            (ShopHttpBackend.search("x", limit = 0).exceptionOrNull() as? ShopBackendException)?.error,
        )
        assertIs<UcpError.Malformed>(
            (ShopHttpBackend.search("x", country = "usa").exceptionOrNull() as? ShopBackendException)?.error,
        )
        assertIs<UcpError.Malformed>(
            (ShopHttpBackend.orderStatus(emptyList()).exceptionOrNull() as? ShopBackendException)?.error,
        )
        assertIs<UcpError.Malformed>(
            (ShopHttpBackend.orderStatus(listOf(OrderStatusQuery("a".repeat(32), "x".repeat(501))))
                .exceptionOrNull() as? ShopBackendException)?.error,
        )
        // A valid scoped search encodes the shop and succeeds with an empty page.
        var sentUrl: String? = null
        ShopHttpBackend.request = { _, url, _ -> sentUrl = url; ok("""{"products": []}""") }
        assertTrue(ShopHttpBackend.search("shoe", shop = "shop.example.com", limit = 5, country = "CA").getOrThrow().isEmpty())
        assertTrue(sentUrl!!.contains("shop=shop.example.com"))
        assertTrue(sentUrl!!.contains("country=CA"))
        return@runBlocking
    }
}
