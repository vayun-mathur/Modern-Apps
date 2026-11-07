package com.vayunmathur.email.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.vayunmathur.library.log.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.core.net.toUri
import com.vayunmathur.email.BuildConfig
import com.vayunmathur.email.data.EmailAccount
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Outlook / Microsoft 365 OAuth2 — Authorization Code + PKCE for our own Azure
 * app registered as Mobile and desktop applications with redirect
 * `com.vayunmathur.email://oauth` (RFC8252 compliant native flow, same as
 * Thunderbird Desktop `useExternalBrowser=true` in `OAuth2Providers.sys.mjs`).
 *
 * Flow:
 * 1. `start()` — generate verifier (64 base64Url), challenge S256, state 24,
 *    persist to `outlook_oauth` prefs, open Custom Tab at Microsoft authorize endpoint.
 * 2. Redirect `com.vayunmathur.email://oauth?code=...&state=...` → [com.vayunmathur.email.ui.OAuthActivity]
 *    → `complete()` validates state, POSTs to token endpoint (form-urlencoded),
 *    parses access_token + refresh_token + id_token.email, persists account with `authType=oauth2`.
 * 3. `freshAccessToken()` — refreshes <1min expiry via `grant_type=refresh_token`.
 *
 * Fix for "browser closed but not signed in":
 * - Intent-filter for `com.vayunmathur.email://oauth` now exists (was missing) + bare `/oauth` path variant
 * - Fallback `extractQueryParam()` for OEMs that lose query in single-slash URIs
 * - Explicit error logging of `error`, `error_description` from Microsoft, clearing stale prefs only after failure
 * - Email from id_token (`preferred_username` for Entra) + fallback to emailHint if user typed it
 * - CustomTabs failure fallback to VIEW intent + FLAG_ACTIVITY_NEW_TASK
 */
object OutlookOAuth {
    private const val TAG = "OutlookOAuth"
    private const val AUTH_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
    private const val TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    private const val PREFS = "outlook_oauth"
    private const val TOKEN_REFRESH_SKEW_MS = 60_000L
    private const val TOKEN_TIMEOUT_MS = 20_000
    private const val DEFAULT_EXPIRES_IN_SECONDS = 3600L
    private const val HTTP_SUCCESS_MIN = 200
    private const val HTTP_SUCCESS_MAX = 299
    private const val MILLIS_PER_SECOND = 1000L
    private const val DEFAULT_OAUTH_REDIRECT_URI = "com.vayunmathur.email://oauth"
    private const val CODE_KEY = "code"
    private const val REFRESH_TOKEN_KEY = "refresh_token"
    private const val CODE_VERIFIER_KEY = "code_verifier"

    private fun redactedFormKeys(form: Map<String, String>): List<String> =
        form.keys.filter { it != CODE_KEY && it != REFRESH_TOKEN_KEY && it != CODE_VERIFIER_KEY }

