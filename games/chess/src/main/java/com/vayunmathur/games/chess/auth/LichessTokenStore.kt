package com.vayunmathur.games.chess.auth

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Persists the Lichess OAuth access token in the app's DataStore.
 *
 * The token authenticates every Board API call as a Bearer header via [authHeaders]; [clear]
 * drops it on sign-out or on [LichessAuthExpired] (HTTP 401). The lobby reads [usernameNow] for
 * its signed-in header without a network round-trip.
 *
 * The stored token is sealed with a non-exportable Android Keystore key (see
 * [KeystoreTokenSealer]): only ciphertext ever reaches the DataStore file, so the token
 * stays useless if that file leaves the device via backup or device transfer. A sealed
 * value that no longer decrypts (restored on a new device, lost key) reads as signed-out.
 * Legacy plaintext tokens from before sealing are used once and rewritten sealed.
 */
class LichessTokenStore(context: Context) {

    private val store = DataStoreUtils.getInstance(context.applicationContext)

    /** Fire-and-forget legacy-migration rewrites triggered from the [token] Flow. */
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Shared constants and helpers for LichessTokenStore. */
    companion object {
        private const val KEY_ACCESS_TOKEN = "lichess_access_token"
        private const val KEY_USERNAME = "lichess_username"
        private const val KEY_USER_ID = "lichess_user_id"

        private const val KEY_ALIAS = "games_chess_lichess_token_key"
        private const val AUTH_SCHEME = "Bearer "
        private const val TAG = "LichessTokenStore"

        /** Test seam: Robolectric has no AndroidKeyStore; tests install a fake sealer here. */
        internal var sealerForTest: TokenSealer? = null
    }

    /** Emits the unsealed access token when set; silent before sign-in / after [clear]. */
    val token: Flow<String> = store.stringFlow(KEY_ACCESS_TOKEN).map { raw -> unsealOrEmpty(raw) }

    /** Emits the verified Lichess username from the last `/api/account` check. */
    val username: Flow<String> = store.stringFlow(KEY_USERNAME)

    /**
     * Unseals a stored token value for emission: empty stays empty, an undecryptable
     * sealed value becomes empty (signed-out), and a legacy plaintext value is returned
     * as-is with a sealed rewrite queued (idempotent migration on first read).
     */
    private fun unsealOrEmpty(raw: String): String {
        if (raw.isEmpty()) return ""
        val plain = activeSealer().unseal(raw) ?: return ""
        if (!activeSealer().isSealed(raw)) queueMigration(raw, plain)
        return plain
    }

    /** Rewrites a legacy plaintext token sealed, but only if it is still the stored value. */
    private fun queueMigration(legacyRaw: String, plain: String) {
        migrationScope.launch {
            val sealed = activeSealer().seal(plain) ?: return@launch
            if (store.getStringAwait(KEY_ACCESS_TOKEN) == legacyRaw) {
                store.setString(KEY_ACCESS_TOKEN, sealed)
            }
        }
    }

    private val defaultSealer by lazy { KeystoreTokenSealer(KEY_ALIAS) }

    private fun activeSealer(): TokenSealer = sealerForTest ?: defaultSealer

    /** Snapshot for one-shot reads (sign-in finish, API header build, VM init). */
    suspend fun tokenNow(): String? {
        val raw = store.getStringAwait(KEY_ACCESS_TOKEN)?.takeIf { it.isNotEmpty() } ?: return null
        val plain = activeSealer().unseal(raw) ?: return null
        if (!activeSealer().isSealed(raw)) {
            // Legacy plaintext: rewrite sealed, verifying the value has not changed since the read.
            val sealed = activeSealer().seal(plain)
            if (sealed != null && store.getStringAwait(KEY_ACCESS_TOKEN) == raw) {
                store.setString(KEY_ACCESS_TOKEN, sealed)
            }
        }
        return plain.takeIf { it.isNotEmpty() }
    }

    /** Snapshot of the stored username (lobby header without a network round-trip). */
    suspend fun usernameNow(): String? = store.getStringAwait(KEY_USERNAME)?.takeIf { it.isNotEmpty() }

    /** Authorization header for Lichess API calls; empty when signed out. */
    suspend fun authHeaders(): Map<String, String> {
        val token = tokenNow() ?: return emptyMap()
        return mapOf("Authorization" to AUTH_SCHEME + token)
    }

    suspend fun saveToken(token: String) {
        if (token.isEmpty()) {
            store.setString(KEY_ACCESS_TOKEN, "")
            return
        }
        val sealed = activeSealer().seal(token)
        if (sealed == null) {
            // Never persist the token in plaintext; a failed seal keeps the previous value.
            Log.error(TAG, "Sealing failed; Lichess token not persisted")
            return
        }
        store.setString(KEY_ACCESS_TOKEN, sealed)
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
