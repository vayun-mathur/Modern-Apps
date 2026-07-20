package com.vayunmathur.everysync.provider.impl

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.OAuthConfig
import com.vayunmathur.everysync.auth.OAuthManager
import com.vayunmathur.everysync.auth.OAuthTokens
import com.vayunmathur.everysync.provider.AuthType
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.SyncDirection
import com.vayunmathur.everysync.provider.SyncProvider
import com.vayunmathur.everysync.provider.SyncState
import com.vayunmathur.everysync.remote.GoogleHealthClient
import com.vayunmathur.everysync.sink.HealthSink
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.ui.IconProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Google Health via the Google Health API v4 (OAuth PKCE). Pulls measurements
 * into Health Connect. Pull-dominant — the cloud is the source of truth.
 */
class GoogleHealthProvider : SyncProvider {
    override val id = "google_health"
    override val displayName = "Google Health"
    override val icon: @Composable () -> Unit = { IconProvider() }
    override val authType = AuthType.OAUTH
    override val capabilities = setOf(DataType.HEALTH)

    override fun oauthConfig(): OAuthConfig = OAuthConfig.GOOGLE_HEALTH

    override suspend fun resolveAccountName(context: Context, tokens: OAuthTokens): String {
        return try {
            val resp = NetworkClient.performRequest(
                "https://www.googleapis.com/oauth2/v3/userinfo", "GET",
                mapOf("Authorization" to "Bearer ${tokens.accessToken}"),
            )
            val email = (JSON.parseToJsonElement(resp.body) as? JsonObject)
                ?.get("email")?.jsonPrimitive?.content
            if (!email.isNullOrBlank()) "$email (Google Health)" else "Google Health"
        } catch (e: Exception) {
            Log.e(TAG, "resolveAccountName failed", e)
            "Google Health"
        }
    }

    override suspend fun sync(context: Context, config: AccountConfig, direction: SyncDirection) {
        if (DataType.HEALTH !in config.enabledTypes || direction == SyncDirection.PUSH) return
        val token = OAuthManager.validAccessToken(context, config.accountName, id) ?: return
        val since = SyncState.get(context, config.accountName, "googlehealth_since")?.toLongOrNull() ?: 0L
        HealthSink.upsert(context, GoogleHealthClient(token).getMeasurements(since))
        SyncState.set(context, config.accountName, "googlehealth_since", System.currentTimeMillis().toString())
    }

    companion object {
        private const val TAG = "GoogleHealthProvider"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
