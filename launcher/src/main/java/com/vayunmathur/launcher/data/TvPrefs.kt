package com.vayunmathur.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The persisted home-screen configuration: just the row order, like tv-detox's `apps`. */
data class TvSettings(
    /** Saved top-row packages in order, or null when the row still follows the defaults. */
    val appOrder: List<String>? = null,
)

private val Context.tvDataStore by preferencesDataStore(name = "tvlauncher_config")

/** DataStore-backed persistence for [TvSettings] (the BackupConfig pattern, not Room). */
class TvPrefs(private val context: Context) {
    // A legacy "quick" key in an existing store is ignored: DataStore tolerates
    // unknown keys, so removing its parsing stays backward-compatible.
    val settings: Flow<TvSettings> = context.tvDataStore.data.map { p ->
        TvSettings(
            appOrder = p[KEY_APPS]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() },
        )
    }

    /** Saves the top row as the app list, so it no longer follows the defaults. */
    suspend fun saveOrder(order: List<String>) {
        context.tvDataStore.edit { it[KEY_APPS] = order.joinToString(",") }
    }

    /** Shared constants and helpers for TvPrefs. */
    companion object {
        private val KEY_APPS = stringPreferencesKey("apps")
    }
}

