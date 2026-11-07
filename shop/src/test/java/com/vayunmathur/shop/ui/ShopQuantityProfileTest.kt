package com.vayunmathur.shop.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.vayunmathur.shop.data.ShopProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Quantity stepper bounds and buyer-profile validation/checkout inputs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShopQuantityProfileTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun quantityStepperIncrementsDecrementsAndShowsValue() {
        var qty = 2
        composeRule.setContent { ProductQuantityRow(quantity = qty, onChange = { qty = it }) }
        composeRule.onNodeWithContentDescription("Quantity: 2").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Increase quantity").performClick()
        assertEquals(3, qty)
        composeRule.onNodeWithContentDescription("Decrease quantity").performClick()
        // Content still renders quantity 2 (state is hoisted); callback value is what matters.
        assertEquals(1, qty)
    }

    @Test
    fun profileValidationRequiresNameEmailPhone() {
        assertEquals(
            "Enter your first name, last name, email and phone number.",
            ShopProfile().validationError(),
        )
        assertEquals(
            "Enter your first name, last name, email and phone number.",
            ShopProfile(firstName = "Ada", lastName = "L", email = "a@b.c").validationError(),
        )
        val ok = ShopProfile(firstName = "Ada", lastName = "Lovelace", email = "ada@example.com", phone = "555-1")
        // Without an address, checkout inputs may still validate (address optional per AGENTS.md);
        // required-field error must be gone.
        val err = ok.validationError()
        assertTrue(err == null || !err.contains("first name"))
        assertEquals("ShopProfile(redacted)", ok.toString())
    }

    @Test
    fun checkoutInputsTrimAndCarryOptions() {
        val profile = ShopProfile(
            firstName = "  Ada ", lastName = " Lovelace ", email = " ada@example.com ",
            phone = " 555 ", street = " 1 Main ", city = " Springfield ", country = "US",
            postalCode = " 01101 ",
        )
        val inputs = profile.checkoutInputs(shippingRequested = true, options = mapOf("ship" to "express"))
        assertEquals("Ada", inputs.firstName)
        assertEquals("ada@example.com", inputs.email)
        assertEquals("1 Main", inputs.street)
        assertEquals("express", inputs.options["ship"])
        assertTrue(inputs.shippingRequested)
        assertTrue(profile.hasAddress || inputs.street.isNotEmpty())
        val bare = ShopProfile(firstName = "A", lastName = "B", email = "a@b.c", phone = "1")
        assertFalse(bare.hasAddress)
        assertNull(bare.checkoutInputs().options["ship"])
    }
}
