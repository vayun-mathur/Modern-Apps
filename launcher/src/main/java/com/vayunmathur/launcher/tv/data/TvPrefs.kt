package com.vayunmathur.launcher.tv.data

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
    /** Optional settings shortcuts by the clock (`bluetooth`, `sound`, `picture`). */
    val quickSettings: List<String> = emptyList(),
)

private val Context.tvDataStore by preferencesDataStore(name = "tvlauncher_config")

/** DataStore-backed persistence for [TvSettings] (the BackupConfig pattern, not Room). */
class TvPrefs(private val context: Context) {
    val settings: Flow<TvSettings> = context.tvDataStore.data.map { p ->
        TvSettings(
            appOrder = p[KEY_APPS]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() },
            quickSettings = p[KEY_QUICK]
                ?.split(',')
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
        )
    }

    /** Saves the top row as the app list, so it no longer follows the defaults. */
    suspend fun saveOrder(order: List<String>) {
        context.tvDataStore.edit { it[KEY_APPS] = order.joinToString(",") }
    }

    suspend fun saveQuick(quick: List<String>) {
        context.tvDataStore.edit { it[KEY_QUICK] = quick.joinToString(",") }
    }

    /** Shared constants and helpers for TvPrefs. */
    companion object {
        private val KEY_APPS = stringPreferencesKey("apps")
        private val KEY_QUICK = stringPreferencesKey("quick")
    }
}

