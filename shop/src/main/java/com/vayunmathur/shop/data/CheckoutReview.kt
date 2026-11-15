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
    val hasAddress: Boolean get() =
        listOf(street, extendedAddress, city, region, country, postalCode).any { it.isNotBlank() }

    fun validationError(): String? = when {
        email.isNotBlank() && !EMAIL_REGEX.matches(email) -> "Enter a valid email address."
        phone.isNotBlank() && !PHONE_REGEX.matches(phone) ->
            "Use an international phone number, such as +14155550123."
        shippingRequested && !hasAddress -> "Provide an address for merchant shipping."
        hasAddress && addressError() != null -> addressError()
        else -> null
    }

    /** The address-specific validation error, or null when the address is usable. */
    private fun addressError(): String? = when {
        street.isBlank() || city.isBlank() || !COUNTRY_REGEX.matches(country) ->
            "Provide a street, city and two-letter uppercase country code."
        country in setOf("US", "CA") && (region.isBlank() || postalCode.isBlank()) ->
            "Provide a state or province and postal code."
        else -> null
    }

    private companion object {
        val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        val PHONE_REGEX = Regex("^\\+[1-9][0-9]{1,14}$")
        val COUNTRY_REGEX = Regex("^[A-Z]{2}$")
    }
    override fun toString(): String = "CheckoutInputs(redacted)"
}

/** Immutable value describing checkout legal link; carries type, title, url. */
data class CheckoutLegalLink(val type: String, val title: String, val url: String)
/** Immutable value describing checkout shipping option; carries id, title, description, totals. */
data class CheckoutShippingOption(
    val id: String,
    val title: String,
    val description: String,
    val totals: List<UcpTotalEntry>,
)
/** Immutable value describing checkout shipping group; carries id, selectedOptionId, options. */
data class CheckoutShippingGroup(
    val id: String,
    val selectedOptionId: String?,
    val options: List<CheckoutShippingOption>,
)
/** Immutable value describing checkout shipping method; carries id, groups. */
data class CheckoutShippingMethod(
    val id: String,
    val groups: List<CheckoutShippingGroup>,
)
/** Immutable value describing checkout review. */
data class CheckoutReview(
    val shipping: List<CheckoutShippingMethod> = emptyList(),
    val links: List<CheckoutLegalLink> = emptyList(),
    val browserOnlyReason: String? = null,
    val availableShippingLineIds: List<String> = emptyList(),
)

/** Kinds of checkout phase. */
enum class CheckoutPhase { Preparing, Reviewing, Updating, BrowserRequired, Error, Expired, Uncertain }
/** Immutable value describing native checkout state. */
data class NativeCheckoutState(
    val platformId: String,
    val shopDomain: String,
    val phase: CheckoutPhase = CheckoutPhase.Reviewing,
    val checkout: UcpCheckout? = null,
    val message: String? = null,
    /** Merchant handoff URL from a rejected write, when no session exists to carry one. */
    val handoffUrl: String? = null,
    val inputs: CheckoutInputs = CheckoutInputs(),
    val submittedInputs: CheckoutInputs? = null,
    val submittedLines: List<CartLine> = emptyList(),
) {
    val busy: Boolean get() = phase == CheckoutPhase.Preparing || phase == CheckoutPhase.Updating
    val completionEnabled: Boolean get() = false
}
