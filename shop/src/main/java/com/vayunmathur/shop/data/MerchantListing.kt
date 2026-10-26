package com.vayunmathur.shop.data

/** Directory metadata is a discovery lead, never merchant access or payment authorization. */
data class MerchantListing(
    val id: String,
    val name: String,
    val domain: String,
    val siteUrl: String,
    val description: String = "",
    val directoryStatus: String = "unknown",
    val directoryVerifiedAt: String? = null,
)
