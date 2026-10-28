package com.vayunmathur.shop.network

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CatalogOptionSelection
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.MerchantListing
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.data.UcpProduct
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request/response DTOs for the first-party proxy (api.maproj.org, shop/v1 endpoints).
 *
 * The server owns all UCP behavior (discovery, REST/MCP transport, dated
 * projections, idempotency ledger); the client sends plain cart/buyer data
 * and decodes display models. Field names mirror the server JSON exactly.
 */
@Serializable
data class ShopCheckoutLine(
    val variantId: String,
    val quantity: Int,
)

@Serializable
data class ShopCheckoutInputs(
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
)

@Serializable
data class ShopCreateCheckoutRequest(
    val shop: String,
    val lines: List<ShopCheckoutLine>,
    val inputs: ShopCheckoutInputs,
    val idempotencyKey: String,
)

@Serializable
data class ShopUpdateCheckoutRequest(
    val shop: String,
    val sessionId: String,
    val lines: List<ShopCheckoutLine>,
    val inputs: ShopCheckoutInputs,
    val idempotencyKey: String,
)

@Serializable
data class ShopSubmittedLine(
    val variantId: String = "",
    val quantity: Int = 0,
)

@Serializable
data class ShopMoney(
    val amount: Long = 0,
    val currency: String = "USD",
)

@Serializable
data class ShopTotalEntry(
    val type: String = "",
    @SerialName("display_text") val displayText: String = "",
    val amount: Long = 0,
    val currency: String = "USD",
)

@Serializable
data class ShopMessage(
    val type: String = "",
    val text: String = "",
    val path: String? = null,
    val code: String? = null,
    val severity: String? = null,
)

@Serializable
data class ShopShippingOption(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val totals: List<ShopTotalEntry> = emptyList(),
)

@Serializable
data class ShopShippingGroup(
    val id: String = "",
    @SerialName("selected_option_id") val selectedOptionId: String? = null,
    val options: List<ShopShippingOption> = emptyList(),
)

@Serializable
data class ShopShippingMethod(
    val id: String = "",
    val groups: List<ShopShippingGroup> = emptyList(),
)

@Serializable
data class ShopLegalLink(
    val type: String = "",
    val title: String = "",
    val url: String = "",
)

@Serializable
data class ShopReview(
    val shipping: List<ShopShippingMethod> = emptyList(),
    val links: List<ShopLegalLink> = emptyList(),
    val browserOnlyReason: String? = null,
    val availableShippingLineIds: List<String> = emptyList(),
)

@Serializable
data class ShopTotals(
    val subtotal: ShopMoney = ShopMoney(),
    val total: ShopMoney = ShopMoney(),
)

@Serializable
data class ShopCheckoutSession(
    val checkoutId: String = "",
    val status: String = "",
    val currency: String = "USD",
    val totals: ShopTotals = ShopTotals(),
    val totalEntries: List<ShopTotalEntry> = emptyList(),
    val continueUrl: String = "",
    val expiresAt: String? = null,
    val messages: List<ShopMessage> = emptyList(),
    val version: String = "2026-08-25",
    val review: ShopReview = ShopReview(),
    val submittedLines: List<ShopSubmittedLine> = emptyList(),
)

@Serializable
data class ShopProductImage(
    val url: String = "",
    val alt: String? = null,
)

@Serializable
data class ShopCatalogOption(
    val name: String = "",
    val label: String = "",
    val id: String? = null,
)

@Serializable
data class ShopVariant(
    val id: String = "",
    val title: String = "",
    val price: ShopMoney = ShopMoney(),
    val available: Boolean = false,
    val optionValues: List<String> = emptyList(),
    val sku: String? = null,
    val storeName: String = "",
    val shopDomain: String = "",
    val selectedOptions: List<ShopCatalogOption> = emptyList(),
)

@Serializable
data class ShopProduct(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val priceRange: ShopMoney = ShopMoney(),
    val priceRangeMax: ShopMoney? = null,
    val url: String = "",
    val platformId: String = "shopify",
    val shopDomain: String = "",
    val images: List<ShopProductImage> = emptyList(),
    val variants: List<ShopVariant> = emptyList(),
    val checkoutEligible: Boolean = false,
    val descriptionHtml: String = "",
    val storeName: String = "",
)

@Serializable
data class ShopMerchant(
    val id: String = "",
    val name: String = "",
    val domain: String = "",
    val siteUrl: String = "",
    val description: String = "",
    val directoryStatus: String = "unknown",
    val directoryVerifiedAt: String? = null,
)

