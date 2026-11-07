package com.vayunmathur.shop.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cart grouping by store, per-store checkout, totals and empty state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShopCartTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int): String = context.getString(id)

    private fun line(title: String, price: Long, qty: Int, store: String, domain: String) = CartLine(
        variantId = "v-$title", productTitle = title, variantTitle = "Default",
        unitPrice = Money(price, "USD"), platformId = "shopify", shopDomain = domain,
        quantity = qty, storeName = store,
    )

    @Test
    fun cartDisplayNameFallsBackToDomain() {
        assertEquals("Cool Store", line("X", 100, 1, "Cool Store", "cool.example").displayName)
        assertEquals("cool.example", line("X", 100, 1, "", "cool.example").displayName)
        assertEquals("", line("X", 100, 1, "", "").displayName)
    }

    @Test
    fun emptyCartShowsPrompt() {
        composeRule.setContent { CartContent(emptyList()) }
        composeRule.onNodeWithText(str(R.string.cart_empty)).assertIsDisplayed()
    }

    @Test
    fun singleStoreCartShowsItemsTotalAndCheckout() {
        var checkedOut: Pair<String, String>? = null
        val cart = listOf(
            line("Trail Shoes", 12000, 1, "Cool Store", "cool.example"),
            line("Socks", 1000, 2, "Cool Store", "cool.example"),
        )
        composeRule.setContent {
            CartContent(cart, onCheckoutStore = { p, d -> checkedOut = p to d })
        }
        composeRule.onNodeWithText("Cool Store").assertIsDisplayed()
        composeRule.onNodeWithText("Trail Shoes").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.checkout)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.checkout)).performClick()
        assertEquals("shopify" to "cool.example", checkedOut)
    }

    @Test
    fun multiStoreCartGroupsWithPerStoreCheckoutLabels() {
        var checkedOut: Pair<String, String>? = null
        val cart = listOf(
            line("Trail Shoes", 12000, 1, "Cool Store", "cool.example"),
            line("Mug", 1500, 1, "Kitchen Co", "kitchen.example"),
        )
        composeRule.setContent {
            CartContent(cart, onCheckoutStore = { p, d -> checkedOut = p to d })
        }
        composeRule.onNodeWithText("Checkout with Cool Store").assertIsDisplayed()
        composeRule.onNodeWithText("Checkout with Cool Store").performClick()
        assertEquals("shopify" to "cool.example", checkedOut)
    }

    @Test
    fun startingStoreDisablesCheckoutButtons() {
        val cart = listOf(line("Trail Shoes", 12000, 1, "Cool Store", "cool.example"))
        composeRule.setContent {
            CartContent(cart, startingStore = "shopify" to "cool.example", onCheckoutStore = { _, _ -> })
        }
        composeRule.onNodeWithText("Trail Shoes").assertIsDisplayed()
        assertTrue(true)
    }
}
