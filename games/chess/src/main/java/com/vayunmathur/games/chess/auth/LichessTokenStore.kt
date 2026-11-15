package com.vayunmathur.games.chess.auth

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.Flow

/**
 * Persists the Lichess OAuth access token in the app's DataStore.
 *
 * The token authenticates every Board API call as a Bearer header via [authHeaders]; [clear]
 * drops it on sign-out or on [LichessAuthExpired] (HTTP 401). The lobby reads [usernameNow] for
 * its signed-in header without a network round-trip.
 */
class LichessTokenStore(context: Context) {

    private val store = DataStoreUtils.getInstance(context.applicationContext)

    /** Shared constants and helpers for LichessTokenStore. */
    companion object {
        private const val KEY_ACCESS_TOKEN = "lichess_access_token"
        private const val KEY_USERNAME = "lichess_username"
        private const val KEY_USER_ID = "lichess_user_id"

        private const val AUTH_SCHEME = "Bearer "
    }

    /** Emits the stored access token when set; silent before sign-in / after [clear]. */
    val token: Flow<String> = store.stringFlow(KEY_ACCESS_TOKEN)

    /** Emits the verified Lichess username from the last `/api/account` check. */
    val username: Flow<String> = store.stringFlow(KEY_USERNAME)

    /** Snapshot for one-shot reads (sign-in finish, API header build, VM init). */
    suspend fun tokenNow(): String? = store.getStringAwait(KEY_ACCESS_TOKEN)?.takeIf { it.isNotEmpty() }

    /** Snapshot of the stored username (lobby header without a network round-trip). */
    suspend fun usernameNow(): String? = store.getStringAwait(KEY_USERNAME)?.takeIf { it.isNotEmpty() }

    /** Authorization header for Lichess API calls; empty when signed out. */
    suspend fun authHeaders(): Map<String, String> {
        val token = tokenNow() ?: return emptyMap()
        return mapOf("Authorization" to AUTH_SCHEME + token)
    }

    suspend fun saveToken(token: String) {
        store.setString(KEY_ACCESS_TOKEN, token)
    }

    suspend fun saveUsername(username: String) {
        store.setString(KEY_USERNAME, username)
    }

    suspend fun saveUserId(userId: String) {
        store.setString(KEY_USER_ID, userId)
    }

    /** Snapshot of the stored Lichess user id (resolves my color in `gameFull`). */
    suspend fun userIdNow(): String? = store.getStringAwait(KEY_USER_ID)?.takeIf { it.isNotEmpty() }

    /** Drops the token and username; the lobby returns to the signed-out state. */
    suspend fun clear() {
        store.setString(KEY_ACCESS_TOKEN, "")
        store.setString(KEY_USERNAME, "")
        store.setString(KEY_USER_ID, "")
    }
}
