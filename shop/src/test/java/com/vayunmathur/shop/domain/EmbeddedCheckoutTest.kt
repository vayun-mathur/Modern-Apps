package com.vayunmathur.shop.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedCheckoutTest {
    @Test
    fun embeddedUrlAppendsEcParams() {
        assertEquals(
            "https://allbirds.com/cart/c/abc?key=k&ec_version=2026-08-25&ec_color_scheme=dark&ec_delegate=window.open",
            EmbeddedCheckout.embeddedUrl("https://allbirds.com/cart/c/abc?key=k", dark = true),
        )
        assertEquals(
            "https://allbirds.com/checkout?ec_version=2026-08-25&ec_color_scheme=light&ec_delegate=window.open",
            EmbeddedCheckout.embeddedUrl("https://allbirds.com/checkout", dark = false),
        )
        assertNull(EmbeddedCheckout.embeddedUrl("http://allbirds.com/checkout", dark = false))
        assertNull(EmbeddedCheckout.embeddedUrl("https://localhost/checkout", dark = false))
    }

    @Test
    fun readyIsAnsweredWithMatchingIdAndVersion() {
        val reply = EmbeddedCheckout.handle("""{"jsonrpc":"2.0","id":"ready_1","method":"ec.ready","params":{"delegate":["window.open"]}}""")
        val json = Json.parseToJsonElement(assertIs<EcAction.Reply>(reply).json).jsonObject
        assertEquals("ready_1", json["id"]!!.jsonPrimitive.content)
        val ucp = json["result"]!!.jsonObject["ucp"]!!.jsonObject
        assertEquals("2026-08-25", ucp["version"]!!.jsonPrimitive.content)
        assertEquals("success", ucp["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun windowOpenAcceptsOnlyPublicHttps() {
        val open = EmbeddedCheckout.handle("""{"jsonrpc":"2.0","id":7,"method":"ec.window.open_request","params":{"url":"https://allbirds.com/policies/privacy"}}""")
        assertEquals(EcAction.OpenWindow(JsonPrimitive(7), "https://allbirds.com/policies/privacy"), open)
        listOf("javascript:alert(1)", "intent://x#Intent;end", "https://localhost/x").forEach { url ->
            val rejected = EmbeddedCheckout.handle("""{"jsonrpc":"2.0","id":8,"method":"ec.window.open_request","params":{"url":"$url"}}""")
            assertTrue(assertIs<EcAction.Reply>(rejected).json.contains("window_open_rejected_error"), url)
        }
        assertTrue(EmbeddedCheckout.windowOpenResult(JsonPrimitive(7), ok = true).contains("\"success\""))
    }

    @Test
    fun completionKeepsOnlyOrderReferenceAndTotal() {
        val message = """{"jsonrpc":"2.0","method":"ec.complete","params":{"checkout":{"id":"checkout_123",
            "buyer":{"email":"ada@example.com","first_name":"Ada"},
            "totals":[{"type":"subtotal","amount":9800},{"type":"total","amount":10584}],
            "order":{"id":"ord_1","permalink_url":"https://allbirds.com/orders/ord_1"}}}}"""
        val done = assertIs<EcAction.Completed>(EmbeddedCheckout.handle(message))
        assertEquals(EcAction.Completed("ord_1", "https://allbirds.com/orders/ord_1", 10584), done)
        assertFalse(done.toString().contains("ada@example.com"))
    }

    @Test
    fun unknownRequestsGetMethodNotFoundAndNoiseIsIgnored() {
        val reply = EmbeddedCheckout.handle("""{"jsonrpc":"2.0","id":"p1","method":"ec.payment.credential_request","params":{}}""")
        assertTrue(assertIs<EcAction.Reply>(reply).json.contains("-32601"))
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""{"jsonrpc":"2.0","method":"ec.start","params":{}}"""))
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("not json"))
        assertEquals(EcAction.Ignore, EmbeddedCheckout.handle("""{"jsonrpc":"2.0","result":{}}"""))
        assertIs<EcAction.Failed>(EmbeddedCheckout.handle("""{"jsonrpc":"2.0","method":"ec.error","params":{"error":{"message":"Session expired"}}}"""))
    }
}
