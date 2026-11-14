package com.vayunmathur.uniflix.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.uniflixAddonStore by preferencesDataStore(name = "uniflix_addons")

/**
 * Addon selection state. Ordering lives in Room ([AddonEntity.priority]);
 * only the default addon id lives here (null = first enabled wins).
 */
class AddonPrefs(private val context: Context) {
    val defaultAddonDbId: Flow<Long?> =
        context.uniflixAddonStore.data.map { it[KEY_DEFAULT_ADDON] }

    suspend fun setDefaultAddonDbId(dbId: Long?) {
        context.uniflixAddonStore.edit {
            if (dbId == null) it.remove(KEY_DEFAULT_ADDON) else it[KEY_DEFAULT_ADDON] = dbId
        }
    }

    /** Shared constants and helpers for AddonPrefs. */
    companion object {
        private val KEY_DEFAULT_ADDON = longPreferencesKey("default_addon_db_id")
    }
}