    private val SCOPES = listOf(
        "https://outlook.office.com/IMAP.AccessAsUser.All",
        "https://outlook.office.com/SMTP.Send",
        "offline_access",
        "openid",
        "email",
        "profile",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Test seam: when set, token POSTs go to this endpoint instead of
     * Microsoft's. Null (the default everywhere in production) keeps the
     * real endpoint; tests point it at a local fake token server.
     */
    internal var tokenEndpointOverride: String? = null

    fun isConfigured(): Boolean = BuildConfig.OUTLOOK_OAUTH_CLIENT_ID.isNotBlank()

    fun start(context: Context, emailHint: String = "") {
        if (!isConfigured()) return
        val verifier = randomUrlSafe(64)
        val challenge = codeChallenge(verifier)
        val state = randomUrlSafe(24)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("verifier", verifier)
            putString("state", state)
            putString("emailHint", emailHint)
        }

        val redirectUri = BuildConfig.OUTLOOK_REDIRECT_URI.ifBlank {
            BuildConfig.OAUTH_REDIRECT_URI.ifBlank { DEFAULT_OAUTH_REDIRECT_URI }
        }

        val url = AUTH_ENDPOINT.toUri().buildUpon()
            .appendQueryParameter("client_id", BuildConfig.OUTLOOK_OAUTH_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .apply {
                if (emailHint.isNotBlank()) appendQueryParameter("login_hint", emailHint)
            }
            .build()

        Log.dev(TAG, "Starting Outlook OAuth -> $url redirect=$redirectUri")
        try {
            CustomTabsIntent.Builder().build().apply {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }.launchUrl(context, url)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.status(TAG, "CustomTabs failed, fallback to VIEW: ${e.message}")
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, url).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }

    sealed class OAuthResult {
        data class Success(val email: String) : OAuthResult()
        data class Failure(
            val reason: String,
            val error: String? = null,
            val errorDescription: String? = null,
        ) : OAuthResult()
    }

    suspend fun complete(context: Context, redirect: Uri): OAuthResult {
        val rawStr = redirect.toString()
        Log.dev(
            TAG,
            "complete redirect=$redirect host=${redirect.host} " +
                "path=${redirect.path} query=${redirect.query} raw=$rawStr",
        )

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val verifier = prefs.getString("verifier", null)
        if (verifier == null) {
            Log.error(TAG, "No verifier — prefs $PREFS missing; wrong flow or cleared?")
            return OAuthResult.Failure("No PKCE verifier found — please try signing in again")
        }
        val session = OAuthSession(
            verifier = verifier,
            expectedState = prefs.getString("state", null),
            emailHint = prefs.getString("emailHint", "") ?: "",
        )

        val code = redirect.getQueryParameter("code") ?: extractQueryParam(rawStr, "code")
        if (code == null) {
            return abortForMissingCode(prefs, redirect, rawStr)
        }

        val returnedState = redirect.getQueryParameter("state") ?: extractQueryParam(rawStr, "state")
        if (session.expectedState != null && returnedState != null && returnedState != session.expectedState) {
            Log.error(TAG, "State mismatch exp=${session.expectedState} got=$returnedState")
            prefs.edit { clear() }
            return OAuthResult.Failure(
                "State mismatch — possible CSRF, please retry",
                "state_mismatch",
                "expected=${session.expectedState} got=$returnedState",
            )
        }

        val exchangeResult = exchangeCode(code, session.verifier)
        val tokens = exchangeResult.tokens
        if (tokens == null) {
            prefs.edit { clear() }
            return exchangeFailure(exchangeResult)
        }

        prefs.edit { clear() }

        val email = tokens.idTokenEmail ?: session.emailHint.takeIf { it.contains("@") }
        if (email.isNullOrBlank()) {
            Log.error(TAG, "No email from id_token, hint='${session.emailHint}'")
            return OAuthResult.Failure(
                "No email found in id_token — try entering your email before signing in",
                "no_email_in_id_token",
                null,
            )
        }

        persistAccount(context, email, tokens)
        Log.dev(TAG, "Outlook persisted: $email")
        return OAuthResult.Success(email)
    }

    private data class OAuthSession(
        val verifier: String,
        val expectedState: String?,
        val emailHint: String,
    )

    private fun abortForMissingCode(
        prefs: android.content.SharedPreferences,
        redirect: Uri,
        rawStr: String,
    ): OAuthResult.Failure {
        val err = redirect.getQueryParameter("error") ?: extractQueryParam(rawStr, "error")
        val desc = redirect.getQueryParameter("error_description") ?: extractQueryParam(rawStr, "error_description")
        Log.error(TAG, "No code, error=$err desc=$desc raw=$rawStr")
        if (err != null) prefs.edit { clear() }
        val reason = err ?: "No authorization code from Microsoft"
        return OAuthResult.Failure(reason, err, desc)
    }

    private suspend fun exchangeCode(code: String, verifier: String): ExchangeResult {
        return exchangeWithError(
            mapOf(
                "client_id" to BuildConfig.OUTLOOK_OAUTH_CLIENT_ID,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to BuildConfig.OUTLOOK_REDIRECT_URI.ifBlank {
                    BuildConfig.OAUTH_REDIRECT_URI.ifBlank { "com.vayunmathur.email://oauth" }
                },
                "code_verifier" to verifier,
                "scope" to SCOPES.joinToString(" "),
            ),
        )
    }

    private fun exchangeFailure(result: ExchangeResult): OAuthResult.Failure {
        Log.error(TAG, "Token exchange failed: ${result.error} desc=${result.errorDescription} raw=${result.rawBody}")
        val reason = result.error ?: "Token exchange failed"
        return OAuthResult.Failure(reason, result.error, result.errorDescription ?: result.rawBody)
    }

    private suspend fun persistAccount(context: Context, email: String, tokens: Tokens) {
        val account = EmailAccount(
            email = email,
            provider = PROVIDER_OUTLOOK,
            imapHost = "outlook.office365.com",
            imapPort = 993,
            imapUseSsl = true,
            smtpHost = "smtp-mail.outlook.com",
            smtpPort = 587,
            smtpUseSsl = false,
            authType = "oauth2",
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = tokens.expiresAtMs,
        )
        EmailRepository.get(context).getDatabase().accountDao().insertAccount(account)
        EmailSyncWorker.scheduleHourlyNonInboxSync(context)
        EmailSyncWorker.runOneOffSync(context)
        ImapIdleService.start(context)
    }

    suspend fun freshAccessToken(context: Context, account: EmailAccount): String? {
        val tokenFresh = account.expiresAt > System.currentTimeMillis() + TOKEN_REFRESH_SKEW_MS
        if (tokenFresh && account.accessToken.isNotBlank()) {
            return account.accessToken
        }
        val refresh = account.refreshToken?.takeIf { it.isNotBlank() } ?: return account.accessToken.ifBlank { null }

        val tokens = exchange(
            mapOf(
                "client_id" to BuildConfig.OUTLOOK_OAUTH_CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "redirect_uri" to BuildConfig.OUTLOOK_REDIRECT_URI.ifBlank {
                    BuildConfig.OAUTH_REDIRECT_URI.ifBlank { DEFAULT_OAUTH_REDIRECT_URI }
                },
                "scope" to SCOPES.joinToString(" "),
            ),
        ) ?: return account.accessToken.ifBlank { null }

        val updated = account.copy(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: account.refreshToken,
            expiresAt = tokens.expiresAtMs,
        )
        EmailRepository.get(context).getDatabase().accountDao().insertAccount(updated)
        return updated.accessToken
    }

    private data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
        val idTokenEmail: String?,
    )
    private data class ExchangeResult(
        val tokens: Tokens?,
        val error: String?,
        val errorDescription: String?,
        val rawBody: String?,
    )

