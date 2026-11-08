package com.vayunmathur.taxi.network.lyft

import android.os.Build
import android.util.Base64
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

@Serializable
data class LyftToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("scope") val scope: String? = null,
    @SerialName("user_id") val userId: String? = null,
)

sealed interface LyftAuthResult {
    data class Success(val token: LyftToken) : LyftAuthResult

    /** Lyft wants a web challenge finished first; [url] comes from BrowserRedirectRequiredError. */
    data class NeedsWebChallenge(val url: String) : LyftAuthResult

    /**
     * Lyft recognised the number but wants a second factor before issuing a token — typically
     * `email_match` when signing in from an unrecognised device. [hint] is Lyft's masked value
     * (e.g. `v******t@u*c.edu`). Answered by resending the phone grant with the email filled in.
     */
    data class NeedsChallenge(
        val identifier: String,
        val hint: String?,
        val description: String?,
    ) : LyftAuthResult

    data class Failed(val code: String?, val message: String) : LyftAuthResult
}

/**
 * Lyft OAuth2, replicating the official app's phone + SMS flow.
 *
 * See `lyft-re/api-notes.md` §2. The client id/secret are string resources shipped in every copy
 * of the APK — they identify the app, not the user.
 */
object LyftAuth {
    private const val TAG = "LyftAuth"
    const val BASE = "https://api.lyft.com"

    private const val CLIENT_ID = "eSat7-iu9doM"
    private const val CLIENT_SECRET = "ZtvLDz0n1-kJVwkIvxC4iSJ0yMvJydPq"

    private const val GRANT_PHONE = "urn:lyft:oauth2:grant_type:phone"
    private const val GRANT_CLIENT = "client_credentials"

    /**
     * Token for the "logged out user", used to authorize pre-login calls such as
     * `/v1/phoneauth`.
     *
     * From `defpackage/o92.d()`: when there is no user session the app mints one with
     * `grant_type=client_credentials` against the same token endpoint (Basic client auth), then
     * `o92.c()` wraps it as `Bearer <token>` for outgoing requests. Basic auth is only ever sent
     * to `/oauth2/access_token` itself — sending it anywhere else yields `invalid_client`.
     */
    @Volatile
    private var cachedClientToken: String? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The app version we impersonate. Lyft maps the `invalid_client` OAuth error to the message
     * "this version of the Lyft app is no longer supported" (`auth_invalid_client_message`), so
     * this has to track a version the server still accepts.
     */
    private const val APP_VERSION = "2026.29.3.1785309574"

    /** How much of a failure body goes to logcat — enough to identify the error, never tokens. */
    private const val LOG_BODY_PREVIEW_MAX = 600

    /**
     * The subset of `defpackage/tmi.b()` we can reproduce without the app's internals.
     *
     * `tmi.a()` drops null/empty values, so the omitted headers (`x-session`, `x-carrier`,
     * `x-location`, `x-client-session-id`, `x-device-density`) are all optional by construction.
     * User-agent format is from `defpackage/i8g0.invoke()`:
     * `"%s:android:%s:%s".format(applicationIdForUserAgent, Build.VERSION.RELEASE, appVersion)`,
     * where `applicationIdForUserAgent` is `"lyft"` per `me.lyft.android.BuildConfig`.
     */
    internal fun commonHeaders(): Map<String, String> {
        val locale = Locale.getDefault()
        val model = Build.MODEL.uppercase(locale)
        val manufacturer = Build.MANUFACTURER.uppercase(locale)
        val device = if (model.startsWith(manufacturer)) Build.MODEL else "${Build.MANUFACTURER} ${Build.MODEL}"
        return buildMap {
            put("user-agent", "lyft:android:${Build.VERSION.RELEASE}:$APP_VERSION")
            put("user-device", device)
            put("x-design-id", "X")
            put("x-timestamp-ms", System.currentTimeMillis().toString())
            put("x-timestamp-source", "system")
            locale.language.takeIf { it.isNotBlank() }?.let { put("x-locale-language", it) }
            locale.country.takeIf { it.isNotBlank() }?.let { put("x-locale-region", it) }
            put("accept-language", locale.toLanguageTag())
            // `tmi.b()` sets these on EVERY production request — both branches of its
            // geo/compliance block (lines 124-136) always emit them, so unlike the other
            // `x-*` headers they are never dropped. Values for the North-American API root
            // (`api.lyft.com`): `nq1.NORTH_AMERICA.getGeoRegionHeaderValue()` = "GEO_REGION_NA"
            // and `x-lyft-payload-compliance` = "COMPLIANCE_REGION_CCPA" (tmi.java:129-130).
            // Reads/quotes tolerate their absence; payment-write (charge-account) validation
            // appears to require the compliance region to be declared, else 422.
            put("x-lyft-geo-region", "GEO_REGION_NA")
            put("x-lyft-payload-compliance", "COMPLIANCE_REGION_CCPA")
        }
    }

