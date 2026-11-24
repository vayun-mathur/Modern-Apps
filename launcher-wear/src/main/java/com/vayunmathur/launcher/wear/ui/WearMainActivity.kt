package com.vayunmathur.launcher.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.launcher.wear.data.WearLauncherStore

/**
 * Watch launcher entry. This is a launcher-style app drawer, not the system
 * home: stock Wear OS reserves home for the system launcher (see manifest).
 * It renders from installed apps + the last synced favorites, fully offline.
 */
class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
        val store = WearLauncherStore.get(this)
        com.vayunmathur.launcher.wear.sync.WearLinkBridge.get(this)
        store.publishWatchState()
        setContent {
            MaterialTheme {
                var route by remember { mutableStateOf<WearRoute>(WearRoute.Home) }
                when (route) {
                    WearRoute.Home -> WearLauncherScreen(
                        store = store,
                        onOpenAllApps = { route = WearRoute.AllApps },
                        onOpenSettings = { route = WearRoute.Settings },
                    )
                    WearRoute.AllApps -> WearAllAppsScreen(
                        store = store,
                        onBack = { route = WearRoute.Home },
                    )
                    WearRoute.Settings -> WearSettingsScreen(
                        onBack = { route = WearRoute.Home },
                    )
                }
            }
        }
    }
}
