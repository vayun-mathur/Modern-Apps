package com.vayunmathur.appstore.data.play

import com.vayunmathur.library.log.Log
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.helpers.AuthHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import kotlin.random.Random

/**
 * Anonymous auth via Aurora dispenser — now using HttpURLConnection.
 * Ported from AuroraStore's AuthProvider; previously used OkHttp.
 *
 * Two things govern whether this works at all.
 *
 * First the User-Agent: auroraoss.com is behind Cloudflare and only admits
 * Aurora Store's own, so [DISPENSER_USER_AGENT] must identify as Aurora.
 * Anything else gets a 403 challenge page before the API is ever reached.
 *
 * Second, the accounts are shared and the service throttles, which shows up as
 * a 429 or a 5xx that clears by itself shortly after. Those are retried with
 * exponential backoff and jitter rather than surfaced immediately, so a brief
 * throttle no longer leaves Play access broken until the app restarts.
 */
open class AnonymousAuthRepository(
    /**
     * Builds the Play session from dispensed credentials (the Google
     * checkin). Injectable so tests can fail the checkin deterministically
     * instead of depending on the host having no network; production uses
     * gplayapi's [AuthHelper] unchanged.
     */
    private val authBuilder: suspend (
        email: String,
        token: String,
        deviceProps: Properties,
    ) -> AuthData = { email, token, props ->
        AuthHelper.build(
            email = email,
            token = token,
            tokenType = AuthHelper.Token.AUTH,
            isAnonymous = true,
            properties = props,
            locale = java.util.Locale.getDefault(),
        )
    },
) {

    /** Holds dispenser URLs and constants for anonymous Play authentication. */
    companion object {
        private const val TAG = "AnonAuthRepo"
        const val DEFAULT_DISPENSER_URL = "https://auroraoss.com/api/auth"
        val FALLBACK_DISPENSERS = listOf(
            "https://auroraoss.com/api/auth"
        )

        /**
         * User-Agent sent to the dispenser, in Aurora's
         * `applicationId-versionName-versionCode` form.
         *
         * This deliberately identifies as Aurora Store. auroraoss.com sits
         * behind Cloudflare and only lets Aurora's own User-Agent reach the
         * API - verified directly: `com.aurora.store-*` reaches the endpoint,
         * while this app's own id gets a 403 Cloudflare challenge page on
         * every request. That 403 is what surfaced as "VPN/proxy detected -
         * dispenser blocked", and no amount of retrying fixes it.
         *
         * Keep the version roughly current; a version that has aged out could
         * start being refused. Mirrors AuroraStore app/build.gradle.kts.
         */
        private const val AURORA_APPLICATION_ID = "com.aurora.store"
        private const val AURORA_VERSION_NAME = "4.8.4"
        private const val AURORA_VERSION_CODE = 76
        const val DISPENSER_USER_AGENT =
            "$AURORA_APPLICATION_ID-$AURORA_VERSION_NAME-$AURORA_VERSION_CODE"

        private val json = Json { ignoreUnknownKeys = true }

        /** Attempts per dispenser before moving on. */
        private const val MAX_ATTEMPTS = 4

        /** First backoff step; doubles each attempt. */
        private const val BASE_BACKOFF_MS = 800L

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val HTTP_ERROR_MIN = 400
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_RATE_LIMITED = 429
        private const val HTTP_UNAVAILABLE = 503
        private const val HTTP_SERVER_ERROR_MIN = 500

        /**
         * Statuses worth retrying.
         *
         * 403 stays in the list even though the systematic one - Cloudflare
         * refusing a non-Aurora User-Agent - is now fixed at the source. What
         * is left is the edge throttling that also answers 403, and that does
         * clear. The cost of being wrong is bounded: a genuine block waits out
         * the backoff before reporting, rather than reporting a recoverable
         * hiccup as a permanent failure.
         */
        private fun isTransient(error: Throwable): Boolean = when (error) {
            is AuthError.RateLimited, is AuthError.VpnRequired, is AuthError.Maintenance -> true
            is AuthError.Network -> true
            is AuthError.Unknown -> error.code >= HTTP_SERVER_ERROR_MIN
            else -> false
        }
    }

    @Serializable
    /** An anonymous-auth dispenser response carrying the account email and token. */
    data class AuthResponse(
        val email: String,
        @SerialName("authToken") val auth: String
    )

    /** Failure modes returned by anonymous Play authentication dispensers. */
    sealed class AuthError : Exception() {
        /** Authentication failed because the dispenser rate-limited the request. */
        object RateLimited : AuthError()
        /** Authentication failed because the dispenser rejected the request. */
        object BadRequest : AuthError()
        /** Authentication requires routing through a VPN endpoint. */
        object VpnRequired : AuthError()
        /** Authentication failed because the dispenser endpoint was not found. */
        object NotFound : AuthError()
        /** Authentication is unavailable while the dispenser is under maintenance. */
        object Maintenance : AuthError()
        /** Authentication failed with an unexpected status code and response body. */
        data class Unknown(val code: Int, val body: String?) : AuthError()
        /** Authentication failed because the dispenser could not be reached. */
        data class Network(override val message: String?) : AuthError()
    }

    /**
     * Fetch raw credentials from dispenser.
     */
    suspend fun fetchAnonCredentials(
        deviceProps: Properties,
        dispenserUrl: String = DEFAULT_DISPENSER_URL
    ): Result<AuthResponse> = withContext(Dispatchers.IO) {
        try {
            val propsJson = propertiesToJson(deviceProps)
            val bodyBytes = propsJson.toByteArray(Charsets.UTF_8)

            val conn = (URL(dispenserUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                doInput = true
                instanceFollowRedirects = false
                useCaches = false
                // Aurora's postAuth sends exactly these two and nothing else.
                setRequestProperty("User-Agent", DISPENSER_USER_AGENT)
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(bodyBytes.size)
            }
            conn.outputStream.use { it.write(bodyBytes) }

            val code = try {
                conn.responseCode
            } catch (expected: java.io.IOException) {
                conn.disconnect()
                return@withContext Result.failure(AuthError.Network(expected.message))
            }
            val responseBody = try {
                val stream = if (code >= HTTP_ERROR_MIN) conn.errorStream ?: conn.inputStream else conn.inputStream
                stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            } catch (_: java.io.IOException) {
                ""
            } finally {
                conn.disconnect()
            }

            if (code !in HTTP_OK_MIN..HTTP_OK_MAX) {
                return@withContext Result.failure(mapError(code, responseBody))
            }

            try {
                val auth = json.decodeFromString(AuthResponse.serializer(), responseBody)
                Result.success(auth)
            } catch (expected: IllegalArgumentException) {
                Log.status(TAG, "Parse auth failed: ${expected.message}")
                Result.failure(AuthError.Unknown(code, responseBody))
            }
        } catch (expected: java.io.IOException) {
            Result.failure(AuthError.Network(expected.message))
        }
    }

    /**
     * Build AuthData from dispenser response using gplayapi AuthHelper.
     */
    suspend fun buildAuthData(
        email: String,
        token: String,
        deviceProps: Properties
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        try {
            Result.success(authBuilder(email, token, deviceProps))
        } catch (expected: IllegalStateException) {
            Log.status(TAG, "buildAuthData failed: ${expected.message}", expected)
            Result.failure(expected)
        } catch (expected: IllegalArgumentException) {
            Log.status(TAG, "buildAuthData failed: ${expected.message}", expected)
            Result.failure(expected)
        }
    }

    /**
     * Dispense a fresh anonymous account, retrying throttled responses.
     *
     * Each dispenser gets [MAX_ATTEMPTS] tries with exponential backoff and
     * jitter before the next is tried. Jitter matters because every install
     * that got throttled at the same moment would otherwise retry in lockstep
     * and throttle each other again.
     */
    open suspend fun ensureAuthData(
        deviceProps: Properties,
        dispenserUrls: List<String> = FALLBACK_DISPENSERS
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null

        for (url in dispenserUrls) {
            for (attempt in 0 until MAX_ATTEMPTS) {
                currentCoroutineContext().ensureActive()

                val credResult = fetchAnonCredentials(deviceProps, url)
                val cred = credResult.getOrNull()
                if (cred != null) {
                    val authResult = buildAuthData(cred.email, cred.auth, deviceProps)
                    if (authResult.isSuccess) return@withContext authResult
                    // Credentials arrived but were unusable. A different
                    // account may well work, so this is worth another attempt.
                    lastError = authResult.exceptionOrNull()
                } else {
                    val error = credResult.exceptionOrNull()
                    lastError = error
                    if (error != null && !isTransient(error)) {
                        Log.status(TAG, "Dispenser $url rejected us permanently: $error")
                        break
                    }
                }

                if (attempt < MAX_ATTEMPTS - 1) {
                    val backoff = BASE_BACKOFF_MS shl attempt
                    val wait = backoff + Random.nextLong((backoff / 2).coerceAtLeast(1))
                    Log.status(TAG, "Dispenser $url attempt ${attempt + 1} failed ($lastError), " +
                        "retrying in ${wait}ms")
                    delay(wait)
                }
            }
        }
        Result.failure(lastError ?: Exception("All dispensers failed"))
    }

    private fun propertiesToJson(props: Properties): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in props.entries) {
            if (!first) sb.append(",")
            sb.append("\"")
                .append(escapeJson(k.toString()))
                .append("\":\"")
                .append(escapeJson(v.toString()))
                .append("\"")
            first = false
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun mapError(code: Int, body: String?): Throwable {
        return when (code) {
            HTTP_BAD_REQUEST -> AuthError.BadRequest
            HTTP_FORBIDDEN -> AuthError.VpnRequired
            HTTP_NOT_FOUND -> AuthError.NotFound
            HTTP_RATE_LIMITED -> AuthError.RateLimited
            HTTP_UNAVAILABLE -> AuthError.Maintenance
            else -> AuthError.Unknown(code, body)
        }
    }

    fun errorMessage(error: Throwable): String {
        return when (error) {
            is AuthError.RateLimited -> "Play dispenser is rate limiting – try again shortly"
            // Only reported once retries are exhausted, so by this point a
            // transient throttle has been ruled out.
            is AuthError.VpnRequired -> "Play dispenser refused this network – try again, or disable VPN"
            is AuthError.BadRequest -> "Bad request to dispenser"
            is AuthError.NotFound -> "Dispenser unreachable"
            is AuthError.Maintenance -> "Dispenser maintenance, try later"
            is AuthError.Unknown -> "Dispenser error ${error.code}"
            is AuthError.Network -> "Network error: ${error.message}"
            else -> error.message ?: "Auth failed"
        }
    }
}