    private val basicAuth: String by lazy {
        val raw = "$CLIENT_ID:$CLIENT_SECRET".toByteArray()
        "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    /** New id per login attempt, carried through both legs so the server can correlate them. */
    fun newSessionUuid(): String = UUID.randomUUID().toString()

    private suspend fun clientToken(): String? {
        cachedClientToken?.let { return it }
        val result = tokenCall(formEncode("grant_type" to GRANT_CLIENT))
        val token = (result as? LyftAuthResult.Success)?.token?.accessToken?.takeIf { it.isNotBlank() }
        cachedClientToken = token
        return token
    }

    /**
     * Asks Lyft to text a code to [phoneNumber] (E.164).
     *
     * Recovered from `defpackage/xnk.c` →
     * `bdj.a(…, "/v1/phoneauth", eks.POST, …, 8064)` and the request DTO `defpackage/ypa`
     * (proto `pb.api.endpoints.v1.phone_auth.CreatePhoneAuthRequest`): tag 1 `phone_number`,
     * tag 2 `voice_verification`, tag 3 `message_format`, tag 4 `client_configuration`.
     *
     * The flags word 8064 sets bit 128, so no header map is passed at the call site — the
     * `Authorization` comes from the transport's auth provider, which is the
     * `client_credentials` bearer above. 8064 also sets bit 2048, suppressing the
     * form-urlencoded default in `bdj.a`, so the body is JSON.
     *
     * `message_format` is `sms_android_retriever` in the official app only when it uses the SMS
     * Retriever API; we type the code by hand, so `sms_basic` is correct.
     */
    suspend fun requestSmsCode(phoneNumber: String): LyftAuthResult {
        val bearer = clientToken()
            ?: return LyftAuthResult.Failed(null, "Could not obtain a Lyft client token")
        val body = json.encodeToString(
            PhoneAuthRequest.serializer(),
            PhoneAuthRequest(phoneNumber = phoneNumber),
        )
        val resp = NetworkClient.performRequest(
            url = "$BASE/v1/phoneauth",
            method = "POST",
            headers = commonHeaders() + mapOf(
                "Authorization" to "Bearer $bearer",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            ),
            body = body,
        )
        Log.debug(TAG, "POST /v1/phoneauth -> ${resp.status}")
        return if (resp.isSuccess) {
            LyftAuthResult.Success(LyftToken(accessToken = ""))
        } else {
            Log.status(TAG, "phoneauth failed ${resp.status}: ${resp.body.take(LOG_BODY_PREVIEW_MAX)}")
            failure(resp.status, resp.body)
        }
    }

    /**
     * Exchanges the SMS code for a token.
     *
     * [email] answers an `email_match` challenge. From `OAuth2Service.e`, the app does not use a
     * separate challenge endpoint for this — it reissues the same phone grant with the `email`
     * field (tag 7) populated, so the SMS code must still be valid on the retry.
     */
    suspend fun verifySmsCode(
        phoneNumber: String,
        code: String,
        sessionUuid: String,
        email: String? = null,
    ): LyftAuthResult {
        val fields = buildList {
            add("grant_type" to GRANT_PHONE)
            add("phone_number" to phoneNumber)
            add("phone_code" to code)
            add("login_session_uuid" to sessionUuid)
            email?.takeIf { it.isNotBlank() }?.let {
                add("email" to it)
                // Mirrors the "This is my account" prompt_action Lyft offers alongside the
                // challenge; without it the server can read the retry as a new-account attempt.
                add("confirm" to "true")
            }
        }
        return tokenCall(formEncode(*fields.toTypedArray()))
    }

    suspend fun refresh(refreshToken: String): LyftToken? {
        val form = formEncode(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        )
        return (tokenCall(form) as? LyftAuthResult.Success)?.token
    }

    /**
     * Revokes a token server-side (`POST /oauth2/revoke_token`, Basic client auth, form body
     * `token=<token>`), so signing out invalidates the session on Lyft's side rather than only
     * dropping it locally. Best-effort: returns whether the server accepted it.
     */
    suspend fun revoke(token: String): Boolean {
        val resp = NetworkClient.performRequest(
            url = "$BASE/oauth2/revoke_token",
            method = "POST",
            headers = commonHeaders() + mapOf(
                "Authorization" to basicAuth,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Accept" to "application/json",
            ),
            body = formEncode("token" to token),
        )
        Log.debug(TAG, "POST /oauth2/revoke_token -> ${resp.status}")
        return resp.isSuccess
    }

    private suspend fun tokenCall(form: String): LyftAuthResult {
        val resp = NetworkClient.performRequest(
            url = "$BASE/oauth2/access_token",
            method = "POST",
            headers = commonHeaders() + mapOf(
                "Authorization" to basicAuth,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Accept" to "application/json",
            ),
            body = form,
        )
        Log.debug(TAG, "POST /oauth2/access_token -> ${resp.status}")
        if (!resp.isSuccess) {
            // Body only on failure — a success body carries the tokens.
            Log.status(TAG, "token failed ${resp.status}: ${resp.body.take(LOG_BODY_PREVIEW_MAX)}")
            return failure(resp.status, resp.body)
        }
        val token = runCatching { json.decodeFromString(LyftToken.serializer(), resp.body) }
            .getOrElse { return LyftAuthResult.Failed(null, "Unreadable token response") }
        return LyftAuthResult.Success(token)
    }

    private fun failure(status: Int, body: String): LyftAuthResult {
        val error = runCatching { json.decodeFromString(LyftError.serializer(), body) }.getOrNull()
        error?.redirectUri?.takeIf { it.isNotBlank() }?.let {
            return LyftAuthResult.NeedsWebChallenge(it)
        }
        error?.challenges?.firstOrNull()?.let { challenge ->
            return LyftAuthResult.NeedsChallenge(
                identifier = challenge.identifier,
                hint = challenge.data,
                description = error.errorDescription,
            )
        }
        val message = error?.errorDescription ?: error?.error ?: "HTTP $status"
        return LyftAuthResult.Failed(error?.error, message)
    }

    private fun formEncode(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

@Serializable
private data class PhoneAuthRequest(
    @SerialName("phone_number") val phoneNumber: String,
    @SerialName("voice_verification") val voiceVerification: Boolean = false,
    @SerialName("message_format") val messageFormat: String = "sms_basic",
    @SerialName("client_configuration") val clientConfiguration: String = "release",
)

@Serializable
private data class LyftError(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("redirect_uri") val redirectUri: String? = null,
    val challenges: List<LyftChallenge> = emptyList(),
)

@Serializable
private data class LyftChallenge(
    val identifier: String,
    /** Masked hint for the expected answer, e.g. `v******t@u*c.edu`. */
    val data: String? = null,
)
