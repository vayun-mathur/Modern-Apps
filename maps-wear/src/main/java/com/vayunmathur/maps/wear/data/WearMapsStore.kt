package com.vayunmathur.maps.wear.data

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.maps.wear.domain.WearMapSnapshot
import com.vayunmathur.maps.wear.domain.WearMapsProtocol
import com.vayunmathur.maps.wear.domain.WearPosition
import com.vayunmathur.maps.wear.domain.WearRouteState
import com.vayunmathur.maps.wear.domain.WearWatchState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/**
 * The watch's local truth: the last region snapshot, route overlay and
 * position, persisted as JSON in app-private storage so the map renders
 * fully offline (phone absent, process cold-started) — labelled stale,
 * never as live data. An inactive [WearRouteState] clears the overlay
 * and is not kept.
 */
class WearMapsStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WearMapsStore"

        @Volatile
        private var instance: WearMapsStore? = null

        /** The process-wide store (bridge and UI must agree on one). */
        fun get(context: Context): WearMapsStore = instance ?: synchronized(this) {
            instance ?: WearMapsStore(context.applicationContext).also { instance = it }
        }
    }

    private val regionFile = File(context.filesDir, "wear-maps-region.json")
    private val routeFile = File(context.filesDir, "wear-maps-route.json")
    private val positionFile = File(context.filesDir, "wear-maps-position.json")

    private val _snapshot = MutableStateFlow(loadSnapshot())
    val snapshot: StateFlow<WearMapSnapshot?> = _snapshot.asStateFlow()

    private val _route = MutableStateFlow(loadRoute())
    /** Active route overlay, or null when no navigation (cleared routes stay cleared). */
    val route: StateFlow<WearRouteState?> = _route.asStateFlow()

    private val _position = MutableStateFlow(loadPosition())
    val position: StateFlow<WearPosition?> = _position.asStateFlow()

    private val _connected = MutableStateFlow(false)
    /** Last known WearLink session state, for offline honesty in the UI. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun setConnected(connected: Boolean) {
        _connected.value = connected
    }

    fun setSnapshot(snapshot: WearMapSnapshot) {
        _snapshot.value = snapshot
        persist(regionFile, WearMapsProtocol.json.encodeToString(snapshot))
        publishWatchState()
    }

    /** Applies a route state; `active=false` clears the overlay (route ended). */
    fun setRoute(state: WearRouteState) {
        val applied = com.vayunmathur.maps.wear.domain.wearApplyRouteState(_route.value, state)
        _route.value = applied
        if (applied == null) {
            runCatching { routeFile.delete() }
        } else {
            persist(routeFile, WearMapsProtocol.json.encodeToString(applied))
        }
        publishWatchState()
    }

    fun setPosition(position: WearPosition) {
        _position.value = position
        persist(positionFile, WearMapsProtocol.json.encodeToString(position))
    }

    private fun publishWatchState() {
        com.vayunmathur.maps.wear.sync.WearLinkBridge.publishWatchState(context)
    }

    /** The watch-state document the bridge republishes on connect. */
    fun watchState(): WearWatchState = WearWatchState(
        appliedGeneratedAtMillis = _snapshot.value?.generatedAtMillis ?: 0L,
        hasRegion = _snapshot.value != null,
        routeActive = _route.value?.active == true,
    )

    private fun loadSnapshot(): WearMapSnapshot? = runCatching {
        WearMapsProtocol.json.decodeFromString<WearMapSnapshot>(regionFile.readText())
    }.getOrNull()

    private fun loadRoute(): WearRouteState? = runCatching {
        WearMapsProtocol.json.decodeFromString<WearRouteState>(routeFile.readText())
    }.getOrNull()?.takeIf { it.active }

    private fun loadPosition(): WearPosition? = runCatching {
        WearMapsProtocol.json.decodeFromString<WearPosition>(positionFile.readText())
    }.getOrNull()

    private fun persist(file: File, text: String) {
        runCatching { file.writeText(text) }
            .onFailure { Log.status(TAG, "persist ${file.name} failed: ${it.message}") }
    }
}
