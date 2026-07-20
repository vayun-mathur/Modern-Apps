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
        val client = GoogleHealthClient(token)
        val account = config.accountName
        val now = System.currentTimeMillis()

        // 1. Forward window: pull everything new since the last run (with a small
        // overlap so late-arriving data isn't missed). Idempotent via clientRecordId.
        val watermark = SyncState.get(context, account, KEY_WATERMARK)?.toLongOrNull()
        val recentFrom = (watermark?.minus(RECENT_OVERLAP_MILLIS) ?: (now - RECENT_SEED_DAYS * DAY_MILLIS))
            .coerceAtLeast(0)
        Log.i(TAG, "sync $account: recent window [$recentFrom, $now]")
        HealthSink.upsert(context, client.getMeasurements(recentFrom, now))
        SyncState.set(context, account, KEY_WATERMARK, now.toString())

        // 2. Backfill older history one chunk at a time, writing each chunk as it
        // lands so data appears progressively rather than after one huge request.
        // The cursor is persisted after every chunk, so if the worker is killed the
        // next run resumes where this one left off instead of restarting.
        val floor = now - BACKFILL_DAYS * DAY_MILLIS
        var cursor = SyncState.get(context, account, KEY_BACKFILL_CURSOR)?.toLongOrNull()
            ?: (now - RECENT_SEED_DAYS * DAY_MILLIS)
        while (cursor > floor) {
            val chunkStart = maxOf(cursor - BACKFILL_CHUNK_DAYS * DAY_MILLIS, floor)
            Log.i(TAG, "sync $account: backfill chunk [$chunkStart, $cursor]")
            HealthSink.upsert(context, client.getMeasurements(chunkStart, cursor))
            cursor = chunkStart
            SyncState.set(context, account, KEY_BACKFILL_CURSOR, cursor.toString())
        }
        Log.i(TAG, "sync $account: complete (backfilled to $cursor)")
    }

    companion object {
        private const val TAG = "GoogleHealthProvider"
        private val JSON = Json { ignoreUnknownKeys = true }
        private const val DAY_MILLIS = 86_400_000L
        private const val KEY_WATERMARK = "googlehealth_watermark"
        private const val KEY_BACKFILL_CURSOR = "googlehealth_backfill_cursor"
        private const val RECENT_SEED_DAYS = 30L // recent window pulled on the first sync
        private const val RECENT_OVERLAP_MILLIS = DAY_MILLIS // re-check the last day for late data
        private const val BACKFILL_CHUNK_DAYS = 30L // one window of history written per step
        private const val BACKFILL_DAYS = 365L // total history to backfill (1 year)
    }
}
