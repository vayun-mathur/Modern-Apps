package com.vayunmathur.shop.network

/** Labels only. Merchant endpoint and access permissions must be discovered, not inferred. */
data class UcpPlatform(val id: String, val displayName: String, val globalCatalogMcp: String? = null)

object UcpPlatforms {
    val SHOPIFY = UcpPlatform("shopify", "Shopify", "https://catalog.shopify.com/api/ucp/mcp")
    val GENERIC = UcpPlatform("generic", "UCP merchant")
    val ALL = listOf(
        SHOPIFY,
        UcpPlatform("woocommerce", "WooCommerce"),
        UcpPlatform("magento", "Adobe Commerce / Magento"),
        UcpPlatform("bigcommerce", "BigCommerce"),
        UcpPlatform("salesforce", "Salesforce Commerce Cloud"),
        UcpPlatform("wix", "Wix"),
        UcpPlatform("squarespace", "Squarespace"),
    )
}