    private suspend fun exchange(form: Map<String, String>): Tokens? = exchangeWithError(form).tokens

    private suspend fun exchangeWithError(form: Map<String, String>): ExchangeResult = withContext(Dispatchers.IO) {
        try {
            val (respCode, text) = postTokenForm(form)
            Log.dev(
                TAG,
                "token $respCode " +
                    "body=$text formKeys=${redactedFormKeys(form)}",
            )
            if (respCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                return@withContext failedExchange(respCode, text)
            }
            parseTokenResponse(text)
        } catch (e: IOException) {
            Log.error(TAG, "exchange IO failure", e)
            ExchangeResult(null, e.javaClass.simpleName, e.message, null)
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "exchange parse failure", e)
            ExchangeResult(null, e.javaClass.simpleName, e.message, null)
        }
    }

    private fun postTokenForm(form: Map<String, String>): Pair<Int, String> {
        val body = form.entries.joinToString("&") { "${Uri.encode(it.key)}=${Uri.encode(it.value)}" }
        val conn = (URL(tokenEndpointOverride ?: TOKEN_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TOKEN_TIMEOUT_MS
            readTimeout = TOKEN_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val respCode = conn.responseCode
        val text = if (respCode in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        return respCode to text
    }

    private fun failedExchange(respCode: Int, text: String): ExchangeResult {
        Log.error(TAG, "token exchange $respCode: $text")
        var err: String? = null
        var errDesc: String? = null
        try {
            val root = json.parseToJsonElement(text) as? JsonObject
            err = root?.get("error")?.jsonPrimitive?.contentOrNull()
            errDesc = root?.get("error_description")?.jsonPrimitive?.contentOrNull()
        } catch (_: Exception) {}
        return ExchangeResult(null, err, errDesc, text)
    }

    private fun parseTokenResponse(text: String): ExchangeResult {
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: return ExchangeResult(null, "invalid_json", text, text)
        val access = root["access_token"]?.jsonPrimitive?.contentOrNull()
            ?: return ExchangeResult(
                null,
                root["error"]?.jsonPrimitive?.contentOrNull() ?: "no_access_token",
                root["error_description"]?.jsonPrimitive?.contentOrNull() ?: text,
                text,
            )
        val expiresIn = parseExpiresIn(root)
        return ExchangeResult(
            Tokens(
                access,
                root["refresh_token"]?.jsonPrimitive?.contentOrNull(),
                System.currentTimeMillis() + expiresIn * MILLIS_PER_SECOND,
                root["id_token"]?.jsonPrimitive?.contentOrNull()?.let { emailFromIdToken(it) },
            ),
            null,
            null,
            null,
        )
    }

    private fun parseExpiresIn(root: JsonObject): Long {
        return root["expires_in"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull()
            ?: root["expires_in"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
            ?: DEFAULT_EXPIRES_IN_SECONDS
    }

    private fun emailFromIdToken(idToken: String): String? = try {
        val payload = idToken.split(".").getOrNull(1) ?: return null
        val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val obj = json.parseToJsonElement(decoded) as? JsonObject ?: return null
        (obj["email"] ?: obj["preferred_username"] ?: obj["upn"] ?: obj["unique_name"])?.jsonPrimitive?.contentOrNull()
    } catch (e: IllegalArgumentException) {
        Log.error(TAG, "id_token decode", e)
        null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()?.ifBlank { null }

    private fun extractQueryParam(rawUrl: String, key: String): String? = try {
        val qIdx = rawUrl.indexOf('?'); if (qIdx == -1) return null
        val hIdx = rawUrl.indexOf('#', qIdx)
        val query = if (hIdx == -1) rawUrl.substring(qIdx + 1) else rawUrl.substring(qIdx + 1, hIdx)
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq != -1) {
                val k = Uri.decode(pair.substring(0, eq))
                if (k == key) return Uri.decode(pair.substring(eq + 1))
            }
        }
        null
    } catch (_: Exception) { null }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