@Serializable
data class ShopSearchResponse(
    val products: List<ShopProduct> = emptyList(),
)

@Serializable
data class ShopProductResponse(
    val product: ShopProduct = ShopProduct(),
)

@Serializable
data class ShopMerchantsResponse(
    val merchants: List<ShopMerchant> = emptyList(),
)

@Serializable
data class ShopErrorBody(
    val code: String = "",
    val message: String = "",
    val retryAfterMs: Long? = null,
)

@Serializable
data class ShopErrorEnvelope(
    val error: ShopErrorBody = ShopErrorBody(),
)

/** Display-model conversions: server DTOs to the UI models. */
internal fun ShopMoney.toModel(): Money = Money(amount, currency)

internal fun ShopProduct.toModel(): UcpProduct = UcpProduct(
    id = id,
    title = title,
    description = description,
    priceRange = priceRange.toModel(),
    priceRangeMax = priceRangeMax?.toModel(),
    url = url,
    platformId = platformId,
    shopDomain = shopDomain,
    images = images.map { com.vayunmathur.shop.data.ProductImage(it.url, it.alt) },
    variants = variants.map { variant ->
        com.vayunmathur.shop.data.ProductVariant(
            id = variant.id,
            title = variant.title,
            price = variant.price.toModel(),
            available = variant.available,
            optionValues = variant.optionValues,
            sku = variant.sku,
            storeName = variant.storeName,
            shopDomain = variant.shopDomain,
            selectedOptions = variant.selectedOptions.map {
                CatalogOptionSelection(it.name, it.label, it.id)
            },
        )
    },
    checkoutEligible = checkoutEligible,
    descriptionHtml = descriptionHtml,
    storeName = storeName,
)

internal fun ShopMerchant.toModel(): MerchantListing = MerchantListing(
    id = id,
    name = name,
    domain = domain,
    siteUrl = siteUrl,
    description = description,
    directoryStatus = directoryStatus,
    directoryVerifiedAt = directoryVerifiedAt,
)

internal fun ShopCheckoutSession.toModel(): UcpCheckout = UcpCheckout(
    checkoutId = checkoutId,
    status = status,
    currency = currency,
    totals = com.vayunmathur.shop.data.UcpTotals(
        subtotal = totals.subtotal.toModel(),
        total = totals.total.toModel(),
    ),
    totalEntries = totalEntries.map {
        com.vayunmathur.shop.data.UcpTotalEntry(it.type, it.displayText.ifBlank { it.type }, it.amount.toModel(it.currency))
    },
    continueUrl = continueUrl,
    expiresAt = expiresAt,
    messages = messages.map {
        com.vayunmathur.shop.data.UcpMessage(it.type, it.text, it.path, it.code, it.severity)
    },
    version = version,
    review = com.vayunmathur.shop.data.CheckoutReview(
        shipping = review.shipping.map { method ->
            com.vayunmathur.shop.data.CheckoutShippingMethod(
                id = method.id,
                groups = method.groups.map { group ->
                    com.vayunmathur.shop.data.CheckoutShippingGroup(
                        id = group.id,
                        selectedOptionId = group.selectedOptionId,
                        options = group.options.map { option ->
                            com.vayunmathur.shop.data.CheckoutShippingOption(
                                id = option.id,
                                title = option.title,
                                description = option.description,
                                totals = option.totals.map { total ->
                                    com.vayunmathur.shop.data.UcpTotalEntry(
                                        total.type,
                                        total.displayText.ifBlank { total.type },
                                        total.amount.toModel(total.currency),
                                    )
                                },
                            )
                        },
                    )
                },
            )
        },
        links = review.links.map {
            com.vayunmathur.shop.data.CheckoutLegalLink(it.type, it.title, it.url)
        },
        browserOnlyReason = review.browserOnlyReason,
        availableShippingLineIds = review.availableShippingLineIds,
    ),
    submittedLines = submittedLines.map {
        com.vayunmathur.shop.data.SubmittedLine(it.variantId, it.quantity)
    },
)

private fun Long.toModel(currency: String): Money = Money(this, currency)

internal fun CartLine.toRequestLine(): ShopCheckoutLine =
    ShopCheckoutLine(variantId = variantId, quantity = quantity)

internal fun CheckoutInputs.toRequestInputs(): ShopCheckoutInputs = ShopCheckoutInputs(
    firstName = firstName,
    lastName = lastName,
    email = email,
    phone = phone,
    street = street,
    extendedAddress = extendedAddress,
    city = city,
    region = region,
    country = country,
    postalCode = postalCode,
    options = options,
    shippingRequested = shippingRequested,
)
