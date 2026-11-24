package com.vayunmathur.launcher.wear.data

import android.content.Context
import com.vayunmathur.launcher.wear.domain.RecentLaunch
import com.vayunmathur.launcher.wear.domain.WearFavoritesSnapshot
import com.vayunmathur.launcher.wear.domain.WearLauncherWatchState
import com.vayunmathur.launcher.wear.domain.WearLauncherProtocol
import com.vayunmathur.launcher.wear.domain.recordRecentLaunch
import com.vayunmathur.launcher.wear.sync.WearLinkBridge
import com.vayunmathur.library.log.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/**
 * The watch's local truth: the last favorites snapshot from the phone plus
 * watch-local recents, persisted as JSON so the launcher renders offline.
 */
class WearLauncherStore private constructor(private val context: Context) {
    companion object {
        private const val TAG = "WearLauncherStore"

        @Volatile private var instance: WearLauncherStore? = null

        fun get(context: Context): WearLauncherStore = instance ?: synchronized(this) {
            instance ?: WearLauncherStore(context.applicationContext).also { instance = it }
        }
    }

    private val snapshotFile = File(context.filesDir, "wear-launcher-favorites.json")
    private val recentsFile = File(context.filesDir, "wear-launcher-recents.json")

    private val _snapshot = MutableStateFlow(loadSnapshot())
    val snapshot: StateFlow<WearFavoritesSnapshot> = _snapshot.asStateFlow()

    private val _recents = MutableStateFlow(loadRecents())
    val recents: StateFlow<List<RecentLaunch>> = _recents.asStateFlow()

    fun setSnapshot(snapshot: WearFavoritesSnapshot, installedCount: Int) {
        _snapshot.value = snapshot
        runCatching { snapshotFile.writeText(WearLauncherProtocol.json.encodeToString(snapshot)) }
            .onFailure { Log.status(TAG, "persist favorites failed: ${it.message}") }
        publishWatchState(installedCount)
    }

    fun recordLaunch(packageName: String) {
        val updated = recordRecentLaunch(_recents.value, packageName, System.currentTimeMillis())
        _recents.value = updated
        runCatching { recentsFile.writeText(WearLauncherProtocol.json.encodeToString(updated)) }
            .onFailure { Log.status(TAG, "persist recents failed: ${it.message}") }
    }

    fun publishWatchState(installedCount: Int = 0) {
        WearLinkBridge.publishWatchState(context, _snapshot.value, installedCount)
    }

    private fun loadSnapshot(): WearFavoritesSnapshot = runCatching {
        WearLauncherProtocol.json.decodeFromString<WearFavoritesSnapshot>(snapshotFile.readText())
    }.getOrDefault(WearFavoritesSnapshot())

    private fun loadRecents(): List<RecentLaunch> = runCatching {
        WearLauncherProtocol.json.decodeFromString<List<RecentLaunch>>(recentsFile.readText())
    }.getOrDefault(emptyList())
}
