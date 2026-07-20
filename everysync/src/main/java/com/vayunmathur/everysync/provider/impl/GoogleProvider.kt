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
import com.vayunmathur.everysync.remote.DavClient
import com.vayunmathur.everysync.remote.DavSync
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.ui.IconProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Google via CardDAV (contacts) + CalDAV (calendar), OAuth PKCE Bearer, two-way. */
class GoogleProvider : SyncProvider {
    override val id = "google"
    override val displayName = "Google"
    override val icon: @Composable () -> Unit = { IconProvider() }
    override val authType = AuthType.OAUTH
    override val capabilities = setOf(DataType.CONTACTS, DataType.CALENDAR)

    override fun oauthConfig(): OAuthConfig = OAuthConfig.GOOGLE

    override suspend fun resolveAccountName(context: Context, tokens: OAuthTokens): String {
        val email = fetchEmail(tokens.accessToken)
        return if (!email.isNullOrBlank()) "$email (Google)" else "Google account"
    }

    override suspend fun sync(context: Context, config: AccountConfig, direction: SyncDirection) {
        val token = OAuthManager.validAccessToken(context, config.accountName, id) ?: return
        val account = config.accountName
        val email = fetchEmail(token) ?: emailFromAccountName(account)
        if (email == null) {
            Log.e(TAG, "sync: could not resolve Google account email for '$account'")
            return
        }

        val client = DavClient { "Bearer $token" }

        if (DataType.CONTACTS in config.enabledTypes) {
            DavSync.syncContacts(
                context, account, client,
                "https://www.googleapis.com/carddav/v1/principals/$email/lists/default",
                direction,
            )
        }
        if (DataType.CALENDAR in config.enabledTypes) {
            // Google's CalDAV principal is /caldav/v2/$email/user, but that is not a
            // collection — if principal→home-set discovery fails, the fallback needs a
            // real calendar collection to list. Point at the primary calendar's events
            // collection so discovery always resolves at least the primary calendar
            // (secondary calendars still come through the principal/home-set chain).
            DavSync.syncCalendars(
                context, account, client,
                "https://apidata.googleusercontent.com/caldav/v2/$email/events",
                direction,
            )
        }
    }

    /** Fetch the account email from the OAuth userinfo endpoint. */
    private suspend fun fetchEmail(token: String): String? = try {
        val resp = NetworkClient.performRequest(
            "https://www.googleapis.com/oauth2/v3/userinfo", "GET",
            mapOf("Authorization" to "Bearer $token"),
        )
        (JSON.parseToJsonElement(resp.body) as? JsonObject)
            ?.get("email")?.jsonPrimitive?.content?.ifBlank { null }
    } catch (e: Exception) {
        Log.e(TAG, "fetchEmail failed", e)
        null
    }

    /** Fall back to parsing the account name, which has the form "email (Google)". */
    private fun emailFromAccountName(accountName: String): String? =
        accountName.substringBefore(" (Google)").takeIf { "@" in it }

    companion object {
        private const val TAG = "GoogleProvider"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
