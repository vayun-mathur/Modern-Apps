package com.vayunmathur.taxi.network.lyft

import android.net.Uri
import android.util.Base64
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.taxi.data.NewCard
import javax.net.ssl.SSLSocketFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Resolves which processor to tokenize a new card with and runs the tokenization.
 *
 * Lyft never accepts a raw PAN. The card is tokenized by a payment processor first, and only
 * the resulting token/nonce is sent on. Two hops:
 *   1. POST /v1/tokenization_strategies (PostTokenizationStrategies, req `n1z` / resp `o1z`)
 *      → a list of TokenizationStrategy (`sbe0`), each carrying its processor's client key
 *        under `api_key` (Stripe publishable key `thc0`, Braintree client token `zo3`).
 *      NB: the strategy comes from *this* endpoint, not from `payment_options_response`.
 *   2. Tokenize at the processor (Stripe `POST /v1/tokens`, Braintree GraphQL) → token|nonce.
 * Card data lives only for the duration of the call; full PAN/CVV are never logged.
 */
internal class LyftCardTokenizer(
    private val json: Json,
    private val systemTrustFactory: SSLSocketFactory,
    private val authJsonHeaders: (String) -> Map<String, String>,
) {
    /**
     * Resolves which processor to tokenize the card with by calling PostTokenizationStrategies.
     * The response (`o1z`) lists per-provider strategies (`sbe0`); the card ones carry the
     * processor's client key under `api_key`. Stripe is preferred, then Braintree; providers we
     * don't implement (Adyen etc.) yield null → caller reports Unsupported. Logs the resolved
     * provider and a redacted key so the residual "where does the key live" unknown is closed.
     */
    suspend fun fetchConfig(token: String, card: NewCard): TokenizerConfig? {
        // `n1z`: purpose(1, enum) = PAYIN, card_request(11) = { bin(1), last_four(2) }.
        val body = buildJsonObject {
            put("purpose", "PAYIN")
            putJsonObject("card_request") {
                put("bin", card.bin)
                put("last_four", card.last4)
            }
        }.toString()
        val resp = runCatching {
            NetworkClient.execute(
                url = "${LyftAuth.BASE}/v1/tokenization_strategies",
                method = "POST",
                headers = authJsonHeaders(token),
                body = body,
            )
        }.getOrElse {
            Log.status(TAG, "tokenization_strategies request failed", it)
            return null
        }
        Log.dev(TAG, "POST /v1/tokenization_strategies -> ${resp.status} (${resp.bytes.size} bytes)")
        if (!resp.isSuccess) {
            Log.status(TAG, "tokenization_strategies failed: ${resp.text.take(ERROR_BODY_PREVIEW_MAX)}")
            return null
        }
        val config = parseConfig(resp)
        if (config == null) {
            Log.status(TAG, "No supported tokenizer strategy in response")
        } else {
            Log.dev(TAG, "Tokenizer resolved: provider=${config.provider} key=${redactKey(config.key)}")
        }
        return config
    }

    /**
     * `PostTokenizationStrategiesResponse` (`o1z`): strategies(1, repeated `sbe0`). Each `sbe0`
     * has stripe_card_data(10)→api_key(1) and braintree_card_data(11)→api_key(1). Tries JSON then
     * protobuf, matching the codec negotiation the rest of this class uses.
     */
    fun parseConfig(resp: RawResponse): TokenizerConfig? {
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        return if (isProto) {
            parseConfigProto(resp.bytes) ?: parseConfigJson(resp.text)
        } else {
            parseConfigJson(resp.text) ?: parseConfigProto(resp.bytes)
        }
    }

    /**
     * `PostTokenizationStrategiesResponse` (`o1z`): strategies(1, repeated `sbe0`). The real client
     * prioritises the modern SCA strategy `stripe_card_setup_intent_data` (tag 22, `uhc0`) far above
     * legacy `stripe_card_data` (tag 10) — a 2026 build returns the SetupIntent one for cards, which
     * is why only recognising the legacy strategies made add-card fail (no config → Unsupported).
     * Order here mirrors that: SetupIntent → legacy Stripe token → Braintree.
     */
    fun parseConfigJson(raw: String): TokenizerConfig? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return null
        val strategies = root["strategies"]?.jsonArray?.mapNotNull { it as? JsonObject }
            ?: return null
        strategies.firstNotNullOfOrNull(::setupIntentConfig)?.let { return it }
        strategies.firstNotNullOfOrNull { s: JsonObject ->
            val data = s["stripe_card_data"] as? JsonObject ?: return@firstNotNullOfOrNull null
            val apiKey = data.lyftStr("api_key") ?: return@firstNotNullOfOrNull null
            TokenizerConfig("stripe", apiKey)
        }?.let { return it }
        return strategies.firstNotNullOfOrNull { s: JsonObject ->
            val data = s["braintree_card_data"] as? JsonObject ?: return@firstNotNullOfOrNull null
            val apiKey = data.lyftStr("api_key") ?: return@firstNotNullOfOrNull null
            TokenizerConfig("braintree", apiKey)
        }
    }

    private fun setupIntentConfig(strategy: JsonObject): TokenizerConfig? {
        val data = strategy["stripe_card_setup_intent_data"] as? JsonObject ?: return null
        val apiKey = data.lyftStr("api_key") ?: return null
        return TokenizerConfig(
            provider = "stripe_setup_intent",
            key = apiKey,
            clientSecret = data.lyftStr("client_secret"),
            setupIntentId = data.lyftStr("setup_intent_id"),
            stripeApiVersion = data.lyftStr("stripe_api_version"),
        )
    }

    fun parseConfigProto(bytes: ByteArray): TokenizerConfig? {
        val strategies = runCatching { ProtoMessage(bytes, 0, bytes.size).messages(1) }
            .getOrDefault(emptyList())
        // stripe_card_setup_intent_data (tag 22): api_key(1), client_secret(2), setup_intent_id(3),
        // stripe_api_version(4).
        strategies.firstNotNullOfOrNull { s ->
            s.message(SETUP_INTENT_STRATEGY_FIELD)?.let { d ->
                d.string(API_KEY_FIELD)?.let { apiKey ->
                    TokenizerConfig(
                        provider = "stripe_setup_intent",
                        key = apiKey,
                        clientSecret = d.string(CLIENT_SECRET_FIELD),
                        setupIntentId = d.string(SETUP_INTENT_ID_FIELD),
                        stripeApiVersion = d.string(STRIPE_API_VERSION_FIELD),
                    )
                }
            }
        }?.let { return it }
        strategies.firstNotNullOfOrNull { it.message(STRIPE_STRATEGY_FIELD)?.string(API_KEY_FIELD) }
            ?.let { return TokenizerConfig("stripe", it) }
        return strategies.firstNotNullOfOrNull { it.message(BRAINTREE_STRATEGY_FIELD)?.string(API_KEY_FIELD) }
            ?.let { TokenizerConfig("braintree", it) }
    }

    suspend fun tokenize(config: TokenizerConfig, card: NewCard): TokenizeResult =
        when (config.provider) {
            "stripe_setup_intent" -> tokenizeStripeSetupIntent(config, card)
            "stripe" -> tokenizeStripe(config.key, card)
            "braintree" -> tokenizeBraintree(config.key, card)
            else -> TokenizeResult.Err("Unsupported card processor: ${config.provider}")
        }

    /**
     * Modern SCA-compliant Stripe path (`sbe0` tag 22 `StripeCardSetupIntentDataDTO`, tokenized in
     * `ol6.c` case 2 via `qic0`). The server pre-creates a SetupIntent; we confirm it with the raw
     * card inline:
     *   POST https://api.stripe.com/v1/setup_intents/{setup_intent_id}/confirm
     *   Authorization: Bearer <api_key>;  Stripe-Version: <stripe_api_version>; form-encoded
     *   client_secret + payment_method_data[type|card[...]|billing_details[address][postal_code]]
     * The confirm response's `payment_method` (`pm_…`, `mna.payment_method`) is the token, sent on
     * as `mt00.token` with version `STRIPE_SETUP_INTENT` (`it00`).
     */
    suspend fun tokenizeStripeSetupIntent(
        config: TokenizerConfig,
        card: NewCard,
    ): TokenizeResult {
        val setupIntentId = config.setupIntentId
            ?: return TokenizeResult.Err("Stripe SetupIntent strategy missing setup_intent_id")
        val clientSecret = config.clientSecret
            ?: return TokenizeResult.Err("Stripe SetupIntent strategy missing client_secret")
        val form = buildString {
            append("client_secret=").append(Uri.encode(clientSecret))
            append("&payment_method_data[type]=card")
            append("&payment_method_data[card][number]=").append(Uri.encode(card.number))
            append("&payment_method_data[card][exp_month]=").append(card.expMonth)
            append("&payment_method_data[card][exp_year]=").append(card.expYear)
            append("&payment_method_data[card][cvc]=").append(Uri.encode(card.cvc))
            if (card.postalCode.isNotBlank()) {
                append("&payment_method_data[billing_details][address][postal_code]=")
                append(Uri.encode(card.postalCode))
            }
        }
        val headers = buildMap {
            put("Authorization", "Bearer ${config.key}")
            put("Content-Type", "application/x-www-form-urlencoded")
            put("Accept", "application/json")
            config.stripeApiVersion?.takeIf { it.isNotBlank() }?.let { put("Stripe-Version", it) }
        }
        val resp = runCatching {
            NetworkClient.execute(
                url = "https://api.stripe.com/v1/setup_intents/${Uri.encode(setupIntentId)}/confirm",
                method = "POST",
                headers = headers,
                body = form,
                sslSocketFactory = systemTrustFactory,
            )
        }.getOrElse { return TokenizeResult.Err("Stripe SetupIntent request failed: ${it.message}") }
        val root = runCatching { json.parseToJsonElement(resp.text) as? JsonObject }.getOrNull()
        if (!resp.isSuccess) {
            val msg = root?.get("error")?.jsonObject?.lyftStr("message")
                ?: "Stripe HTTP ${resp.status}: ${resp.text.take(ERROR_BODY_PREVIEW_MAX)}"
            return TokenizeResult.Err(msg)
        }
        val pm = root?.lyftStr("payment_method")
            ?: return TokenizeResult.Err("Stripe returned no payment_method")
        Log.dev(TAG, "Stripe SetupIntent confirmed ••${card.last4} -> ${pm.take(TOKEN_LOG_PREFIX_LENGTH)}…")
        // mt00.provider for a Stripe card is jju.h(qbe0.STRIPE.name()) = "stripe".
        return TokenizeResult.Ok("stripe", token = pm, nonce = null, version = "STRIPE_SETUP_INTENT")
    }

    /**
     * Stripe card token: `POST https://api.stripe.com/v1/tokens`, form-encoded, publishable key
     * as Bearer (mirrors `ol6` case STRIPE_CARD_DATA). Returns a `tok_…` used as `mt00.token`.
     */
    suspend fun tokenizeStripe(publishableKey: String, card: NewCard): TokenizeResult {
        val form = buildString {
            append("card[number]=").append(Uri.encode(card.number))
            append("&card[exp_month]=").append(card.expMonth)
            append("&card[exp_year]=").append(card.expYear)
            append("&card[cvc]=").append(Uri.encode(card.cvc))
        }
        // External host: force system CAs via an explicit factory. (NetworkClient's own
        // useSystemTrust flag falls back to the Lyft-only bundle, which rejects Stripe.) Guard
        // the call so a transport/TLS failure surfaces as an error rather than crashing.
        val resp = runCatching {
            NetworkClient.execute(
                url = "https://api.stripe.com/v1/tokens",
                method = "POST",
                headers = mapOf(
                    "Authorization" to "Bearer $publishableKey",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Accept" to "application/json",
                    "Stripe-Version" to "2015-10-12",
                ),
                body = form,
                sslSocketFactory = systemTrustFactory,
            )
        }.getOrElse { return TokenizeResult.Err("Stripe request failed: ${it.message}") }
        val root = runCatching { json.parseToJsonElement(resp.text) as? JsonObject }.getOrNull()
        if (!resp.isSuccess) {
            val msg = root?.get("error")?.jsonObject?.lyftStr("message")
                ?: "Stripe HTTP ${resp.status}: ${resp.text.take(ERROR_BODY_PREVIEW_MAX)}"
            return TokenizeResult.Err(msg)
        }
        val tok = root?.lyftStr("id") ?: return TokenizeResult.Err("Stripe returned no token")
        Log.dev(TAG, "Stripe tokenized ••${card.last4} -> ${tok.take(TOKEN_LOG_PREFIX_LENGTH)}…")
        return TokenizeResult.Ok("stripe", token = tok, nonce = null, version = "STRIPE_TOKEN")
    }

    /**
     * Braintree card nonce. The Braintree `card` SDK module isn't bundled in the Lyft APK, so the
     * flow is hand-built: parse the client token (`av7` → `authorizationFingerprint` + GraphQL
     * url), then run the `tokenizeCreditCard` mutation against the GraphQL endpoint. Returns a
     * nonce used as `mt00.nonce`.
     */
    suspend fun tokenizeBraintree(clientToken: String, card: NewCard): TokenizeResult {
        val parsed = parseBraintreeClientToken(clientToken)
            ?: return TokenizeResult.Err("Couldn't parse Braintree client token")
        val (graphQlUrl, fingerprint) = parsed
        val query = "mutation TokenizeCard(\$input: TokenizeCreditCardInput!) { " +
            "tokenizeCreditCard(input: \$input) { token } }"
        val reqBody = buildJsonObject {
            put("query", query)
            putJsonObject("variables") {
                putJsonObject("input") {
                    putJsonObject("creditCard") {
                        put("number", card.number)
                        put("expirationMonth", card.expMonth.toString())
                        put("expirationYear", card.expYear.toString())
                        put("cvv", card.cvc)
                    }
                }
            }
        }.toString()
        val resp = runCatching {
            NetworkClient.execute(
                url = graphQlUrl,
                method = "POST",
                headers = mapOf(
                    "Authorization" to "Bearer $fingerprint",
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "Braintree-Version" to "2024-08-23",
                ),
                body = reqBody,
                sslSocketFactory = systemTrustFactory,
            )
        }.getOrElse { return TokenizeResult.Err("Braintree request failed: ${it.message}") }
        val root = runCatching { json.parseToJsonElement(resp.text) as? JsonObject }.getOrNull()
        // Braintree GraphQL returns 200 with a non-empty `errors` array on failure.
        val errors = root?.get("errors")?.jsonArray
        if (!resp.isSuccess || !errors.isNullOrEmpty()) {
            val msg = errors?.firstOrNull()?.jsonObject?.lyftStr("message")
                ?: "Braintree HTTP ${resp.status}: ${resp.text.take(ERROR_BODY_PREVIEW_MAX)}"
            return TokenizeResult.Err(msg)
        }
        val nonce = root?.get("data")?.jsonObject
            ?.get("tokenizeCreditCard")?.jsonObject
            ?.lyftStr("token")
            ?: return TokenizeResult.Err("Braintree returned no nonce")
        Log.dev(TAG, "Braintree tokenized ••${card.last4} -> nonce ${nonce.take(NONCE_LOG_PREFIX_LENGTH)}…")
        return TokenizeResult.Ok("braintree", token = null, nonce = nonce)
    }

    /**
     * A Braintree client token is either raw JSON or base64-encoded JSON (`av7` base64-decodes
     * when it matches). We need `authorizationFingerprint` and the GraphQL url (`graphQL.url`,
     * defaulting to the public endpoint).
     */
    fun parseBraintreeClientToken(clientToken: String): Pair<String, String>? {
        val raw = if (clientToken.trimStart().startsWith("{")) {
            clientToken
        } else {
            runCatching { String(Base64.decode(clientToken, Base64.DEFAULT), Charsets.UTF_8) }
                .getOrNull() ?: return null
        }
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return null
        val fingerprint = obj.lyftStr("authorizationFingerprint") ?: return null
        val url = obj["graphQL"]?.jsonObject?.lyftStr("url")
            ?: "https://payments.braintree-api.com/graphql"
        return url to fingerprint
    }

    private companion object {
        private const val TAG = "LyftCardTokenizer"

        /** How much of a processor error body is logged — enough to identify it, never card data. */
        private const val ERROR_BODY_PREVIEW_MAX = 200
        /** Logged prefix of a Stripe token/payment-method id — the rest is redacted. */
        private const val TOKEN_LOG_PREFIX_LENGTH = 8
        /** Logged prefix of a Braintree nonce — the rest is redacted. */
        private const val NONCE_LOG_PREFIX_LENGTH = 6
        /** Visible prefix length when logging a redacted processor key. */
        private const val REDACTED_KEY_PREFIX_LENGTH = 8

        // TokenizationStrategy (`sbe0`) field tags: which processor a strategy is for.
        private const val STRIPE_STRATEGY_FIELD = 10
        private const val BRAINTREE_STRATEGY_FIELD = 11
        private const val SETUP_INTENT_STRATEGY_FIELD = 22
        // SetupIntent strategy (`uhc0`) field tags.
        private const val API_KEY_FIELD = 1
        private const val CLIENT_SECRET_FIELD = 2
        private const val SETUP_INTENT_ID_FIELD = 3
        private const val STRIPE_API_VERSION_FIELD = 4
    }
}

/**
 * Which processor to tokenize a new card with, and that processor's client [key]. For the modern
 * Stripe SetupIntent strategy (`sbe0` tag 22, `uhc0`) the extra SetupIntent fields are carried too.
 */
internal data class TokenizerConfig(
    val provider: String,
    val key: String,
    val clientSecret: String? = null,
    val setupIntentId: String? = null,
    val stripeApiVersion: String? = null,
)

/**
 * Outcome of tokenizing a card at the processor: a Stripe [token] or a Braintree [nonce], plus the
 * [version] (`it00`) the server needs to interpret a Stripe token (STRIPE_TOKEN vs
 * STRIPE_SETUP_INTENT); null for processors that don't send one (Braintree).
 */
internal sealed interface TokenizeResult {
    data class Ok(
        val provider: String,
        val token: String?,
        val nonce: String?,
        val version: String? = null,
    ) : TokenizeResult

    data class Err(val message: String) : TokenizeResult
}

private fun redactKey(key: String): String =
    if (key.length <= REDACTED_KEY_PREFIX_LENGTH) {
        "***"
    } else {
        "${key.take(REDACTED_KEY_PREFIX_LENGTH)}…(len ${key.length})"
    }

private const val REDACTED_KEY_PREFIX_LENGTH = 8
