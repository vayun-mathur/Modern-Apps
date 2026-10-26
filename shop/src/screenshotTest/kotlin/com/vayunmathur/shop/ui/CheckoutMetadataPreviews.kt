package com.vayunmathur.shop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.CheckoutLegalLink
import com.vayunmathur.shop.data.CheckoutReview
import com.vayunmathur.shop.data.CheckoutShippingGroup
import com.vayunmathur.shop.data.CheckoutShippingMethod
import com.vayunmathur.shop.data.CheckoutShippingOption
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.NativeCheckoutState
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.data.UcpTotalEntry
import com.vayunmathur.shop.data.UcpTotals

/** Synthetic, immutable review data. No ViewModel, initialization, gateway or network is used. */
class CheckoutMetadataPreviews {
    private val buyer = CheckoutInputs(
        firstName = "Alex", lastName = "Example", email = "alex@example.com", phone = "+12025550123",
        street = "123 Example Street", city = "San Francisco", region = "CA", country = "US", postalCode = "94107",
        options = mapOf("fixture-shipping/fixture-group" to "fixture-standard"),
    )
    private val totals = listOf(
        UcpTotalEntry("subtotal", "Items", Money(12999, "USD")),
        UcpTotalEntry("fulfillment", "Shipping", Money(500, "USD")),
        UcpTotalEntry("tax", "Tax", Money(1080, "USD")),
        UcpTotalEntry("total", "Total", Money(14579, "USD")),
    )
    private val state = NativeCheckoutState(
        platformId = "generic", shopDomain = "trail.example", inputs = buyer, submittedInputs = buyer,
        checkout = UcpCheckout(
            checkoutId = "fixture-checkout", status = "ready_for_complete", currency = "USD",
            totals = UcpTotals(subtotal = totals.first().money, shipping = totals[1].money, tax = totals[2].money, total = totals.last().money),
            totalEntries = totals, expiresAt = "2026-10-27T00:00:00Z",
            review = CheckoutReview(
                shipping = listOf(CheckoutShippingMethod("fixture-shipping", listOf(
                    CheckoutShippingGroup("fixture-group", "fixture-standard", listOf(
                        CheckoutShippingOption("fixture-standard", "Standard shipping", "Arrives in 3-5 business days", listOf(totals[1])),
                    )),
                ))),
                links = listOf(CheckoutLegalLink("terms_of_service", "Merchant terms", "https://trail.example/terms")),
            ),
        ),
    )

    @PreviewTest
    @Preview(name = "4-checkout", group = "phone", widthDp = 360, heightDp = 780)
    @Composable
    fun Preview4Checkout() {
        DynamicTheme(darkTheme = false) {
            AppScaffold(title = "Checkout review", scrollBehavior = appBarScrollBehavior()) { padding ->
                CheckoutReviewContent(
                    state = state,
                    editable = true,
                    onInputsChange = {},
                    onOpenUrl = {},
                    onRefresh = {},
                    modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                )
            }
        }
    }
}
