package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CheckoutReview
import com.vayunmathur.shop.data.CheckoutShippingGroup
import com.vayunmathur.shop.data.CheckoutShippingMethod
import com.vayunmathur.shop.data.CheckoutShippingOption
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.SubmittedLine
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.data.UcpMessage
import com.vayunmathur.shop.data.UcpTotalEntry
import com.vayunmathur.shop.data.UcpTotals

internal object NativeCheckoutFixtures {
    val lines = listOf(CartLine(variantId = "variant-1", productTitle = "Test product", platformId = "generic", shopDomain = "shop.example", quantity = 2))
    fun checkout(
        status: String = "incomplete",
        submitted: List<SubmittedLine> = listOf(SubmittedLine("variant-1", 2)),
        methodType: String = "shipping",
        review: CheckoutReview? = null,
    ): UcpCheckout = UcpCheckout(
        checkoutId = "checkout-1",
        status = status,
        currency = "KWD",
        totals = UcpTotals(subtotal = Money(1000, "KWD"), total = Money(1200, "KWD")),
        totalEntries = listOf(
            UcpTotalEntry("subtotal", "subtotal", Money(1000, "KWD")),
            UcpTotalEntry("fulfillment", "fulfillment", Money(200, "KWD")),
            UcpTotalEntry("total", "total", Money(1200, "KWD")),
        ),
        expiresAt = "2026-10-27T00:00:00Z",
        continueUrl = "",
        messages = listOf(UcpMessage("error", "Add phone", "$.buyer.phone_number", "missing", "recoverable")),
        version = "2026-08-25",
        review = review ?: CheckoutReview(
            shipping = if (methodType == "shipping") listOf(
                CheckoutShippingMethod(
                    "merchant-shipping",
                    listOf(
                        CheckoutShippingGroup(
                            "merchant-group",
                            "standard",
                            listOf(
                                CheckoutShippingOption("standard", "Standard", "", listOf(UcpTotalEntry("total", "total", Money(200, "KWD")))),
                                CheckoutShippingOption("express", "Express", "", listOf(UcpTotalEntry("total", "total", Money(400, "KWD")))),
                            ),
                        ),
                    ),
                ),
            ) else emptyList(),
            links = listOf(com.vayunmathur.shop.data.CheckoutLegalLink("terms_of_service", "Terms", "https://shop.example/terms")),
            browserOnlyReason = if (methodType == "shipping") null else "This fulfillment method requires the merchant website.",
            availableShippingLineIds = emptyList(),
        ),
        submittedLines = submitted,
    )
}
