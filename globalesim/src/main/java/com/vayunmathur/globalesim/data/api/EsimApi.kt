package com.vayunmathur.globalesim.data.api

import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

private val HTTP_STATUS = Regex("^HTTP (\\d{3})\\b")

/**
 * Thin client for our own eSIM storefront API (the esim-v1 routes on
 * api.maproj.org, FIRST_PARTY trust). Generic DTOs only — the reseller
 * vendor is contacted server-side and is never named here.
 */
object EsimApi {
    const val BASE_URL = "https://api.maproj.org"

    // Test seam: point the API at an unroutable address so offline-path
    // tests are hermetic on boxes that have internet.
    @androidx.annotation.VisibleForTesting
    internal var baseUrlOverride: String? = null

    private val baseUrl: String get() = baseUrlOverride ?: BASE_URL

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun url(path: String): String = baseUrl + path

    /** HTTP failure with a parsed status code. NetworkClient surfaces
     * non-2xx as `IOException("HTTP <code>: ...")`. */
    class ApiError(val statusCode: Int, message: String) : java.io.IOException(message)

    internal fun parseApiError(e: java.io.IOException): java.io.IOException {
        if (e is ApiError) return e
        val code = e.message?.let { HTTP_STATUS.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        return if (code != null) ApiError(code, e.message ?: "HTTP $code") else e
    }

    private suspend inline fun <T> wrapApiErrors(crossinline block: suspend () -> T): T {
        try {
            return block()
        } catch (e: java.io.IOException) {
            throw parseApiError(e)
        }
    }

    private suspend inline fun <reified Req, reified Res> post(path: String, body: Req): Res =
        wrapApiErrors {
            NetworkClient.callJson(
                url = url(path),
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(serializer<Req>(), body),
            )
        }

    private suspend inline fun <reified Res> get(path: String): Res =
        wrapApiErrors { NetworkClient.getJson(url(path)) }

    suspend fun rates(): RatesResponse = get("/esim/v1/rates")

    suspend fun status(accountId: String): StatusResponse =
        post("/esim/v1/status", AccountRequest(accountId))

    /** Move the eSIM whose SIM ID is [simId] onto [accountId] (this phone). */
    suspend fun claim(simId: String, accountId: String): OkResponse =
        post("/esim/v1/transfer-in", TransferInRequest(simId, accountId))

    suspend fun stripeKey(): StripeKeyResponse = get("/esim/v1/stripe-key")

    suspend fun stripeIntent(accountId: String, amountCents: Long): StripeIntentResponse =
        post("/esim/v1/stripe-intent", StripeIntentRequest(accountId, amountCents))

    suspend fun nowPayInvoice(accountId: String, amountCents: Long): NowPayInvoiceResponse =
        post("/esim/v1/nowpay-invoice", NowPayInvoiceRequest(accountId, amountCents))

    suspend fun nowPayCancel(accountId: String): OkResponse =
        post("/esim/v1/nowpay-cancel", AccountRequest(accountId))
}
