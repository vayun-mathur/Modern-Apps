package com.vayunmathur.taxi.network.lyft

import android.net.Uri
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.taxi.data.AddCardResult
import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.data.PaymentActionResult
import com.vayunmathur.taxi.data.PaymentMethodsResult
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Payment-method management.
 *
 * Endpoints recovered from the APK (`defpackage/l77`, `d77`, `ech0`, response DTO `u77` /
 * `c67`):
 *   GET    /chargeaccounts                → ReadChargeAccounts          (list)
 *   PUT    /charge-accounts-multi-provider → UpdateChargeAccount…      (set default)
 *   DELETE /chargeaccounts/{id}          → DeleteChargeAccount          (remove)
 *
 * Payment methods are held only in memory by callers and never persisted here.
 */
internal class LyftPaymentsClient(private val session: LyftApiSession) {
    private val cardTokenizer: LyftCardTokenizer by lazy {
        LyftCardTokenizer(session.json, session.systemTrustFactory, session::authJsonHeaders)
    }
    private val accountsParser: LyftChargeAccountsParser by lazy { LyftChargeAccountsParser(session) }

    suspend fun paymentMethods(): PaymentMethodsResult {
        val token = session.accessToken() ?: return PaymentMethodsResult.NotSignedIn
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/chargeaccounts",
            method = "GET",
            headers = session.authHeaders(token),
        )
        Log.debug(TAG, "GET /chargeaccounts -> ${resp.status} (${resp.bytes.size} bytes)")
        if (!resp.isSuccess) {
            return PaymentMethodsResult.Failed(session.httpError(resp))
        }
        return PaymentMethodsResult.Success(accountsParser.parse(resp))
    }

    suspend fun setDefaultPaymentMethod(id: String): PaymentActionResult {
        val token = session.accessToken() ?: return PaymentActionResult.Failed("Not signed in to Lyft")
        // UpdateChargeAccountMultiProvider (fvf0). The real client (t77.b via ech0, for
        // MakePersonalDefault + TriggerDebtCollection) always sends four fields on this PUT:
        //   default (tag 2, BoolValue)              = true
        //   charge_account_id (tag 4, string)       = id
        //   skip_debt_collection (tag 8, BoolValue) = false  (TriggerDebtCollection)
        //   skip_persisted_challenge (tag 9, ...)   = false
        // Omitting the two skip_* wrappers makes the server reject the update with 422, so we
        // mirror the real client exactly.
        val body = buildJsonObject {
            put("charge_account_id", id)
            put("default", true)
            put("skip_debt_collection", false)
            put("skip_persisted_challenge", false)
        }.toString()
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/charge-accounts-multi-provider",
            method = "PUT",
            headers = session.authProtoJsonHeaders(
                token,
                "pb.api.endpoints.charge_accounts.UpdateChargeAccountMultipleProviderRequest",
            ),
            body = body,
        )
        Log.debug(TAG, "PUT /charge-accounts-multi-provider -> ${resp.status}")
        if (!resp.isSuccess) {
            // 422s here are opaque without the server's reason — log the full exchange so the
            // exact validation error (field name / compliance / challenge) is visible.
            Log.status(TAG, "set-default failed ${resp.status}: req=$body resp=${resp.text}")
            return PaymentActionResult.Failed(session.httpError(resp))
        }
        // The response is a ChargeAccountsResponse; return the refreshed list when it parses.
        val accounts = runCatching { accountsParser.parse(resp) }.getOrDefault(emptyList())
        return PaymentActionResult.Success(accounts.ifEmpty { null })
    }

    suspend fun removePaymentMethod(id: String): PaymentActionResult {
        val token = session.accessToken() ?: return PaymentActionResult.Failed("Not signed in to Lyft")
        val resp = NetworkClient.execute(
            url = "${LyftAuth.BASE}/chargeaccounts/${Uri.encode(id)}",
            method = "DELETE",
            headers = session.authHeaders(token),
        )
        Log.debug(TAG, "DELETE /chargeaccounts/{id} -> ${resp.status}")
        if (!resp.isSuccess) return PaymentActionResult.Failed(session.httpError(resp))
        // Callers re-fetch the list after a delete.
        return PaymentActionResult.Success(null)
    }

    // ----------------------------------------------------------------------------------------
    // Add a card (Create)
    //
    // Lyft never accepts a raw PAN. The card is tokenized by a payment processor first, and only
    // the resulting token/nonce is sent on. Two hops:
    //   1. POST /v1/tokenization_strategies (PostTokenizationStrategies, req `n1z` / resp `o1z`)
    //      → a list of TokenizationStrategy (`sbe0`), each carrying its processor's client key
    //        under `api_key` (Stripe publishable key `thc0`, Braintree client token `zo3`).
    //      NB: the strategy comes from *this* endpoint, not from `payment_options_response`.
    //   2. Tokenize at the processor (Stripe `POST /v1/tokens`, Braintree GraphQL) → token|nonce.
    //   3. POST /charge-accounts-multi-provider (CreateChargeAccountMultiProvider, req `una`)
    //      with provider_representation + card_meta_data → refreshed ChargeAccountsResponse.
    // Card data lives only for the duration of this call; full PAN/CVV are never logged.
    // ----------------------------------------------------------------------------------------

    suspend fun addCard(card: NewCard, makeDefault: Boolean): AddCardResult {
        val token = session.accessToken() ?: return AddCardResult.Failed("Not signed in to Lyft")

        // No retrievable processor config → native add-card is blocked for this session.
        val config = cardTokenizer.fetchConfig(token, card) ?: return AddCardResult.Unsupported

        val tokenized = when (val r = cardTokenizer.tokenize(config, card)) {
            is TokenizeResult.Err -> return AddCardResult.Failed(r.message)
            is TokenizeResult.Ok -> r
        }

        // `una`: default(2, bool), provider_representation(4, repeated `mt00`), card_meta_data(6,
        // `lf6`), skip_debt_collection(8), skip_persisted_challenge(9). The real create (`t77.a`)
        // sets default only when making default, always sends the two skip_* flags, and each Stripe
        // `mt00` carries a `version` (STRIPE_TOKEN / STRIPE_SETUP_INTENT). Wrapper types serialize
        // as bare scalars in proto3-JSON, so this maps 1:1 onto the DTO.
        val body = buildJsonObject {
            if (makeDefault) put("default", true)
            put("skip_debt_collection", false)
            put("skip_persisted_challenge", false)
            putJsonArray("provider_representation") {
                addJsonObject {
                    put("provider", tokenized.provider)
                    tokenized.token?.let { put("token", it) }
                    tokenized.nonce?.let { put("nonce", it) }
                    tokenized.version?.let { put("version", it) }
                }
            }
            putJsonObject("card_meta_data") {
                put("expiration_month", card.expMonth)
                put("expiration_year", card.expYear)
                if (card.postalCode.isNotBlank()) put("postal_code", card.postalCode)
            }
        }.toString()

        val resp = runCatching {
            NetworkClient.execute(
                url = "${LyftAuth.BASE}/charge-accounts-multi-provider",
                method = "POST",
                headers = session.authProtoJsonHeaders(
                    token,
                    "pb.api.endpoints.charge_accounts.CreateChargeAccountMultipleProviderRequest",
                ),
                body = body,
            )
        }.getOrElse { return AddCardResult.Failed("Create card request failed: ${it.message}") }
        Log.debug(TAG, "POST /charge-accounts-multi-provider (create) -> ${resp.status}")
        if (!resp.isSuccess) {
            Log.status(TAG, "add-card failed ${resp.status}: req=$body resp=${resp.text}")
            return AddCardResult.Failed(session.httpError(resp))
        }
        // Response is a ChargeAccountsResponse; return the refreshed list when it parses.
        val accounts = runCatching { accountsParser.parse(resp) }.getOrDefault(emptyList())
        return AddCardResult.Success(accounts.ifEmpty { null })
    }

    private companion object {
        private const val TAG = "LyftPayments"
    }
}
