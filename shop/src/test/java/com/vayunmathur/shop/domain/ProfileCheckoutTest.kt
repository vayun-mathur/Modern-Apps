package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.CheckoutReview
import com.vayunmathur.shop.data.NativeCheckoutState
import com.vayunmathur.shop.data.ShopProfile
import com.vayunmathur.shop.data.UcpMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileCheckoutTest {
    private val contact = ShopProfile(firstName = "Ada", lastName = "Lovelace", email = "ada@example.com", phone = "+14155550123")
    private val withAddress = contact.copy(street = "1 Main St", city = "Austin", region = "TX", country = "US", postalCode = "78701")
    private val noShipping = NativeCheckoutFixtures.checkout(review = CheckoutReview())

    @Test fun requiredFieldsGateProfile() {
        assertNotNull(ShopProfile().validationError())
        assertNotNull(contact.copy(phone = "").validationError())
        assertNotNull(contact.copy(phone = "4155550123").validationError())
        assertNotNull(contact.copy(street = "1 Main St").validationError())
        assertNull(contact.validationError())
        assertNull(withAddress.validationError())
        assertFalse(withAddress.toString().contains("Ada"))
    }

    @Test fun createCarriesTheWholeProfile() {
        val inputs = ProfileCheckout.inputs(withAddress, NativeCheckoutState("generic", "shop.example"))
        assertEquals("Ada", inputs.firstName)
        assertEquals("Lovelace", inputs.lastName)
        assertEquals("1 Main St", inputs.street)
        assertEquals("78701", inputs.postalCode)
        assertTrue(inputs.shippingRequested)
        assertNull(inputs.validationError())
    }

    @Test fun deliveryAddressRequiredSendsSavedAddress() {
        val checkout = noShipping.copy(messages = listOf(UcpMessage("error", "Add an address", null, "delivery_address_required", "recoverable")))
        val state = NativeCheckoutState("generic", "shop.example", checkout = checkout, inputs = CheckoutInputs(options = mapOf("m/g" to "express")))
        val inputs = ProfileCheckout.inputs(withAddress, state)
        assertTrue(inputs.shippingRequested)
        assertEquals("1 Main St", inputs.street)
        assertEquals(mapOf("m/g" to "express"), inputs.options)
        assertNull(inputs.validationError())
    }

    @Test fun noAddressSentWhenProfileHasNone() {
        val shipping = NativeCheckoutState("generic", "shop.example", checkout = NativeCheckoutFixtures.checkout())
        val inputs = ProfileCheckout.inputs(contact, shipping)
        assertFalse(inputs.hasAddress)
        assertFalse(inputs.shippingRequested)
    }
}
