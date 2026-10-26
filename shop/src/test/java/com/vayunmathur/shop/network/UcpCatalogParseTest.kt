package com.vayunmathur.shop.network

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.Money
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fixtures follow the dated UCP product, availability, media and totals schemas. */
class UcpCatalogParseTest {
    private fun obj(raw: String) = UcpApi.json.parseToJsonElement(raw) as JsonObject

    @Test
    fun parsesCurrentProductShape() {
        val product = UcpCatalog.parseProducts(obj("""
            {"products":[{"id":"p1","title":"Trail Runner","description":{"plain":"Grippy shoes"},
              "url":"https://shop.example/products/trail",
              "price_range":{"min":{"amount":12999,"currency":"USD"},"max":{"amount":14999,"currency":"USD"}},
              "media":[{"type":"image","url":"https://cdn.shopify.com/s/img.jpg","alt_text":"side"}],
              "variants":[{"id":"v1","title":"9 / Blue","description":{"plain":"Blue"},
                "price":{"amount":12999,"currency":"USD"},"availability":{"available":true},
                "options":[{"name":"Size","label":"9"},{"name":"Color","label":"Blue"}]}]}]}
        """), "shopify", "shop.example").single()
        assertEquals("Grippy shoes", product.description)
        assertEquals(Money(12999, "USD"), product.priceRange)
        assertEquals("side", product.images.single().alt)
        assertEquals(listOf("9", "Blue"), product.variants.single().optionValues)
        assertTrue(product.variants.single().available)
    }

    @Test
    fun dropsNonMerchantImagesAndUnknownAvailability() {
        val product = UcpCatalog.parseProducts(obj("""
            {"products":[{"id":"p","media":[
              {"type":"image","url":"https://evil.example/track.png"},
              {"type":"image","url":"http://shop.example/img.png"}],
              "variants":[{"id":"v","price":{"amount":100,"currency":"USD"}}]}]}
        """), "generic", "shop.example").single()
        assertTrue(product.images.isEmpty())
        assertFalse(product.variants.single().available)
    }

    @Test
    fun emptyProductsIsSuccessButMissingProductsIsMalformed() {
        assertTrue(UcpCatalog.parseProducts(obj("""{"products":[]}"""), "generic", "shop.example").isEmpty())
        assertFailsWith<UcpHttpException> {
            UcpCatalog.parseProducts(obj("""{"other":[]}"""), "generic", "shop.example")
        }
    }

    @Test
    fun parsesOrderedCheckoutTotalsAndPendingStatus() {
        val checkout = UcpCheckoutApi.parseCheckout(obj("""
            {"id":"co1","status":"requires_escalation","currency":"KWD",
              "totals":[{"type":"subtotal","display_text":"Items","amount":12999},
                {"type":"tax","display_text":"Tax","amount":1000},
                {"type":"total","display_text":"Total","amount":13999}],
              "continue_url":"https://shop.example/checkout/co1"}
        """))
        assertEquals("requires_escalation", checkout.status)
        assertEquals(Money(13999, "KWD"), checkout.totals.total)
        assertEquals(listOf("Items", "Tax", "Total"), checkout.totalEntries.map { it.label })
    }

    @Test
    fun rejectsMissingOrDuplicatedGrandTotal() {
        assertFailsWith<UcpHttpException> {
            UcpCheckoutApi.parseCheckout(obj("""{"id":"c","status":"incomplete","currency":"USD","totals":[]}"""))
        }
        assertFailsWith<UcpHttpException> {
            UcpCheckoutApi.parseCheckout(obj("""{"id":"c","status":"incomplete","currency":"USD",
              "totals":[{"type":"subtotal","amount":1},{"type":"total","amount":1},{"type":"total","amount":1}]}"""))
        }
    }

    @Test
    fun splitsCollidingVariantIdsByMerchant() {
        val split = UcpCheckoutApi.splitByShop(listOf(
            CartLine(variantId = "same", shopDomain = "a.example", platformId = "shopify"),
            CartLine(variantId = "same", shopDomain = "b.example", platformId = "generic"),
        ))
        assertEquals(2, split.size)
        assertEquals("a.example", split["shopify" to "a.example"]?.single()?.shopDomain)
    }

    @Test
    fun searchUsesContextAndPaginationRatherThanInventedFilters() {
        val arguments = UcpCatalog.searchArguments("shoes", "US", 100)["catalog"] as JsonObject
        assertEquals("50", (arguments["pagination"] as JsonObject)["limit"].toString())
        assertEquals("\"US\"", (arguments["context"] as JsonObject)["address_country"].toString())
        assertFalse(arguments.containsKey("filters"))
    }

    @Test
    fun platformForFallsBackToGeneric() {
        assertEquals("shopify", UcpCheckoutApi.platformFor("shopify").id)
        assertEquals("generic", UcpCheckoutApi.platformFor("unknown").id)
    }
}
