package com.vayunmathur.games.chess.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.vayunmathur.library.network.NetworkClient
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lichess OAuth with PKCE (public client, no secret).
 *
 * Flow: [startSignIn] builds `https://lichess.org/oauth?...&scope=board:play` and opens it in a
 * Custom Tab; the `modernapps://lichess-callback?code=…&state=…` redirect re-enters the app
 * through `MainActivity.onNewIntent` → [LichessAuthBridge.onRedirect] → [LichessAuthBridge.redirects];
 * the online ViewModel validates `state` and calls [finishSignIn] (`POST /api/token`) via
 * [NetworkClient], persisting the access token in [LichessTokenStore].
 *
 * The verifier lives in memory only (never DataStore): a redirect that arrives after a process
 * death has no verifier to complete with, so it is dropped and the user signs in again.
 */
class LichessAuthManager(private val context: Context, private val tokenStore: LichessTokenStore) {

    companion object {
        private const val OAUTH_URL = "https://lichess.org/oauth"
        private const val TOKEN_URL = "https://lichess.org/api/token"
        private const val REDIRECT_URI = "modernapps://lichess-callback"
        private const val SCOPE = "board:play"

        /** PKCE verifier entropy (RFC 7636: 32 octets; 43–128 chars after base64url). */
        private const val VERIFIER_BYTES = 32

        /** `state` entropy: 16 bytes is unguessable and fits comfortably in a URL. */
        private const val STATE_BYTES = 16

        private val secureRandom = SecureRandom()

        private fun base64Url(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        /** RFC 7636 `S256`: `BASE64URL(SHA256(verifier))`. */
        private fun codeChallenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return base64Url(digest)
        }

        private fun formEncode(params: Map<String, String>): String =
            params.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
    }

    /** One pending sign-in attempt; cleared on [startSignIn] and on completion. */
    private var pending: PendingAuth? = null

    private data class PendingAuth(val verifier: String, val state: String)

    /** Redirect URI query parsed out of the OAuth callback intent (null when not ours). */
    data class AuthRedirect(val code: String, val state: String) {
        companion object {
            fun from(intent: Intent): AuthRedirect? {
                val uri: Uri = intent.data ?: return null
                if (uri.scheme != "modernapps" || uri.host != "lichess-callback") return null
                val code = uri.getQueryParameter("code") ?: return null
                val state = uri.getQueryParameter("state") ?: return null
                return AuthRedirect(code, state)
            }
        }
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("token_type") val tokenType: String = "",
        @SerialName("expires_in") val expiresIn: Long = 0L,
    )

    /**
     * Opens the Lichess authorize page. Any earlier incomplete attempt is abandoned — the user
     * tapped sign-in again, so only the newest `state` may complete.
     */
    fun startSignIn() {
        val verifier = base64Url(ByteArray(VERIFIER_BYTES).also { secureRandom.nextBytes(it) })
        val state = base64Url(ByteArray(STATE_BYTES).also { secureRandom.nextBytes(it) })
        pending = PendingAuth(verifier, state)
        val url = OAUTH_URL + "?" + formEncode(
            mapOf(
                "response_type" to "code",
                "client_id" to "modern-apps-chess",
                "redirect_uri" to REDIRECT_URI,
                "scope" to SCOPE,
                "code_challenge_method" to "S256",
                "code_challenge" to codeChallenge(verifier),
                "state" to state,
            )
        )
        CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
    }

    /**
     * Completes sign-in after the redirect: validates `state`, exchanges the code, persists the
     * token. Returns the username verified via `GET /api/account`, or throws:
     * [IllegalStateException] on `state` mismatch (possible CSRF — drop it) or
     * [java.io.IOException] from [NetworkClient] on transport/HTTP failure.
     */
    suspend fun finishSignIn(redirect: AuthRedirect): String {
        val attempt = pending ?: throw IllegalStateException("No pending Lichess sign-in")
        pending = null
        if (redirect.state != attempt.state) throw IllegalStateException("Lichess state mismatch")
        val response: TokenResponse = NetworkClient.callJson(
            TOKEN_URL,
            method = "POST",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = formEncode(
                mapOf(
                    "grant_type" to "authorization_code",
                    "redirect_uri" to REDIRECT_URI,
                    "client_id" to "modern-apps-chess",
                    "code" to redirect.code,
                    "code_verifier" to attempt.verifier,
                )
            ),
        )
        if (response.accessToken.isEmpty()) throw IllegalStateException("Empty Lichess access token")
        tokenStore.saveToken(response.accessToken)
        val profile: com.vayunmathur.games.chess.network.LichessProfile =
            com.vayunmathur.games.chess.network.LichessApi(tokenStore).account()
        tokenStore.saveUsername(profile.username)
        tokenStore.saveUserId(profile.id)
        return profile.username
    }
}

/**
 * Forwards the OAuth redirect intent to whoever is collecting it.
 *
 * The redirect re-enters [MainActivity] (singleTop → `onNewIntent`) outside composition, but the
 * completion logic lives in the online ViewModel; a repository-scoped channel would outlive the
 * screen, so the bridge is a process-scoped flow with no replay — a redirect with nothing
 * collecting (user left the Online tab mid-sign-in) is dropped.
 */
object LichessAuthBridge {
    private val _redirects = MutableSharedFlow<LichessAuthManager.AuthRedirect>(extraBufferCapacity = 1)
    val redirects: SharedFlow<LichessAuthManager.AuthRedirect> = _redirects.asSharedFlow()

    fun onRedirect(intent: Intent) {
        val redirect = LichessAuthManager.AuthRedirect.from(intent) ?: return
        _redirects.tryEmit(redirect)
    }
}
