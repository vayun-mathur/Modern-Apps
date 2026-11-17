package com.vayunmathur.web.platform

import android.content.Context
import androidx.core.content.edit
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

private const val TAG = "WebViewModel"

// ---- Persistence ----

fun WebViewModel.onClearedPersist() { persistTabsSync() }

internal fun WebViewModel.persistTabs() {
    scope.launch(Dispatchers.IO) { persistTabsSync() }
}

internal fun WebViewModel.persistTabsSync() {
    // Incognito windows leave no trace on disk.
    if (incognito) return
    runCatching {
        val sp = context.getSharedPreferences("web_prefs", Context.MODE_PRIVATE)
        val toSave = tabs.filter { !it.isPrivate }
        sp.edit {
            putString(savedTabsKey, json.encodeToString(toSave))
            putString(activeTabKey, activeTabId)
        }
    }.onFailure { e -> Log.error(TAG, "persistTabs failed", e) }
}

internal fun WebViewModel.persistPrefs() {
    scope.launch(Dispatchers.IO) {
        runCatching {
            val sp = context.getSharedPreferences("web_prefs", Context.MODE_PRIVATE)
            sp.edit {
                putString(P_CACHE_MODE, cacheMode.name)
                putString(P_SEARCH_ENGINE, searchEngine.name)
                putBoolean(P_JS_ENABLED, jsEnabled)
                putBoolean(P_BLOCK_THIRD_PARTY, blockThirdPartyCookies)
                putBoolean(P_DESKTOP_MODE, desktopMode)
                putBoolean(P_SEARCH_BAR_BOTTOM, searchBarAtBottom)
                putString(P_SHIELD_LEVEL, shields.level?.name)
                putBoolean(P_SHIELD_TRACKERS, shields.blockTrackers != false)
                putBoolean(P_SHIELD_COSMETIC, shields.cosmeticFiltering != false)
                putBoolean(P_SHIELD_FINGERPRINT, shields.fingerprintProtection != false)
                putBoolean(P_SHIELD_HTTPS, shields.httpsUpgrade != false)
            }
        }.onFailure { e -> Log.error(TAG, "persistPrefs failed", e) }
    }
}
