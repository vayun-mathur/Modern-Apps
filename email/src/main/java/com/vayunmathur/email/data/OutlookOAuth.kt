package com.vayunmathur.email.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.vayunmathur.email.BuildConfig
import com.vayunmathur.email.EmailAccount
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
 * Outlook / Microsoft 365 OAuth2 (Authorization Code + PKCE). Microsoft has
 * disabled basic auth (app passwords) for IMAP/SMTP, so Outlook accounts sign in
 * here and authenticate to the mail servers with XOAUTH2 access tokens.
 *
 * Public client — no secret ships in the APK (PKCE). The client ID is a public
 * identifier baked into [BuildConfig] so the build stays reproducible.
 */
object OutlookOAuth {
    private const val TAG = "OutlookOAuth"
    private const val AUTH_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
    private const val TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    private const val PREFS = "outlook_oauth"

    /** IMAP/SMTP XOAUTH2 scopes + OIDC scopes so we can read the account address. */
    private val SCOPES = listOf(
        "https://outlook.office.com/IMAP.AccessAsUser.All",
        "https://outlook.office.com/SMTP.Send",
        "offline_access",
        "openid",
        "email",
        "profile",
    )

    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = BuildConfig.OUTLOOK_OAUTH_CLIENT_ID.isNotBlank()

    /** Launch the Microsoft sign-in page in a Custom Tab. */
    fun start(context: Context) {
        if (!isConfigured) return
        val verifier = randomUrlSafe(64)
        val challenge = codeChallenge(verifier)
        val state = randomUrlSafe(24)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("verifier", verifier)
            .putString("state", state)
            .apply()

        val url = Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.OUTLOOK_OAUTH_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", BuildConfig.OUTLOOK_REDIRECT_URI)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
        CustomTabsIntent.Builder().build().launchUrl(context, url)
    }

    /** Handle the redirect: exchange the code, persist the account. Returns the email on success. */
    suspend fun complete(context: Context, redirect: Uri): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val verifier = prefs.getString("verifier", null) ?: return null
        val expectedState = prefs.getString("state", null)
        val code = redirect.getQueryParameter("code") ?: return null
        if (redirect.getQueryParameter("state") != expectedState) {
            Log.e(TAG, "OAuth state mismatch")
            return null
        }

        val tokens = exchange(
            mapOf(
                "client_id" to BuildConfig.OUTLOOK_OAUTH_CLIENT_ID,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to BuildConfig.OUTLOOK_REDIRECT_URI,
                "code_verifier" to verifier,
                "scope" to SCOPES.joinToString(" "),
            ),
        ) ?: return null
        prefs.edit().clear().apply()

        val email = tokens.idTokenEmail ?: return null
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
        EmailDatabase.getInstance(context).emailDao().insertAccount(account)
        EmailSyncWorker.schedulePeriodicSync(context)
        EmailSyncWorker.runOneOffSync(context)
        ImapIdleService.start(context)
        return email
    }

    /**
     * Return a valid access token for [account], refreshing (and persisting) it
     * first when it is within a minute of expiring.
     */
    suspend fun freshAccessToken(context: Context, account: EmailAccount): String? {
        if (account.expiresAt > System.currentTimeMillis() + 60_000) return account.accessToken
        val refresh = account.refreshToken?.takeIf { it.isNotBlank() } ?: return account.accessToken.ifBlank { null }
        val tokens = exchange(
            mapOf(
                "client_id" to BuildConfig.OUTLOOK_OAUTH_CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "redirect_uri" to BuildConfig.OUTLOOK_REDIRECT_URI,
                "scope" to SCOPES.joinToString(" "),
            ),
        ) ?: return account.accessToken.ifBlank { null }

        val updated = account.copy(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: account.refreshToken,
            expiresAt = tokens.expiresAtMs,
        )
        EmailDatabase.getInstance(context).emailDao().insertAccount(updated)
        return updated.accessToken
    }

    private data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
        val idTokenEmail: String?,
    )

    private suspend fun exchange(form: Map<String, String>): Tokens? = withContext(Dispatchers.IO) {
        try {
            val body = form.entries.joinToString("&") {
                "${Uri.encode(it.key)}=${Uri.encode(it.value)}"
            }
            val conn = (URL(TOKEN_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val text = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.e(TAG, "token endpoint ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.readText()}")
                return@withContext null
            }
            val root = json.parseToJsonElement(text) as? JsonObject ?: return@withContext null
            val access = root["access_token"]?.jsonPrimitive?.contentOrNull() ?: return@withContext null
            val expiresIn = root["expires_in"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 3600L
            Tokens(
                accessToken = access,
                refreshToken = root["refresh_token"]?.jsonPrimitive?.contentOrNull(),
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000,
                idTokenEmail = root["id_token"]?.jsonPrimitive?.contentOrNull()?.let { emailFromIdToken(it) },
            )
        } catch (e: Exception) {
            Log.e(TAG, "token exchange failed", e)
            null
        }
    }

    /** Pull the address out of the OIDC id_token (email, else preferred_username). */
    private fun emailFromIdToken(idToken: String): String? {
        return try {
            val payload = idToken.split(".").getOrNull(1) ?: return null
            val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val obj = json.parseToJsonElement(decoded) as? JsonObject ?: return null
            (obj["email"] ?: obj["preferred_username"] ?: obj["upn"])?.jsonPrimitive?.contentOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "id_token decode failed", e)
            null
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()?.ifBlank { null }

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
