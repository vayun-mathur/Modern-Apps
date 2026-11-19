package com.vayunmathur.taxi.data.uber

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.vayunmathur.taxi.network.uber.UberCookies
import kotlinx.coroutines.flow.first

private val Context.uberDataStore by preferencesDataStore(name = "uber_session")

/**
 * Whether we believe the WebView holds a logged-in Uber session.
 *
 * This is persisted rather than derived from cookies. `m.uber.com` sets `sid`, `csid`,
 * `jwt-session` and others on the *first page load*, before any sign-in, so no cookie-name
 * check can distinguish an anonymous visitor from an authenticated one — an earlier version
 * of this reported "Signed in" before the user had ever logged in.
 *
 * Instead [UberWebView] reports each page it lands on, and we flip the flag when navigation
 * settles somewhere that is not the auth host.
 */
class UberSession(private val context: Context) {
    private val signedInKey = booleanPreferencesKey("signed_in")

    suspend fun isSignedIn(): Boolean =
        // A corrupt preferences file throws on read; that reads as signed out.
        try {
            context.uberDataStore.data.first()[signedInKey] ?: false
        } catch (_: Exception) {
            false
        }

    suspend fun setSignedIn(value: Boolean) {
        context.uberDataStore.edit { it[signedInKey] = value }
    }

    suspend fun clear() {
        context.uberDataStore.edit { it.clear() }
        UberCookies.clear()
    }

    /** Shared constants and helpers for UberSession. */
    companion object {
        /** Hosts/paths that mean the user is still in the sign-in flow. */
        private val AUTH_MARKERS = listOf("auth.uber.com", "/login", "accounts.uber.com")

        fun looksAuthenticated(url: String): Boolean =
            url.startsWith("https://m.uber.com") && AUTH_MARKERS.none { url.contains(it) }
    }
}
