package com.vayunmathur.everysync.provider

import android.content.Context
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.OAuthConfig
import com.vayunmathur.everysync.auth.OAuthTokens

/**
 * A cloud service EverySync can sync into the on-device system providers.
 *
 * Direct-cloud providers (Google, generic CalDAV/CardDAV, Apple/iCloud, Google
 * Health) make real network round-trips.
 */
interface SyncProvider {
    val id: String
    val displayName: String
    val iconRes: Int
    val authType: AuthType
    val capabilities: Set<DataType>

    /** OAuth configuration for [AuthType.OAUTH] providers, else null. */
    fun oauthConfig(): OAuthConfig? = null

    /** Preset DAV base URL for [AuthType.DAV] providers (e.g. iCloud), else null (user enters one). */
    val davPresetUrl: String? get() = null

    /** Whether this provider is serviced through Health Connect rather than a direct cloud API. */
    val viaHealthConnect: Boolean get() = false

    /**
     * Resolve a stable, human-readable account name from freshly obtained OAuth
     * tokens (e.g. the user's email). Default returns a short token fingerprint.
     */
    suspend fun resolveAccountName(context: Context, tokens: OAuthTokens): String =
        "$displayName (${tokens.accessToken.take(6)})"

    /** Run one sync pass for [config] in the requested [direction]. */
    suspend fun sync(context: Context, config: AccountConfig, direction: SyncDirection)
}
