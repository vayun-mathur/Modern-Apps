package com.vayunmathur.fooddelivery.api

import com.vayunmathur.library.log.Log
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Sign-in, token storage and the phone-OTP flow. */
object BitesAuth {

    var onTokenUpdated: ((String) -> Unit)? = null

    /** Removes the persisted copy, so a sign-out survives a restart. */
    var onTokenCleared: (() -> Unit)? = null

    fun setToken(token: AuthToken?) {
        BitesCore.storedToken = token
        if (token == null) return
        if (token.expiresIn > 0) {
            BitesCore.expiresAtMs =
                System.currentTimeMillis() + token.expiresIn * MILLIS_PER_SECOND
        }
        onTokenUpdated?.invoke(
            BitesCore.encodeTokenForStorage(token, BitesCore.expiresAtMs),
        )
    }

    fun restoreToken(tokenJson: String) {
        try {
            val root = BitesCore.json.parseToJsonElement(tokenJson).jsonObject
            BitesCore.storedToken =
                BitesCore.json.decodeFromJsonElement(AuthToken.serializer(), root)
            BitesCore.expiresAtMs =
                root[BitesCore.EXPIRES_AT_KEY]?.jsonPrimitive?.longOrNull ?: 0L
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "restoreToken failed", e)
        }
    }

    fun isLoggedIn(): Boolean =
        BitesCore.storedToken?.accessToken?.isNotEmpty() == true

    fun clearToken() {
        BitesCore.storedToken = null
        BitesCore.expiresAtMs = 0
        onTokenCleared?.invoke()
    }

    suspend fun verifyPhone(phone: String): String? = withContext(Dispatchers.Default) {
        try {
            val cleanPhone = "+" + phone.replace(Regex("\\D"), "")
            val body = "{\"phoneNumber\":\"$cleanPhone\"," +
                "\"audience\":\"customer\"," +
                "\"scope\":\"openid email profile offline_access\"}"
            val resp = NetworkClient.performRequest(
                "${BitesCore.BASE}/auth/verify_phone", "POST",
                mapOf("Content-Type" to "application/json"), body,
            )
            BitesCore.logd { "verifyPhone -> ${resp.status}" }
            if (resp.isSuccess) {
                val parsed = BitesCore.json.parseToJsonElement(resp.body)
                if (parsed is kotlinx.serialization.json.JsonObject) {
                    (parsed["state_id"] ?: parsed["stateId"])?.toString()?.trim('"')
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: java.io.IOException) {
            // Swallowing this silently is what made a TLS-pinning failure look like
            // "login does nothing" with an empty logcat.
            Log.error(TAG, "verifyPhone failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "verifyPhone failed", e)
            null
        }
    }

    suspend fun exchangeOtpCodeForToken(
        stateId: String,
        code: String,
    ): AuthToken? = withContext(Dispatchers.Default) {
        try {
            val body = "grant_type=phone_otp&state_id=$stateId&code=$code"
            val resp = NetworkClient.performRequest(
                "${BitesCore.BASE}/auth/token",
                "POST",
                mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Accept" to "application/json",
                ),
                body,
            )
            BitesCore.logd { "exchangeOtp -> ${resp.status}" }
            if (resp.isSuccess) {
                BitesCore.json.decodeFromString<AuthToken>(resp.body)
            } else {
                null
            }
        } catch (e: java.io.IOException) {
            Log.error(TAG, "exchangeOtp failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "exchangeOtp failed", e)
            null
        }
    }

    private const val TAG = "BitesAuth"
    private const val MILLIS_PER_SECOND = 1000L
}
