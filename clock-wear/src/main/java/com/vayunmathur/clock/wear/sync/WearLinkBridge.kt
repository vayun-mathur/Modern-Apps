package com.vayunmathur.clock.wear.sync

import android.content.Context
import com.vayunmathur.clock.wear.data.WearClockStore
import com.vayunmathur.clock.wear.domain.WearClockProtocol
import com.vayunmathur.clock.wear.domain.WearClockSnapshot
import com.vayunmathur.clock.wear.domain.WearClockWatchState
import com.vayunmathur.clock.wear.domain.armedAndDisplayOnlyCounts
import com.vayunmathur.clock.wear.platform.WatchAlarmScheduler
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Watch side of WearLink for Clock: snapshot data items land in
 * [WearClockStore], are immediately reconciled against the watch's own
 * AlarmManager (that reconciliation — not the snapshot's `enabled` flag —
 * decides what the UI calls armed), watch state goes back as a data item,
 * and request-sync is a message. Delivery is bound to this process being
 * alive; there is no Play-services listener service.
 */
object WearLinkBridge {
    private const val TAG = "WearLinkBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var node: WearLinkNode? = null

    fun get(context: Context): WearLinkNode {
        val appContext = context.applicationContext
        return node ?: synchronized(this) {
            node ?: create(appContext).also { node = it }
        }
    }

    private fun create(context: Context): WearLinkNode {
        val listener = object : WearLinkListener {
            override fun onData(path: String, body: ByteArray) {
                if (path != WearClockProtocol.SNAPSHOT_PATH) return
                val snapshot = runCatching {
                    WearClockProtocol.json.decodeFromString<WearClockSnapshot>(
                        body.decodeToString(),
                    )
                }.getOrNull()
                if (snapshot == null) {
                    Log.status(TAG, "ignoring malformed snapshot")
                    return
                }
                scope.launch { applySnapshot(context, snapshot) }
            }
        }
        return WearLinkNode(context, WearClockProtocol.CAPABILITY, listener).also { it.start() }
    }

    /** Store the snapshot, arm what can be armed on this watch, report back. */
    fun applySnapshot(context: Context, snapshot: WearClockSnapshot) {
        val store = WearClockStore.get(context)
        val previousArmedIds = store.state.value.armedRecords.map { it.alarmId }
        store.setSnapshot(snapshot)
        val (armed, failures) = WatchAlarmScheduler.reconcile(
            context, snapshot, previousArmedIds, System.currentTimeMillis(),
        )
        store.setArming(armed, failures)
        publishWatchState(context)
    }

    /** Re-run arming for the stored snapshot (e.g. after exact-alarm access returns). */
    fun rearmFromStore(context: Context) {
        scope.launch {
            val snapshot = WearClockStore.get(context).state.value.snapshot
            applySnapshot(context, snapshot)
        }
    }

    fun publishWatchState(context: Context) {
        val persisted = WearClockStore.get(context).state.value
        val (armedCount, displayOnlyCount) = armedAndDisplayOnlyCounts(
            persisted.snapshot.alarms,
            persisted.armedRecords,
            persisted.armFailures,
            System.currentTimeMillis(),
        )
        val state = WearClockWatchState(
            appliedGeneratedAtMillis = persisted.snapshot.generatedAtMillis,
            alarmCount = persisted.snapshot.alarms.size,
            armedCount = armedCount,
            displayOnlyCount = displayOnlyCount,
        )
        scope.launch {
            get(context).putData(
                WearClockProtocol.WATCH_STATE_PATH,
                WearClockProtocol.json.encodeToString(state).encodeToByteArray(),
            )
        }
    }

    /** Ask the phone for a fresh snapshot; false when no session is live. */
    suspend fun requestSync(context: Context): Boolean =
        get(context).sendMessage(WearClockProtocol.REQUEST_SYNC_PATH, ByteArray(0))
}
