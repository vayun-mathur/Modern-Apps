package com.vayunmathur.shop.network

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CatalogOptionSelection
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.MerchantListing
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.data.UcpError
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.data.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * The only network surface of the shop app. All UCP behavior (merchant
 * discovery, REST/MCP transport, dated projections, SSRF guards, idempotency
 * ledger, image proxying) lives behind the api.maproj.org shop endpoints; this
 * client sends plain cart/buyer data and decodes display models.
 *
 * Error semantics mirror the former direct-UCP mapping so messages survive
 * the move unchanged: `unsupported` → merchant website; everything else on a
 * write → uncertain (never auto-retried).
 */
interface ShopBackend {
    suspend fun search(query: String, shop: String? = null, limit: Int = 20, country: String = "US"): Result<List<UcpProduct>>
    suspend fun getProduct(
        shop: String,
        productId: String,
        variantId: String? = null,
        title: String? = null,
        selectedOptions: List<CatalogOptionSelection> = emptyList(),
    ): Result<UcpProduct>
    suspend fun createCheckout(shop: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String): Result<UcpCheckout>
    suspend fun getCheckout(shop: String, sessionId: String): Result<UcpCheckout>
    suspend fun updateCheckout(shop: String, sessionId: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String): Result<UcpCheckout>
    suspend fun listMerchants(refresh: Boolean = false): Result<List<MerchantListing>>
}

class ShopBackendException(val error: UcpError) : Exception(error.toString())

