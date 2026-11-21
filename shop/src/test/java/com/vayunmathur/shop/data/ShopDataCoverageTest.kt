package com.vayunmathur.shop.data

import com.vayunmathur.shop.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Data-model validation branches, redacted toStrings and route serialization. */
class ShopDataCoverageTest {
    @Test fun checkoutInputsValidationBranches() {
        assertNull(CheckoutInputs().validationError()) // blank email/phone, no shipping
        assertEquals("Enter a valid email address.", CheckoutInputs(email="nope").validationError())
        assertTrue(CheckoutInputs(phone="123").validationError()!!.contains("international"))
        assertEquals("Provide an address for merchant shipping.",
            CheckoutInputs(shippingRequested=true).validationError())
        // Partial address triggers addressError
        assertTrue(CheckoutInputs(street="1 Main").validationError()!!.contains("street, city"))
        assertTrue(CheckoutInputs(street="1 Main", city="Austin", country="US").validationError()!!.contains("state or province"))
        assertTrue(CheckoutInputs(street="1 Main", city="Austin", country="usa").validationError()!!.contains("two-letter"))
        assertNull(CheckoutInputs(street="1 Main", city="Austin", region="TX", country="US", postalCode="78701",
            email="a@b.c", phone="+14155550123").validationError())
        assertNull(CheckoutInputs(street="1 Main", city="Paris", country="FR").validationError())
        assertTrue(CheckoutInputs(street="1 Main", city="Paris", country="FR").hasAddress)
        assertFalse(CheckoutInputs().hasAddress)
        assertEquals("CheckoutInputs(redacted)", CheckoutInputs(email="a@b.c").toString())
    }

    @Test fun nativeStateBusyAndModels() {
        assertTrue(NativeCheckoutState("g","s.example", phase=CheckoutPhase.Preparing).busy)
        assertTrue(NativeCheckoutState("g","s.example", phase=CheckoutPhase.Updating).busy)
        assertFalse(NativeCheckoutState("g","s.example", phase=CheckoutPhase.Reviewing).busy)
        assertFalse(NativeCheckoutState("g","s.example").completionEnabled)
        assertEquals("EmbeddedCheckoutRequest(s.example)",
            EmbeddedCheckoutRequest("g","s.example","Store","https://secret.example/x","https://s.example").toString())
        assertTrue(OrderTracking(status="delivered").isFinal); assertTrue(OrderTracking(status="canceled").isFinal)
        assertFalse(OrderTracking(status="shipped").isFinal)
        assertEquals("ShopProfile(redacted)", ShopProfile(firstName="Ada").toString())
        assertEquals("UcpCheckout(redacted)", UcpCheckout(checkoutId="c").toString())
    }

    @Test fun ucpErrorMessagesForEveryVariant() {
        assertTrue(UcpError.Transport("x").message().contains("reach the store"))
        assertTrue(UcpError.RateLimited(null).message().contains("busy"))
        assertTrue(UcpError.Http(503,"").message().contains("503"))
        assertTrue(UcpError.Malformed("x").message().contains("unexpected"))
        assertEquals("custom reason", UcpError.Unsupported("custom reason").message())
        assertTrue(UcpError.Rpc("x").message().contains("couldn't process"))
        assertNotEquals(UcpError.Transport("a"), UcpError.Transport("b"))
    }

    @Test fun routeEqualityAndSerialization() {
        assertEquals(Route.ProfileSetup, Route.ProfileSetup)
        assertEquals(Route.Search, Route.Search); assertEquals(Route.Cart, Route.Cart)
        assertEquals(Route.Orders, Route.Orders); assertEquals(Route.EmbeddedCheckout("shopify","s.example"), Route.EmbeddedCheckout("shopify","s.example"))
        val detail = Route.ProductDetail("v1","shopify","s.example")
        assertEquals(detail, Route.ProductDetail("v1","shopify","s.example"))
        assertNotEquals(detail, Route.ProductDetail("v2","shopify","s.example"))
        assertEquals("v1", detail.component1()); assertEquals(detail.copy(variantId="v9").variantId, "v9")
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString<Route>(detail)
        assertEquals(detail, json.decodeFromString<Route>(encoded))
        assertEquals(Route.Search, json.decodeFromString<Route>(json.encodeToString<Route>(Route.Search)))
        assertEquals(Route.ProfileSetup, json.decodeFromString<Route>(json.encodeToString<Route>(Route.ProfileSetup)))
    }
}
