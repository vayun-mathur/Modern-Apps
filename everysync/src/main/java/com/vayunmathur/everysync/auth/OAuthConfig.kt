package com.vayunmathur.everysync.auth

import com.vayunmathur.everysync.BuildConfig

/**
 * OAuth2 endpoints + scopes for a provider.
 *
 * Everything here is safe to commit — client IDs are public identifiers, not
 * secrets, so builds stay reproducible. All providers are public PKCE clients;
 * no client secret is ever shipped.
 */
data class OAuthConfig(
    val authEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val scopes: List<String>,
    /** Extra query params appended to the authorization request. */
    val extraAuthParams: Map<String, String> = emptyMap(),
    /** Redirect URI for this provider (app-id custom scheme by default). */
    val redirectUri: String = REDIRECT_URI,
) {
    val hasClientId: Boolean get() = clientId.isNotBlank()

    companion object {
        // iOS-type Google OAuth client → custom URI scheme redirect. The scheme is
        // the app id (registered as the iOS client's "bundle ID" in Google Cloud).
        // Per Google's installed-app spec the path uses a single slash. iOS clients
        // do NOT accept https redirects (that's Web-client only), and Android-type
        // clients don't allow custom schemes at all — hence the iOS client.
        const val REDIRECT_URI = "com.vayunmathur.everysync:/oauth"

        val GOOGLE = OAuthConfig(
            authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenEndpoint = "https://oauth2.googleapis.com/token",
            clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
            scopes = listOf(
                "openid",
                "email",
                "https://www.googleapis.com/auth/carddav",
                "https://www.googleapis.com/auth/calendar",
            ),
            // Force a refresh token to be returned on first consent.
            extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent"),
        )

        // Google Health via the Google Health API v4 (health.googleapis.com) —
        // same Google OAuth client, read-only Google Health scopes. Replaces the
        // wound-down Google Fitness REST API and its fitness.* scopes.
        val GOOGLE_HEALTH = OAuthConfig(
            authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenEndpoint = "https://oauth2.googleapis.com/token",
            clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
            scopes = listOf(
                "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
                "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
                "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
                "https://www.googleapis.com/auth/googlehealth.nutrition.readonly",
            ),
            extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent"),
        )
    }
}
