package com.vayunmathur.games.hub.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vayunmathur.games.hub.platform.GameLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which of [packagesByGameId]'s games are installed, or null until the first check finishes.
 *
 * The check runs on [Dispatchers.IO] (package-manager calls block), and is redone whenever the
 * candidates change and every time the screen resumes - so a game installed from the store, or
 * uninstalled in Settings, while the hub was in the background shows up correctly on return.
 */
@Composable
fun rememberInstalledGameIds(packagesByGameId: Map<String, String>): Set<String>? {
    val context = LocalContext.current.applicationContext
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val installed by produceState<Set<String>?>(initialValue = null, packagesByGameId, resumes) {
        value = withContext(Dispatchers.IO) { GameLauncher.installedGameIds(context, packagesByGameId) }
    }
    return installed
}
