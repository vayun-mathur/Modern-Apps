package com.vayunmathur.launcher.wear.sync

import android.content.Context
import com.vayunmathur.launcher.wear.data.WearLauncherStore
import com.vayunmathur.launcher.wear.domain.WearFavoritesSnapshot
import com.vayunmathur.launcher.wear.domain.WearLauncherProtocol
import com.vayunmathur.launcher.wear.domain.WearLauncherWatchState
import com.vayunmathur.launcher.wear.platform.InstalledApps
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Watch side of WearLink for Launcher: favorites data items land in
 * [WearLauncherStore], watch state goes back as a data item, and
 * request-sync is a message. Delivery is bound to this process being alive;
 * there is no system listener waking a dead watch app.
 */
object WearLinkBridge {
    private const val TAG = "WearLinkBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var node: WearLinkNode? = null

    fun get(context: Context): WearLinkNode {
        val appContext = context.applicationContext
        return node ?: synchronized(this) {
            node ?: create(appContext).also { node = it }
        }
    }

    private fun create(context: Context): WearLinkNode {
        val listener = object : WearLinkListener {
            override fun onData(path: String, body: ByteArray) {
                if (path != WearLauncherProtocol.FAVORITES_PATH) return
                val snapshot = runCatching {
                    WearLauncherProtocol.json.decodeFromString<WearFavoritesSnapshot>(body.decodeToString())
                }.getOrNull()
                if (snapshot == null) {
                    Log.status(TAG, "ignoring malformed favorites snapshot")
                    return
                }
                WearLauncherStore.get(context).setSnapshot(snapshot, InstalledApps.load(context).size)
            }
        }
        return WearLinkNode(context, WearLauncherProtocol.CAPABILITY, listener).also { it.start() }
    }

    fun publishWatchState(context: Context, snapshot: WearFavoritesSnapshot, installedCount: Int) {
        val state = WearLauncherWatchState(
            appliedGeneratedAtMillis = snapshot.generatedAtMillis,
            favoriteCount = snapshot.favorites.size,
            installedCount = installedCount,
        )
        scope.launch {
            get(context).putData(
                WearLauncherProtocol.WATCH_STATE_PATH,
                WearLauncherProtocol.json.encodeToString(state).encodeToByteArray(),
            )
        }
    }

    suspend fun requestSync(context: Context): Boolean =
        get(context).sendMessage(WearLauncherProtocol.REQUEST_SYNC_PATH, ByteArray(0))
}
