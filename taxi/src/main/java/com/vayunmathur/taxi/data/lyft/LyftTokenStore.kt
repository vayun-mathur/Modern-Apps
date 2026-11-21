package com.vayunmathur.taxi.data.lyft

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.taxi.network.lyft.LyftToken
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.lyftDataStore by preferencesDataStore(name = "lyft_session")

/**
 * Lyft token persistence — the single choke point for Lyft secrets in this app.
 *
 * Access and refresh tokens are stored Keystore-sealed (see [KeystoreTokenSealer]):
 * only ciphertext reaches the DataStore file. Backup is already deny-all for this
 * app, and sealing additionally keeps the tokens useless if the file is ever copied
 * off-device. A sealed value that no longer decrypts reads as signed-out. Legacy
 * plaintext tokens from before sealing are used once and rewritten sealed on read.
 * The user id is not a secret and stays plain.
 */
class LyftTokenStore(private val context: Context) {
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val userIdKey = stringPreferencesKey("user_id")

    private val defaultSealer by lazy { KeystoreTokenSealer(KEY_ALIAS) }

    private fun activeSealer(): TokenSealer = sealerForTest ?: defaultSealer

    suspend fun save(token: LyftToken) {
        // Seal before touching the store: never persist a token in plaintext, and
        // keep the previous value rather than a half-written session on seal failure.
        val sealedAccess = activeSealer().seal(token.accessToken)
        if (sealedAccess == null) {
            Log.error(TAG, "Sealing failed; Lyft access token not persisted")
            return
        }
        val sealedRefresh = token.refreshToken?.takeIf { it.isNotBlank() }?.let { plain ->
            activeSealer().seal(plain) ?: run {
                Log.error(TAG, "Sealing failed; Lyft refresh token not persisted")
                return
            }
        }
        context.lyftDataStore.edit {
            it[accessKey] = sealedAccess
            if (sealedRefresh != null) it[refreshKey] = sealedRefresh
            it[expiresAtKey] = System.currentTimeMillis() + token.expiresIn * MILLIS_PER_SECOND
            if (!token.userId.isNullOrBlank()) it[userIdKey] = token.userId
        }
    }

    // A corrupt preferences file throws (CorruptionException/IOException) on every
    // read; that must read as "signed out", not crash every caller.
    private suspend fun prefs() = try {
        context.lyftDataStore.data.first()
    } catch (_: Exception) {
        null
    }

    /**
     * Unseals a stored token: legacy plaintext passes through and is rewritten
     * sealed (verified against the value just read, inside the edit); an
     * undecryptable sealed value yields null — signed-out, never a crash.
     */
    private suspend fun unsealOrMigrate(
        key: androidx.datastore.preferences.core.Preferences.Key<String>,
        raw: String?,
    ): String? {
        val stored = raw?.takeIf { it.isNotBlank() } ?: return null
        val plain = activeSealer().unseal(stored) ?: return null
        if (!activeSealer().isSealed(stored)) {
            val sealed = activeSealer().seal(plain)
            if (sealed != null) {
                context.lyftDataStore.edit { prefs ->
                    if (prefs[key] == stored) prefs[key] = sealed
                }
            }
        }
        return plain.takeIf { it.isNotBlank() }
    }

    suspend fun accessToken(): String? = unsealOrMigrate(accessKey, prefs()?.get(accessKey))

    suspend fun refreshToken(): String? = unsealOrMigrate(refreshKey, prefs()?.get(refreshKey))

    suspend fun userId(): String? = prefs()?.get(userIdKey)

    /** Treats the token as expired a minute early so a call never races the expiry. */
    suspend fun isExpired(): Boolean {
        val expiresAt = prefs()?.get(expiresAtKey) ?: return true
        return System.currentTimeMillis() >= expiresAt - EXPIRY_SKEW_MS
    }

    suspend fun isSignedIn(): Boolean = accessToken() != null

    // --- Pending ride offer -------------------------------------------------
    // Persisted before createRide so process death mid-booking loses nothing:
    // on the next launch the offer is reconciled against activeRide() before any
    // new booking is attempted (see PendingRideOffer / pendingRideAction).

    private val pendingRideKey = stringPreferencesKey("pending_ride_offer")

    suspend fun savePendingRide(pending: PendingRideOffer) {
        val json = pendingJson.encodeToString(PendingRideOffer.serializer(), pending)
        context.lyftDataStore.edit { it[pendingRideKey] = json }
    }

    /** The persisted pending offer, or null when none/corrupt — corrupt reads as no pending ride. */
    suspend fun pendingRide(): PendingRideOffer? {
        val raw = prefs()?.get(pendingRideKey)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { pendingJson.decodeFromString(PendingRideOffer.serializer(), raw) }.getOrNull()
    }

    suspend fun clearPendingRide() {
        context.lyftDataStore.edit { it.remove(pendingRideKey) }
    }

    suspend fun clear() {
        context.lyftDataStore.edit { it.clear() }
    }

    companion object {
        private const val KEY_ALIAS = "taxi_lyft_token_key"
        private const val TAG = "LyftTokenStore"

        /** Test seam: Robolectric has no AndroidKeyStore; tests install a fake sealer here. */
        internal var sealerForTest: TokenSealer? = null

        private val pendingJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        private const val MILLIS_PER_SECOND = 1000L
        /** How early before real expiry the token counts as expired, so a call never races it. */
        private const val EXPIRY_SKEW_MS = 60_000L
    }
}
