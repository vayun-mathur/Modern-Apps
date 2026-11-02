package com.vayunmathur.globalesim.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Generic eSIM storefront DTOs. These mirror our own esim-v1 API on
 * api.maproj.org — they contain no vendor names, URLs, or key material.
 * The reseller vendor is contacted server-side only and is never named here.
 *
 * Product model: one global eSIM per anonymous account. The user tops up
 * USD credit; data deducts from the on-SIM wallet at the local per-country
 * rate. Credit never expires.
 *
 * Wire format is snake_case (server convention) — every field carries an
 * explicit @SerialName so client renames can never silently break the
 * contract (kotlinx.serialization drops unknown keys, so a missing
 * SerialName decodes to defaults with no error).
 */

@Serializable
data class RateDto(
    @SerialName("country_code") val countryCode: String,
    @SerialName("country_name") val countryName: String,
    /** Integer USD cents per GB. Integer math only — no float money. */
    @SerialName("price_per_gb_usd_cents") val pricePerGbUsdCents: Long,
    /** Per-carrier breakdown, cheapest first. */
    @SerialName("networks") val networks: List<NetworkDto> = emptyList(),
)

@Serializable
data class NetworkDto(
    @SerialName("operator") val operator: String,
    @SerialName("price_per_gb_usd_cents") val pricePerGbUsdCents: Long,
)

@Serializable
data class RatesResponse(@SerialName("rates") val rates: List<RateDto> = emptyList())

@Serializable
data class AccountRequest(@SerialName("account_id") val accountId: String)

/** The account's single global eSIM (null when not yet provisioned). */
@Serializable
data class ProfileDto(
    @SerialName("iccid") val iccid: String,
    @SerialName("status") val status: String,
    @SerialName("activation_code") val activationCode: String = "",
    @SerialName("smdp_address") val smdpAddress: String = "",
    /** On-SIM wallet balance in USD cents. */
    @SerialName("wallet_usd_cents") val walletUsdCents: Long = 0,
)

@Serializable
data class StatusResponse(
    @SerialName("profile") val profile: ProfileDto? = null,
    /** The account's one open crypto checkout, if any. */
    @SerialName("pending_invoice_url") val pendingInvoiceUrl: String? = null,
    /** Paid but not yet in the wallet (eSIM being created, or funding retrying). */
    @SerialName("pending_credit_cents") val pendingCreditCents: Long = 0,
    /** The balance is a last-known fallback; the server couldn't read it live. */
    @SerialName("balance_stale") val balanceStale: Boolean = false,
)

@Serializable
data class OkResponse(@SerialName("ok") val ok: Boolean = false)

/** Claim: [oldAccountId] is the SIM ID copied from the other phone. */
@Serializable
data class TransferInRequest(
    @SerialName("old_account_id") val oldAccountId: String,
    @SerialName("new_account_id") val newAccountId: String,
)

@Serializable
data class StripeKeyResponse(@SerialName("publishable_key") val publishableKey: String = "")

@Serializable
data class StripeIntentRequest(
    @SerialName("account_id") val accountId: String,
    @SerialName("amount_cents") val amountCents: Long,
)

@Serializable
data class StripeIntentResponse(@SerialName("client_secret") val clientSecret: String = "")

@Serializable
data class NowPayInvoiceRequest(
    @SerialName("account_id") val accountId: String,
    @SerialName("amount_cents") val amountCents: Long,
)

@Serializable
data class NowPayInvoiceResponse(
    @SerialName("invoice_url") val invoiceUrl: String = "",
    @SerialName("invoice_id") val invoiceId: String = "",
    /** Extra credit the 5% crypto bonus adds, in USD cents. */
    @SerialName("bonus_usd_cents") val bonusUsdCents: Long = 0,
    @SerialName("amount_cents") val amountCents: Long = 0,
    /** True when the server returned the account's already-open checkout. */
    @SerialName("existing") val existing: Boolean = false,
)
