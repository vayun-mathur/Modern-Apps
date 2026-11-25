package com.vayunmathur.findfamily.wear.data

import android.content.Context
import com.vayunmathur.findfamily.wear.domain.WearFindFamilyProtocol
import com.vayunmathur.findfamily.wear.domain.WearFindFamilySnapshot
import com.vayunmathur.library.log.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/**
 * The watch's local truth: the last phone snapshot, persisted as JSON in
 * app-private storage so people read fully offline (phone absent, process
 * cold-started) — labelled stale by age, never as live data.
 */
class WearFindFamilyStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WearFindFamilyStore"

        @Volatile
        private var instance: WearFindFamilyStore? = null

        /** The process-wide store (bridge and UI must agree on one). */
        fun get(context: Context): WearFindFamilyStore = instance ?: synchronized(this) {
            instance ?: WearFindFamilyStore(context.applicationContext).also { instance = it }
        }
    }

    private val snapshotFile = File(context.filesDir, "wear-findfamily-snapshot.json")

    private val _snapshot = MutableStateFlow(loadSnapshot())
    /** Last snapshot from the phone; empty until the first sync. */
    val snapshot: StateFlow<WearFindFamilySnapshot> = _snapshot.asStateFlow()

    private val _connected = MutableStateFlow(false)
    /** Last known WearLink session state, for offline honesty in the UI. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun setConnected(connected: Boolean) {
        _connected.value = connected
    }

    fun setSnapshot(snapshot: WearFindFamilySnapshot) {
        _snapshot.value = snapshot
        runCatching { snapshotFile.writeText(WearFindFamilyProtocol.json.encodeToString(snapshot)) }
            .onFailure { Log.status(TAG, "persist snapshot failed: ${it.message}") }
        com.vayunmathur.findfamily.wear.sync.WearLinkBridge.publishWatchState(context)
    }

    private fun loadSnapshot(): WearFindFamilySnapshot = runCatching {
        WearFindFamilyProtocol.json.decodeFromString<WearFindFamilySnapshot>(snapshotFile.readText())
    }.getOrDefault(WearFindFamilySnapshot())
}
