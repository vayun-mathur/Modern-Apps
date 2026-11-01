package com.vayunmathur.fooddelivery.api

import com.vayunmathur.library.log.Log
import com.vayunmathur.fooddelivery.BuildConfig
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Shared plumbing for the Bites* API objects: base URLs, the auth-token store plus its
 * single-flight refresh, the authenticated request path and the `{message, data}` decoding
 * helpers. Split out of the old BitesApi object so no single object exceeds the function cap.
 */
internal object BitesCore {

    internal const val BASE = "https://api.deliverycollective.com"
    internal const val API = "$BASE/api/v1"

    /** Absolute expiry we persist alongside the token so a cold start knows if it is stale. */
    internal const val EXPIRES_AT_KEY = "expires_at_ms"

    /** The reference refreshes 30s before expiry so a request can't race the cutoff. */
    private const val REFRESH_SKEW_MS = 30_000L

    internal const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_REQUEST_TIMEOUT = 408
    private const val HTTP_TOO_MANY_REQUESTS = 429
    internal const val HTTP_CLIENT_ERROR_MIN = 400
    internal const val HTTP_CLIENT_ERROR_MAX = 499

    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        // Without encodeDefaults, kotlinx drops every field still equal to its default —
        // on a checkout that silently removed isMobile/isPickup/inStore/leaveAtDoor, tips
        // when 0, and each modifier's quantity, none of which is visible in the Kotlin
        // source. The reference sends all of them on every order.
        encodeDefaults = true
        // ...but it must not turn optional fields into explicit nulls: the reference leaves
        // promoCode/scheduledDate/scheduledTime/isDriveThru off the wire entirely when unset
        // (JSON.stringify drops undefined), so omit nulls to match.
        explicitNulls = false
    }

    @Volatile
    internal var storedToken: AuthToken? = null

    @Volatile
    internal var expiresAtMs: Long = 0

    /** Serialises refreshes: the refresh token rotates, so two in flight invalidate each other. */
    private val refreshMutex = Mutex()

    internal inline fun logd(message: () -> String) {
        if (BuildConfig.DEV_BUILD) Log.dev(TAG, message())
    }

    /**
     * The token plus its absolute expiry. Persisting only `expires_in` meant every cold
     * start treated the token as unknown-expiry and forced a refresh on the first request.
     */
    internal fun encodeTokenForStorage(token: AuthToken, expiryMs: Long): String {
        val encoded = json.encodeToJsonElement(AuthToken.serializer(), token).jsonObject
        return buildJsonObject {
            encoded.forEach { (k, v) -> put(k, v) }
            put(EXPIRES_AT_KEY, expiryMs)
        }.toString()
    }

    /**
     * One refresh at a time. Callers that queue behind the mutex re-check [storedToken]:
     * if it already moved on, the refresh someone else did is theirs to use. A refresh the
     * server rejects signs the user out; a transient failure keeps the session.
     */
    internal suspend fun refreshToken(stale: AuthToken): AuthToken? = refreshMutex.withLock {
        val current = storedToken ?: return@withLock null
        if (current.accessToken != stale.accessToken) return@withLock current
        if (current.refreshToken.isEmpty()) {
            signOut("no refresh token")
            return@withLock null
        }
        when (val result = exchangeRefreshTokenForToken(current.refreshToken)) {
            is RefreshResult.Ok -> result.token.also { BitesAuth.setToken(it) }
            RefreshResult.Rejected -> {
                signOut("refresh token rejected")
                null
            }
            RefreshResult.Failed -> null
        }
    }

    private fun signOut(reason: String) {
        Log.status(TAG, "signing out: $reason")
        BitesAuth.clearToken()
    }

    internal suspend fun getAccessToken(): String? {
        val token = storedToken ?: return null
        if (token.accessToken.isEmpty()) return null
        if (token.refreshToken.isNotEmpty() &&
            (expiresAtMs == 0L || System.currentTimeMillis() > expiresAtMs - REFRESH_SKEW_MS)
        ) {
            refreshToken(token)?.let { return it.accessToken }
            return storedToken?.accessToken
        }
        return token.accessToken
    }

    internal suspend fun authenticatedRequest(
        url: String,
        method: String = "GET",
        body: String? = null,
    ): SimpleResponse {
        var resp = NetworkClient.performRequest(url, method, authHeaders(), body)
        logd { "$method $url -> ${resp.status}" }
        val token = storedToken
        if (resp.status == HTTP_UNAUTHORIZED && token != null) {
            // On success this retries with the new token; after a sign-out it retries without
            // one, so public endpoints like the merchant list still load.
            if (refreshToken(token) != null || storedToken == null) {
                resp = NetworkClient.performRequest(url, method, authHeaders(), body)
                logd { "retry $method $url -> ${resp.status}" }
            } else {
                logd { "token refresh failed" }
            }
        }
        return resp
    }

    internal suspend fun authHeaders(): Map<String, String> {
        val h = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
        getAccessToken()?.let { h["Authorization"] = "Bearer $it" }
        return h
    }

    private sealed interface RefreshResult {
        data class Ok(val token: AuthToken) : RefreshResult
        /** The server refused the refresh token, so the session is over. */
        data object Rejected : RefreshResult
        /** Network error, timeout, rate limit or 5xx — worth trying again later. */
        data object Failed : RefreshResult
    }

    private suspend fun exchangeRefreshTokenForToken(
        refreshToken: String,
    ): RefreshResult = withContext(Dispatchers.Default) {
        try {
            val body = "grant_type=refresh_token&refresh_token=$refreshToken"
            val resp = NetworkClient.performRequest(
                "$BASE/auth/token",
                "POST",
                mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Accept" to "application/json",
                ),
                body,
            )
            logd { "refreshToken -> ${resp.status}" }
            when {
                resp.isSuccess -> RefreshResult.Ok(json.decodeFromString<AuthToken>(resp.body))
                isRefreshRejection(resp.status) -> RefreshResult.Rejected
                else -> RefreshResult.Failed
            }
        } catch (e: java.io.IOException) {
            Log.error(TAG, "refreshToken failed", e)
            RefreshResult.Failed
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "refreshToken failed", e)
            RefreshResult.Failed
        }
    }

    /** Same rule as the reference: any 4xx except timeout and rate limiting. */
    private fun isRefreshRejection(status: Int): Boolean =
        status in HTTP_CLIENT_ERROR_MIN..HTTP_CLIENT_ERROR_MAX &&
            status != HTTP_REQUEST_TIMEOUT &&
            status != HTTP_TOO_MANY_REQUESTS

    /**
     * Unwrap the `{message, data}` envelope these endpoints use, falling back to the root.
     * The element is handed straight to the deserializer — re-serialising it to a String and
     * parsing that back is a third full pass plus a duplicate of the whole payload.
     */
    internal fun <T> unwrap(body: String, serializer: KSerializer<T>): T? {
        val root = json.parseToJsonElement(body)
        val el = (root as? JsonObject)?.get("data") ?: root
        return json.decodeFromJsonElement(serializer, el)
    }

    internal suspend fun <T> decodeData(
        url: String,
        serializer: KSerializer<T>,
    ): T? = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest(url)
            if (!resp.isSuccess) null else unwrap(resp.body, serializer)
        } catch (e: java.io.IOException) {
            Log.error(TAG, "GET $url failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "GET $url failed", e)
            null
        }
    }

    internal suspend fun <T> decodeDataList(
        url: String,
        serializer: KSerializer<T>,
    ): List<T> = withContext(Dispatchers.Default) {
        try {
            val resp = authenticatedRequest(url)
            if (!resp.isSuccess) {
                emptyList()
            } else {
                unwrap(resp.body, ListSerializer(serializer)) ?: emptyList()
            }
        } catch (e: java.io.IOException) {
            Log.error(TAG, "GET $url failed", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "GET $url failed", e)
            emptyList()
        }
    }

    private const val TAG = "BitesCore"
}
