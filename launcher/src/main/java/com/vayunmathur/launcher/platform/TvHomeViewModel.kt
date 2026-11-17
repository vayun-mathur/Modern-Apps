package com.vayunmathur.launcher.platform

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.net.toUri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.launcher.data.TvPrefs
import com.vayunmathur.launcher.domain.TvRowOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the row order, the app list, the HDMI inputs and the edit-menu state.
 *
 * Banner/icon drawables are loaded off-main and cached per process in [TvArtCache], so a
 * package-change rebuild does not re-rasterise art the grid already holds.
 */
class TvHomeViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = TvPrefs(app)
    private val artCache = TvArtCache(app)
    /** Serializes rebuilds: package broadcasts burst, and last-writer must win. */
    private val refreshMutex = Mutex()
    /**
     * The last order known persisted in DataStore. Moves only persist on drop, so while a
     * tile is held (or after arming) the screen order is newer; cancel/disarm restores from
     * here quietly instead of paying for a full reload flash.
     */
    private var lastPersistedOrder: List<String> = emptyList()

    private val _home = MutableStateFlow(TvHomeState())
    val home: StateFlow<TvHomeState> = _home

    private val packagesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshApps()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        app.registerReceiver(packagesChanged, filter)
        refreshApps()
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(packagesChanged) }
    }

    /** Rebuilds the grid: re-query apps + inputs, re-merge with the saved order. */
    fun refreshApps() {
        viewModelScope.launch {
            refreshMutex.withLock {
                val context = getApplication<Application>()
                // Don't clobber an in-progress move: the order on screen is newer than
                // DataStore until finishMove persists it.
                if (_home.value.movingKey != null) return@withLock
                _home.value = _home.value.copy(loading = true)
                val settings = withContext(Dispatchers.IO) { prefs.settings.first() }
                val entries = withContext(Dispatchers.IO) { TvApps.installed(context, context.packageName) }
                val row = TvRowOrder.mergeOrder(settings.appOrder, entries.map { it.packageName })
                val byPkg = entries.associateBy { it.packageName }
                val tiles = withContext(Dispatchers.IO) {
                    row.mapNotNull { pkg ->
                        val entry = byPkg[pkg] ?: return@mapNotNull null
                        TvTile(
                            packageName = pkg,
                            label = entry.label,
                            banner = artCache.banner(pkg),
                            appIcon = artCache.icon(pkg),
                            userInstalled = TvApps.isUserInstalled(context.packageManager, pkg),
                        )
                    }
                }
                val hasStore = byPkg.containsKey(APP_STORE) &&
                    APP_STORE !in row
                val inputs = withContext(Dispatchers.IO) {
                    TvInputs.list(context, TvRowOrder.maxHdmiSlots(hasStore))
                }
                lastPersistedOrder = row
                _home.value = _home.value.copy(
                    rowApps = tiles,
                    showAppStore = hasStore,
                    hdmiInputs = inputs,
                    loading = false,
                )
            }
        }
    }

    // --- open ---

    fun openTile(pkg: String) {
        val context = getApplication<Application>()
        val intent = TvApps.launchIntent(context.packageManager, pkg) ?: run {
            AppMessages.show("Could not open that app")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isFailure) {
            AppMessages.show("Could not open that app")
        }
    }

    fun openAppStore() = openTile(APP_STORE)

    fun openInput(inputId: String) {
        TvInputs.open(getApplication(), inputId)
    }

    fun openSettings() {
        val context = getApplication<Application>()
        val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isFailure) {
            AppMessages.show("Could not open settings")
        }
    }

    fun openNetworkSettings() {
        val context = getApplication<Application>()
        for (action in listOf(
            Settings.ACTION_WIFI_SETTINGS,
            Settings.ACTION_WIRELESS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        AppMessages.show("Could not open network settings")
    }

    // --- edit mode ---

    fun openEditMenu(pkg: String) {
        _home.value = _home.value.copy(editingKey = pkg, movingKey = null)
    }

    fun closeEditMenu() {
        _home.value = _home.value.copy(
            editingKey = null,
            movingKey = null,
            addCandidates = emptyList(),
            addReplacesIndex = -1,
            showAddMenu = false,
        )
    }

    fun startMove(pkg: String) {
        // Picking up keeps reorder mode armed: dropping returns to ARMED so several apps can
        // be arranged without re-arming each time. Only the toggle or Back disarms.
        _home.value = _home.value.copy(editingKey = null, movingKey = pkg, reorderArmed = true)
    }

    /** Arms reorder mode: OK on a main-row tile picks it up, arrows move it. */
    fun toggleReorder() {
        val armed = !_home.value.reorderArmed
        if (!armed && _home.value.movingKey != null) {
            // Disarming mid-hold drops the hold: restore the last persisted order quietly.
            _home.value = _home.value.copy(
                reorderArmed = false,
                editingKey = null,
                movingKey = null,
                rowApps = reorderTiles(_home.value.rowApps, lastPersistedOrder),
            )
        } else {
            _home.value = _home.value.copy(reorderArmed = armed, editingKey = null)
        }
        // No refreshApps(): the on-screen order is already correct, and a reload would flash
        // loading + steal focus.
    }

    /** Disarms reorder mode (Back while armed but not holding). Never reloads. */
    fun disarmReorder() {
        if (_home.value.movingKey != null) {
            cancelMove()
            return
        }
        _home.value = _home.value.copy(reorderArmed = false, editingKey = null)
    }

    /**
     * The main-row OK press: in reorder mode it picks up / drops the tile; otherwise it
     * opens the app. This is the single pick-up/drop path — there is no long-press.
     */
    fun pressTile(pkg: String) {
        val state = _home.value
        when {
            state.movingKey == pkg -> finishMove()
            state.movingKey != null -> return
            state.reorderArmed -> startMove(pkg)
            else -> openTile(pkg)
        }
    }

    /** Drops the held tile: persists the on-screen order, stays armed for the next move. */
    fun finishMove() {
        viewModelScope.launch {
            refreshMutex.withLock {
                val order = _home.value.rowApps.map { it.packageName }
                withContext(Dispatchers.IO) { prefs.saveOrder(order) }
                lastPersistedOrder = order
                _home.value = _home.value.copy(movingKey = null)
            }
        }
    }

    /**
     * Exits move mode without persisting (Back while moving): restores the last persisted
     * order in memory, stays armed, no loading flash.
     */
    fun cancelMove() {
        _home.value = _home.value.copy(
            movingKey = null,
            rowApps = reorderTiles(_home.value.rowApps, lastPersistedOrder),
        )
    }

    fun moveKey(pkg: String, step: Int) {
        // Guard against stale closures: only the tile actually being moved may move.
        if (_home.value.movingKey != pkg) return
        val order = _home.value.rowApps.map { it.packageName }.toMutableList()
        val from = order.indexOf(pkg)
        if (from < 0) return
        val to = (from + step).coerceIn(0, order.size - 1)
        if (to == from) return
        val moved = TvRowOrder.move(order, from, to)
        _home.value = _home.value.copy(
            rowApps = reorderTiles(_home.value.rowApps, moved),
        )
    }

    fun removeTile(pkg: String) {
        val kept = _home.value.rowApps.map { it.packageName }.filter { it != pkg }
        _home.value = _home.value.copy(rowApps = _home.value.rowApps.filter { it.packageName != pkg })
        closeEditMenu()
        viewModelScope.launch {
            refreshMutex.withLock {
                withContext(Dispatchers.IO) { prefs.saveOrder(kept) }
            }
        }
    }

    fun openAddMenu(replaceIndex: Int = -1) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val onRow = _home.value.rowApps.map { it.packageName }.toSet()
            val candidates = withContext(Dispatchers.IO) {
                TvApps.installed(context, context.packageName)
                    .filter { it.packageName !in onRow }
                    .filter {
                        it.packageName !in TvRowOrder.NEVER_TILES ||
                            it.packageName == TvRowOrder.PLAY_STORE
                    }
                    .sortedBy { it.label.lowercase() }
            }
            _home.value = _home.value.copy(
                editingKey = null,
                addCandidates = candidates,
                addReplacesIndex = replaceIndex,
                showAddMenu = true,
            )
        }
    }

    fun pickAddCandidate(pkg: String) {
        val state = _home.value
        val order = state.rowApps.map { it.packageName }
        val updated = TvRowOrder.replaceAt(order, state.addReplacesIndex, pkg)
        viewModelScope.launch {
            refreshMutex.withLock {
                withContext(Dispatchers.IO) { prefs.saveOrder(updated) }
                _home.value = _home.value.copy(
                    addCandidates = emptyList(),
                    addReplacesIndex = -1,
                    showAddMenu = false,
                )
            }
            // Outside the lock: refreshApps takes it in turn, so the save above is visible.
            refreshApps()
        }
    }

    fun uninstallOrInfo(pkg: String) {
        val context = getApplication<Application>()
        val user = TvApps.isUserInstalled(context.packageManager, pkg)
        val uri = "package:$pkg".toUri()
        val intent = if (user) {
            Intent(Intent.ACTION_DELETE, uri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isFailure) {
            AppMessages.show("Could not open that")
        }
        closeEditMenu()
    }

    // --- home role ---

    fun refreshDefaultHome(activity: android.app.Activity) {
        _home.value = _home.value.copy(isDefaultHome = TvHomeRole.isDefaultHome(activity))
    }

    fun requestHomeRole(activity: android.app.Activity) {
        if (!TvHomeRole.requestHomeRole(activity)) {
            AppMessages.show("Could not open home app settings")
        }
    }

    // --- internals ---

    /** Shared constants and helpers for TvHomeViewModel. */
    companion object {
        const val APP_STORE = "com.vayunmathur.appstore"
    }

    private fun persistOrder(order: List<String>) {
        viewModelScope.launch(Dispatchers.IO) { prefs.saveOrder(order) }
    }

    private fun reorderTiles(tiles: List<TvTile>, order: List<String>): List<TvTile> {
        val byPkg = tiles.associateBy { it.packageName }
        // Packages installed since the snapshot (absent from order) keep their relative
        // position at the end rather than vanishing.
        return order.mapNotNull { byPkg[it] } + tiles.filter { it.packageName !in order }
    }
}

