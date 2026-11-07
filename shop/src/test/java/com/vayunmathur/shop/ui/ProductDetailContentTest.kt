package com.vayunmathur.shop.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.shop.platform.testProduct
import com.vayunmathur.shop.platform.testVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp")
class ProductDetailContentTest {

    @get:Rule
    val rule = createComposeRule()

    private data class Callbacks(
        var selected: String? = null,
        var quantity: Int? = null,
        var retries: Int = 0,
        var adds: Int = 0,
        var buys: Int = 0,
        val urls: MutableList<String> = mutableListOf(),
    )

    private fun setContent(
        product: com.vayunmathur.shop.data.UcpProduct = testProduct(),
        selectedVariantId: String = "v1",
        quantity: Int = 1,
        loading: Boolean = false,
        verified: Boolean = true,
        failure: String? = null,
        startingCheckout: Boolean = false,
        cb: Callbacks = Callbacks(),
    ): Callbacks {
        rule.setContent {
            DynamicTheme {
                ProductDetailContent(
                    product = product,
                    selectedVariantId = selectedVariantId,
                    quantity = quantity,
                    loading = loading,
                    verified = verified,
                    failure = failure,
                    onSelectVariant = { cb.selected = it },
                    onQuantityChange = { cb.quantity = it },
                    onRetry = { cb.retries++ },
                    onAddToCart = { cb.adds++ },
                    onBuyNow = { cb.buys++ },
                    onOpenUrl = { cb.urls += it },
                    startingCheckout = startingCheckout,
                )
            }
        }
        return cb
    }

    @Test
    fun `verified in-stock product can be added and bought`() {
        val cb = setContent()
        rule.onNodeWithText("Trail Store").assertIsDisplayed()
        rule.onNodeWithText("$129.99").assertIsDisplayed()
        rule.onNodeWithText("Add to cart").assertIsEnabled()
        rule.onNodeWithText("Add to cart").performClick()
        rule.onNodeWithText("Buy now").performClick()
        assertEquals(1, cb.adds)
        assertEquals(1, cb.buys)
    }

    @Test
    fun `quantity stepper reports clamped changes`() {
        val cb = setContent(quantity = 1)
        rule.onNodeWithContentDescription("Decrease quantity").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Increase quantity").performClick()
        assertEquals(2, cb.quantity)
    }

    @Test
    fun `quantity stepper at the cap cannot increase`() {
        val cb = setContent(quantity = 99)
        rule.onNodeWithContentDescription("Increase quantity").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Decrease quantity").performClick()
        assertEquals(98, cb.quantity)
    }

    @Test
    fun `loading disables purchase and explains why`() {
        val cb = setContent(loading = true, verified = false)
        rule.onNodeWithText("Checking live price and availability...").assertIsDisplayed()
        rule.onNodeWithText("Add to cart").assertIsNotEnabled()
        rule.onNodeWithText("Add to cart").performClick()
        assertEquals(0, cb.adds, "a disabled button must not fire its callback")
    }

    @Test
    fun `unverified product offers a retry and cannot be added`() {
        val cb = setContent(verified = false, failure = "The store no longer lists this exact variant. Cart actions are disabled.")
        rule.onNodeWithText("The store no longer lists this exact variant. Cart actions are disabled.").assertIsDisplayed()
        rule.onNodeWithText("Add to cart").assertIsNotEnabled()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, cb.retries)
    }

    @Test
    fun `out of stock variant says so and cannot be added`() {
        setContent(product = testProduct(variants = listOf(testVariant(available = false))))
        rule.onNodeWithText("This variant is out of stock.").assertIsDisplayed()
        rule.onNodeWithText("Add to cart").assertIsNotEnabled()
    }

    @Test
    fun `multiple variants render a picker that selects available options`() {
        val cb = setContent(
            product = testProduct(
                variants = listOf(
                    testVariant(id = "v1", title = "9 / Blue"),
                    testVariant(id = "v2", title = "10 / Red", price = 13999),
                    testVariant(id = "v3", title = "11 / Green", available = false),
                ),
            ),
        )
        rule.onNodeWithText("9 / Blue").assertIsDisplayed()
        rule.onNodeWithText("Selected").assertIsDisplayed()
        rule.onNodeWithText("Out of stock").assertIsDisplayed()
        // Two enabled Select buttons (v2) plus the selected one disabled; click the v2 row's.
        rule.onAllNodesWithText("Select")[0].performClick()
        assertEquals("v2", cb.selected)
    }

    @Test
    fun `ineligible product links out to the merchant site`() {
        val cb = setContent(product = testProduct(checkoutEligible = false))
        rule.onNodeWithText("View on merchant site").assertIsDisplayed()
        rule.onNodeWithText("Completes on the merchant site.").assertIsDisplayed()
        rule.onNodeWithText("View on merchant site").performClick()
        assertEquals(listOf("https://shop.example.com/products/trail-runner"), cb.urls)
    }

    @Test
    fun `description renders as text`() {
        setContent(product = testProduct().copy(descriptionHtml = "<p>Waterproof <b>leather</b> boots</p>", description = ""))
        rule.onNodeWithText("Waterproof leather boots", substring = true).assertIsDisplayed()
    }

    @Test
    fun `starting checkout replaces the buy label with progress`() {
        setContent(startingCheckout = true)
        rule.onNodeWithText("Buy now").assertDoesNotExist()
    }

    @Test
    fun `store falls back to the domain when no name is known`() {
        setContent(product = testProduct().copy(storeName = "", variants = listOf(testVariant(storeName = ""))))
        rule.onNodeWithText("shop.example.com").assertIsDisplayed()
        assertTrue(true)
    }
}
