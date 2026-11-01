package com.vayunmathur.shop.data

/** An open Embedded Checkout session. In-memory only; [url] carries a merchant session key. */
data class EmbeddedCheckoutRequest(
    val platformId: String,
    val shopDomain: String,
    val storeName: String,
    val url: String,
    val browserUrl: String,
) {
    override fun toString(): String = "EmbeddedCheckoutRequest($shopDomain)"
}