internal object ShopHttpBackend : ShopBackend {
    internal const val BASE = "https://api.maproj.org/shop/v1"
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    internal var request: suspend (method: String, url: String, body: String?) -> BackendRawResponse = { method, url, body ->
        val response = withTimeout(30_000) {
            NetworkClient.execute(
                url, method,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                ),
                body = body,
                followRedirects = false,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000,
            )
        }
        BackendRawResponse(response.status, response.text, response.headers, response.header("Retry-After"))
    }

    override suspend fun search(query: String, shop: String?, limit: Int, country: String): Result<List<UcpProduct>> =
        catching {
            require(query.isNotBlank() && query.length <= 200) { "Invalid query" }
            require(limit in 1..50) { "Invalid limit" }
            require(country.matches(Regex("^[A-Z]{2}$"))) { "Invalid country" }
            val url = buildString {
                append("$BASE/search?query=").append(query.encode())
                append("&limit=").append(limit)
                append("&country=").append(country)
                if (!shop.isNullOrBlank()) append("&shop=").append(shop.encode())
            }
            val raw = get(url)
            raw.parse<ShopSearchResponse>().products.map { it.toModel() }
        }

    override suspend fun getProduct(
        shop: String,
        productId: String,
        variantId: String?,
        title: String?,
        selectedOptions: List<CatalogOptionSelection>,
    ): Result<UcpProduct> = catching {
        require(shop.isNotBlank() && productId.isNotBlank()) { "Invalid product request" }
        val url = buildString {
            append("$BASE/product?shop=").append(shop.encode())
            append("&id=").append(productId.encode())
            if (!variantId.isNullOrBlank()) append("&variant=").append(variantId.encode())
            if (!title.isNullOrBlank()) append("&title=").append(title.encode())
            if (selectedOptions.isNotEmpty()) {
                val selected = selectedOptions.map {
                    ShopCatalogOption(it.name, it.label, it.id)
                }
                append("&selected=").append(json.encodeToString(selected).encode())
            }
        }
        get(url).parse<ShopProductResponse>().product.toModel()
    }

    override suspend fun createCheckout(shop: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String): Result<UcpCheckout> =
        catching {
            checkLines(lines, shop)
            require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 256) { "Invalid idempotency key" }
            val request = ShopCreateCheckoutRequest(shop, lines.map { it.toRequestLine() }, inputs.toRequestInputs(), idempotencyKey)
            post("$BASE/checkout-sessions", json.encodeToString(request)).parseSession()
        }

    override suspend fun getCheckout(shop: String, sessionId: String): Result<UcpCheckout> =
        catching {
            require(shop.isNotBlank() && sessionId.isNotBlank()) { "Invalid checkout request" }
            get("$BASE/checkout-sessions?shop=${shop.encode()}&id=${sessionId.encode()}").parseSession()
        }

    override suspend fun updateCheckout(
        shop: String,
        sessionId: String,
        lines: List<CartLine>,
        inputs: CheckoutInputs,
        idempotencyKey: String,
    ): Result<UcpCheckout> = catching {
        checkLines(lines, shop)
        require(sessionId.isNotBlank()) { "Invalid checkout request" }
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 256) { "Invalid idempotency key" }
        val request = ShopUpdateCheckoutRequest(shop, sessionId, lines.map { it.toRequestLine() }, inputs.toRequestInputs(), idempotencyKey)
        put("$BASE/checkout-sessions", json.encodeToString(request)).parseSession()
    }

    override suspend fun listMerchants(refresh: Boolean): Result<List<MerchantListing>> =
        catching {
            val url = "$BASE/merchants" + if (refresh) "?refresh=true" else ""
            get(url).parse<ShopMerchantsResponse>().merchants.map { it.toModel() }
        }

    private fun checkLines(lines: List<CartLine>, shop: String) {
        require(lines.isNotEmpty() && lines.all { it.shopDomain == shop && it.quantity in 1..99 }) { "Invalid checkout lines" }
    }

    private suspend fun get(url: String): BackendRawResponse = request("GET", url, null)
    private suspend fun post(url: String, body: String): BackendRawResponse = request("POST", url, body)
    private suspend fun put(url: String, body: String): BackendRawResponse = request("PUT", url, body)

    private inline fun <reified T> BackendRawResponse.parse(): T {
        checkStatus()
        return runCatching { json.decodeFromString<T>(body) }
            .getOrElse { throw ShopBackendException(UcpError.Malformed("The server sent an unexpected response. Please try again.")) }
    }

    private fun BackendRawResponse.parseSession(): UcpCheckout {
        checkStatus()
        val session = runCatching { json.decodeFromString<ShopCheckoutSession>(body) }
            .getOrElse { throw ShopBackendException(UcpError.Malformed("The server sent an unexpected response. Please try again.")) }
        return session.toModel()
    }

    private fun BackendRawResponse.checkStatus() {
        if (status in 200..299) return
        // The server's error envelope mirrors the former UcpError codes.
        val envelope = runCatching { json.decodeFromString<ShopErrorEnvelope>(body) }.getOrNull()?.error
        if (envelope != null) {
            throw ShopBackendException(
                when (envelope.code) {
                    "transport" -> UcpError.Transport(envelope.message.ifBlank { "Couldn't reach the store. Check your connection and try again." })
                    "rate_limited" -> UcpError.RateLimited(envelope.retryAfterMs ?: retryAfter?.toLongOrNull()?.times(1000))
                    "http" -> UcpError.Http(status, "")
                    "malformed" -> UcpError.Malformed(envelope.message.ifBlank { "The store sent an unexpected response. Please try again." })
                    "rpc" -> UcpError.Rpc(envelope.message.ifBlank { "The merchant couldn't process the request." })
                    "uncertain" -> UcpError.Transport("The request may have reached the merchant. Do not retry; check the session or use its website.")
                    // "unsupported" and anything unknown: the merchant website is the fallback.
                    else -> UcpError.Unsupported(envelope.message.ifBlank { "These requirements need the merchant website." })
                },
            )
        }
        if (status == 429) throw ShopBackendException(UcpError.RateLimited(retryAfter?.toLongOrNull()?.times(1000)))
        throw ShopBackendException(UcpError.Http(status, ""))
    }

    private suspend fun <T> catching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        Result.failure(ShopBackendException(UcpError.Transport("Merchant request timed out")))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ShopBackendException) {
        Result.failure(error)
    } catch (_: IllegalArgumentException) {
        Result.failure(ShopBackendException(UcpError.Malformed("Invalid shop request")))
    } catch (_: Exception) {
        Result.failure(ShopBackendException(UcpError.Transport("Couldn't reach the store. Check your connection and try again.")))
    }
}

internal data class BackendRawResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>>,
    val retryAfter: String?,
)

internal fun String.encode(): String = URLEncoder.encode(this, "UTF-8")

/** Split cart lines by merchant: one server checkout session per shop. */
internal fun splitByShop(lines: List<CartLine>): Map<Pair<String, String>, List<CartLine>> =
    lines.groupBy { Pair(it.platformId, it.shopDomain) }
