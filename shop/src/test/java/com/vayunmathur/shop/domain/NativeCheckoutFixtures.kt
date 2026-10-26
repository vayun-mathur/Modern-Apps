package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.network.UcpCheckoutApi
import com.vayunmathur.shop.network.UcpService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal object NativeCheckoutFixtures {
    val capabilities = setOf("dev.ucp.shopping.checkout", "dev.ucp.shopping.fulfillment")
    val lines = listOf(CartLine(variantId = "variant-1", productTitle = "Test product", platformId = "generic", shopDomain = "shop.example", quantity = 2))
    fun service(version: String = "2026-08-25", transport: String = "rest") = UcpService(version, transport, "https://shop.example/ucp", capabilities, emptySet())
    fun checkout(version: String = "2026-08-25", status: String = "incomplete", itemId: String = "variant-1", expiry: String = "2026-10-27T00:00:00Z", methodType: String = "shipping"): UcpCheckout =
        UcpCheckoutApi.parseCheckout(response(version, status, itemId, expiry, methodType), version, capabilities)

    fun response(version: String = "2026-08-25", status: String = "incomplete", itemId: String = "variant-1", expiry: String = "2026-10-27T00:00:00Z", methodType: String = "shipping"): JsonObject = Json.parseToJsonElement("""
        {"ucp":{"version":"$version","capabilities":{"dev.ucp.shopping.checkout":[{"version":"$version"}],"dev.ucp.shopping.fulfillment":[{"version":"$version"}]},"payment_handlers":{}},
         "id":"checkout-1","status":"$status","currency":"KWD","expires_at":"$expiry",
         "line_items":[{"id":"merchant-line-1","item":{"id":"$itemId","title":"Test product","price":500},"quantity":2,"totals":[]}],
         "buyer":{"email":"ephemeral@example.com"},
         "totals":[{"type":"subtotal","display_text":"Items","amount":1000},{"type":"fulfillment","display_text":"Shipping","amount":200},{"type":"total","display_text":"Total","amount":1200}],
         "messages":[{"type":"error","severity":"recoverable","code":"missing","path":"$.buyer.phone_number","content":"Add phone"}],
         "links":[{"type":"terms_of_service","title":"Terms","url":"https://shop.example/terms"}],
         "fulfillment":{"methods":[{"id":"merchant-shipping","type":"$methodType","line_item_ids":["merchant-line-1"],
           "destinations":[{"id":"merchant-address","type":"shipping_address","street_address":"old"}],"selected_destination_id":"merchant-address",
           "groups":[{"id":"merchant-group","line_item_ids":["merchant-line-1"],"selected_option_id":"standard",
             "options":[{"id":"standard","title":"Standard","totals":[{"type":"total","amount":200}]},{"id":"express","title":"Express","totals":[{"type":"total","amount":400}]}]}]}]}}
    """).jsonObject
}
