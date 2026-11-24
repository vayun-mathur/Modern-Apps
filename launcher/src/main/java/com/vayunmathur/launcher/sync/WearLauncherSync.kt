package com.vayunmathur.launcher.sync

import android.content.Context
import android.content.pm.PackageManager
import com.vayunmathur.launcher.data.LauncherRepository
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.WearFavorite
import com.vayunmathur.launcher.domain.WearLauncherProtocol
import com.vayunmathur.launcher.domain.WearLauncherWatchState
import com.vayunmathur.launcher.domain.buildWearFavorites
import com.vayunmathur.launcher.domain.wearLauncherCompanionPresent
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Phone-side wear status; absence of a watch is a normal state, never an error. */
data class WearLauncherSyncState(
    val companionPresent: Boolean = false,
    val lastPublishedAtMillis: Long? = null,
    val lastWatchAppliedAtMillis: Long? = null,
    val lastPublishedFavoriteCount: Int = 0,
)

/**
 * Phone side of the launcher wear sync over WearLink (RFCOMM,
 * :library:wearlink): presence is a completed handshake, the favorites
 * snapshot is a data item, and the watch's request-sync is a message
 * handled here while this process lives. There is no Play-services
 * listener service: a request arriving while the launcher is dead waits
 * until it next runs (it republish on connect in any case).
 *
 * Only the hotseat - the pinned/favorites row - is exported. The paged
 * workspace, folders and widgets stay phone-only by design (see the
 * protocol file), and watch recents never come back here.
 */
class WearLauncherSync private constructor(context: Context) {
    companion object {
        private const val TAG = "WearLauncherSync"

        @Volatile private var instance: WearLauncherSync? = null

        fun get(context: Context): WearLauncherSync = instance ?: synchronized(this) {
            instance ?: WearLauncherSync(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(WearLauncherSyncState())
    val state: StateFlow<WearLauncherSyncState> = _state.asStateFlow()

    private val listener = object : WearLinkListener {
        override fun onData(path: String, body: ByteArray) {
            if (path != WearLauncherProtocol.WATCH_STATE_PATH) return
            val watchState = runCatching {
                WearLauncherProtocol.json.decodeFromString<WearLauncherWatchState>(body.decodeToString())
            }.getOrNull() ?: return
            _state.update {
                it.copy(
                    lastWatchAppliedAtMillis = watchState.appliedGeneratedAtMillis
                        .takeIf { applied -> applied > 0L },
                )
            }
        }

        override fun onMessage(path: String, body: ByteArray) {
            if (path != WearLauncherProtocol.REQUEST_SYNC_PATH) return
            scope.launch { publishFavorites() }
        }
    }

    private val node = WearLinkNode(appContext, WearLauncherProtocol.CAPABILITY, listener)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            node.state.collect { linkState ->
                val present = linkState is WearLinkState.Connected
                val wasPresent = _state.value.companionPresent
                _state.update {
                    it.copy(companionPresent = wearLauncherCompanionPresent(if (present) 1 else 0))
                }
                if (present && !wasPresent) publishFavorites()
            }
        }
        node.start()
    }

    /** Layout changed (or manual sync): republish the hotseat snapshot. */
    fun syncNow() {
        scope.launch { publishFavorites() }
    }

    suspend fun publishFavorites() {
        val snapshot = withContext(Dispatchers.IO) { buildSnapshot() } ?: return
        val sent = node.putData(
            WearLauncherProtocol.FAVORITES_PATH,
            WearLauncherProtocol.json.encodeToString(snapshot).encodeToByteArray(),
        )
        if (sent) {
            _state.update {
                it.copy(
                    lastPublishedAtMillis = snapshot.generatedAtMillis,
                    lastPublishedFavoriteCount = snapshot.favorites.size,
                )
            }
        } else {
            Log.status(TAG, "publish favorites not delivered (no live WearLink session)")
        }
    }

    private suspend fun buildSnapshot() = runCatching {
        val items = LauncherRepository.get(appContext).getAll()
        val pm = appContext.packageManager
        val candidates = items
            .filter { it.container == ContainerRef.Hotseat }
            .filter { it.itemType == LauncherItemType.APPLICATION && !it.hidden }
            .sortedBy { it.rank }
            .mapNotNull { item ->
                val packageName = item.packageName ?: return@mapNotNull null
                val label = item.title ?: appLabel(pm, packageName)
                WearFavorite(
                    packageName = packageName,
                    className = item.className.orEmpty(),
                    label = label,
                    rank = item.rank,
                )
            }
        buildWearFavorites(candidates, System.currentTimeMillis())
    }.onFailure { Log.status(TAG, "build favorites failed: ${it.message}") }.getOrNull()

    private fun appLabel(pm: PackageManager, packageName: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName.substringAfterLast('.'))
}
