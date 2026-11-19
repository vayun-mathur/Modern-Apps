package com.vayunmathur.taxi.data.lyft

import android.content.Context
import com.vayunmathur.taxi.network.lyft.LyftToken
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.lyftDataStore by preferencesDataStore(name = "lyft_session")

/** Lyft Token Store: a context implementation for lyft token store. */
class LyftTokenStore(private val context: Context) {
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val userIdKey = stringPreferencesKey("user_id")

    suspend fun save(token: LyftToken) {
        context.lyftDataStore.edit {
            it[accessKey] = token.accessToken
            if (!token.refreshToken.isNullOrBlank()) it[refreshKey] = token.refreshToken
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

    suspend fun accessToken(): String? =
        prefs()?.get(accessKey)?.takeIf { it.isNotBlank() }

    suspend fun refreshToken(): String? =
        prefs()?.get(refreshKey)?.takeIf { it.isNotBlank() }

    suspend fun userId(): String? = prefs()?.get(userIdKey)

    /** Treats the token as expired a minute early so a call never races the expiry. */
    suspend fun isExpired(): Boolean {
        val expiresAt = prefs()?.get(expiresAtKey) ?: return true
        return System.currentTimeMillis() >= expiresAt - EXPIRY_SKEW_MS
    }

    suspend fun isSignedIn(): Boolean = accessToken() != null

    suspend fun clear() {
        context.lyftDataStore.edit { it.clear() }
    }

    private companion object {
        private const val MILLIS_PER_SECOND = 1000L
        /** How early before real expiry the token counts as expired, so a call never races it. */
        private const val EXPIRY_SKEW_MS = 60_000L
    }
}
