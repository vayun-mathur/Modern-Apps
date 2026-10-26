package com.vayunmathur.shop.data

/** Ephemeral form data: intentionally not serializable or saved in instance state. */
data class CheckoutInputs(
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val phone: String = "",
    val street: String = "",
    val extendedAddress: String = "",
    val city: String = "",
    val region: String = "",
    val country: String = "",
    val postalCode: String = "",
    val options: Map<String, String> = emptyMap(),
    val shippingRequested: Boolean = false,
) {
    val hasAddress: Boolean get() = listOf(street, extendedAddress, city, region, country, postalCode).any { it.isNotBlank() }
    fun validationError(): String? = when {
        email.isNotBlank() && !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email) -> "Enter a valid email address."
        phone.isNotBlank() && !Regex("^\\+[1-9][0-9]{1,14}$").matches(phone) -> "Use an international phone number, such as +14155550123."
        shippingRequested && !hasAddress -> "Provide an address for merchant shipping."
        hasAddress && (street.isBlank() || city.isBlank() || !Regex("^[A-Z]{2}$").matches(country)) -> "Provide a street, city and two-letter uppercase country code."
        hasAddress && country in setOf("US", "CA") && (region.isBlank() || postalCode.isBlank()) -> "Provide a state or province and postal code."
        else -> null
    }
    override fun toString(): String = "CheckoutInputs(redacted)"
}

data class CheckoutLegalLink(val type: String, val title: String, val url: String)
data class CheckoutShippingOption(val id: String, val title: String, val description: String, val totals: List<UcpTotalEntry>)
data class CheckoutShippingGroup(val id: String, val selectedOptionId: String?, val options: List<CheckoutShippingOption>)
data class CheckoutShippingMethod(val id: String, val groups: List<CheckoutShippingGroup>)
data class CheckoutReview(
    val shipping: List<CheckoutShippingMethod> = emptyList(),
    val links: List<CheckoutLegalLink> = emptyList(),
    val browserOnlyReason: String? = null,
    val availableShippingLineIds: List<String> = emptyList(),
)

enum class CheckoutPhase { Preparing, Reviewing, Updating, BrowserRequired, Error, Expired, Uncertain }
data class NativeCheckoutState(
    val platformId: String,
    val shopDomain: String,
    val phase: CheckoutPhase = CheckoutPhase.Reviewing,
    val checkout: UcpCheckout? = null,
    val message: String? = null,
    val inputs: CheckoutInputs = CheckoutInputs(),
    val submittedInputs: CheckoutInputs? = null,
    val submittedLines: List<CartLine> = emptyList(),
) {
    val busy: Boolean get() = phase == CheckoutPhase.Preparing || phase == CheckoutPhase.Updating
    val completionEnabled: Boolean get() = false
}
