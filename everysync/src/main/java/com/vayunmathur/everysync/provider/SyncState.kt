package com.vayunmathur.everysync.provider

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils

/**
 * Per-account, per-scope sync cursors (Google syncTokens, CalDAV ctags, Google
 * Fit lastupdate, Health Connect changes tokens) persisted in DataStore.
 */
object SyncState {
    private fun key(account: String, scope: String) = "syncstate_${account}_$scope"

    fun get(context: Context, account: String, scope: String): String? =
        DataStoreUtils.getInstance(context).getString(key(account, scope))?.ifBlank { null }

    suspend fun set(context: Context, account: String, scope: String, value: String?) {
        DataStoreUtils.getInstance(context).setString(key(account, scope), value ?: "")
    }
}
