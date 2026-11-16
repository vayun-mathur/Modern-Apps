package com.vayunmathur.shop.network

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderStatusQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** DTO defaults, equality/copy (generated) and full nested DTO->model mapping. */
class ShopDtosCoverageTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun platformsAreLabelOnlyWithShopifyCatalog() {
        assertEquals("shopify", UcpPlatforms.SHOPIFY.id)
        assertTrue(UcpPlatforms.SHOPIFY.globalCatalogMcp!!.contains("catalog.shopify.com"))
        assertEquals(null, UcpPlatforms.GENERIC.globalCatalogMcp)
        assertEquals(7, UcpPlatforms.ALL.size)
        assertEquals(UcpPlatforms.SHOPIFY, UcpPlatforms.ALL.first())
        assertEquals(listOf("shopify","woocommerce","magento","bigcommerce","salesforce","wix","squarespace"),
            UcpPlatforms.ALL.map { it.id })
        assertEquals(UcpPlatform("x","X").copy(displayName="Y"), UcpPlatform("x","Y"))
        assertNotEquals(UcpPlatform("x","X"), UcpPlatform("y","X"))
    }

    @Test fun checkoutSessionToModelMapsNestedShippingLinksMessagesTotals() {
        val session = ShopCheckoutSession(
            checkoutId="c1", status="incomplete", currency="EUR",
            totals=ShopTotals(ShopMoney(100,"EUR"), ShopMoney(120,"EUR")),
            totalEntries=listOf(ShopTotalEntry("total","",120,"EUR"), ShopTotalEntry("tax","Tax",20,"EUR")),
            continueUrl="https://shop.example/checkout", expiresAt="2030-01-01T00:00:00Z",
            messages=listOf(ShopMessage("error","Bad","$.x","code1","high")),
            review=ShopReview(
                shipping=listOf(ShopShippingMethod("m1", listOf(ShopShippingGroup("g1","opt1",
                    listOf(ShopShippingOption("opt1","Fast","desc", listOf(ShopTotalEntry("total","",20,"EUR")))))))),
                links=listOf(ShopLegalLink("terms","Terms","https://shop.example/terms")),
                browserOnlyReason="reason", availableShippingLineIds=listOf("l1")),
            submittedLines=listOf(ShopSubmittedLine("v1",2)), trackingToken="tok")
        val model = session.toModel()
        assertEquals("c1", model.checkoutId); assertEquals(Money(120,"EUR"), model.totals.total)
        assertEquals("total", model.totalEntries[0].label) // blank display_text falls back to type
        assertEquals("Tax", model.totalEntries[1].label)
        assertEquals("Bad", model.messages.single().text); assertEquals("$.x", model.messages.single().path)
        val opt = model.review.shipping.single().groups.single().options.single()
        assertEquals("Fast", opt.title); assertEquals(Money(20,"EUR"), opt.totals.single().money)
        assertEquals("opt1", model.review.shipping.single().groups.single().selectedOptionId)
        assertEquals("Terms", model.review.links.single().title)
        assertEquals("reason", model.review.browserOnlyReason); assertEquals(listOf("l1"), model.review.availableShippingLineIds)
        assertEquals("v1", model.submittedLines.single().variantId); assertEquals("tok", model.trackingToken)
    }

    @Test fun productAndMerchantToModelMapAllFields() {
        val p = ShopProduct(id="p1", title="Shoe", description="d", priceRange=ShopMoney(100), priceRangeMax=ShopMoney(200),
            url="https://s.example/p", platformId="generic", shopDomain="s.example",
            images=listOf(ShopProductImage("https://i.example/x","alt")),
            variants=listOf(ShopVariant("v1","T",ShopMoney(100),true,listOf("9"),"sku","Store","s.example",
                listOf(ShopCatalogOption("Size","9","o1")))), checkoutEligible=true, descriptionHtml="<p>d</p>", storeName="Store")
        val m = p.toModel()
        assertEquals("alt", m.images.single().alt); assertEquals("sku", m.variants.single().sku)
        assertEquals("o1", m.variants.single().selectedOptions.single().id); assertEquals(Money(200), m.priceRangeMax)
        val merch = ShopMerchant("id","Name","d.example","https://d.example","desc","verified","2026-01-01").toModel()
        assertEquals("verified", merch.directoryStatus); assertEquals("2026-01-01", merch.directoryVerifiedAt)
        assertEquals(ShopMerchant().copy(name="N"), ShopMerchant(name="N"))
    }

    @Test fun requestConversionsAndDtoJsonRoundTrips() {
        val line = CartLine(variantId="v1", quantity=3, shopDomain="s.example")
        assertEquals(ShopCheckoutLine("v1",3), line.toRequestLine())
        val inputs = CheckoutInputs(firstName="A", email="a@b.c", options=mapOf("k" to "v"), shippingRequested=true)
        val req = inputs.toRequestInputs()
        assertEquals("A", req.firstName); assertEquals(mapOf("k" to "v"), req.options); assertTrue(req.shippingRequested)
        assertEquals(ShopCheckoutInputs(firstName="A", email="a@b.c", options=mapOf("k" to "v"), shippingRequested=true), req)
        // JSON round-trips exercise serializers for every DTO type.
        assertEquals(req, json.decodeFromString<ShopCheckoutInputs>(json.encodeToString(req)))
        val create = ShopCreateCheckoutRequest("s.example", listOf(ShopCheckoutLine("v1",1)), req, "key1")
        assertEquals(create, json.decodeFromString<ShopCreateCheckoutRequest>(json.encodeToString(create)))
        val update = ShopUpdateCheckoutRequest("s.example","sess", listOf(ShopCheckoutLine("v1",1)), req, "key1")
        assertEquals(update, json.decodeFromString<ShopUpdateCheckoutRequest>(json.encodeToString(update)))
        val statusReq = ShopOrderStatusRequest(listOf(OrderStatusQuery("t".repeat(32), null)))
        assertEquals(statusReq, json.decodeFromString<ShopOrderStatusRequest>(json.encodeToString(statusReq)))
        val statusResp = ShopOrderStatusResponse(listOf(ShopOrderStatus("tok", true, null), ShopOrderStatus()))
        assertEquals(statusResp, json.decodeFromString<ShopOrderStatusResponse>(json.encodeToString(statusResp)))
        val envelope = ShopErrorEnvelope(ShopErrorBody("http","oops", 500L, "https://x.example"))
        assertEquals(envelope, json.decodeFromString<ShopErrorEnvelope>(json.encodeToString(envelope)))
        assertNull(ShopProductResponse().product.priceRangeMax)
        assertEquals(ShopSearchResponse(), ShopSearchResponse(emptyList()))
        assertNotEquals(ShopMoney(1), ShopMoney(2)); assertEquals(ShopMoney(5,"EUR").copy(amount=6), ShopMoney(6,"EUR"))
    }
}
