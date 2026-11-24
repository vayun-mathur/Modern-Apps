package com.vayunmathur.calendar.wear.data

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.vayunmathur.calendar.wear.domain.WearCalendarProtocol
import com.vayunmathur.calendar.wear.domain.WearCalendarSnapshot
import com.vayunmathur.calendar.wear.domain.WearWatchState
import com.vayunmathur.library.log.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/**
 * The watch's local truth: the last snapshot the phone published, persisted
 * as JSON in app-private storage so the UI renders offline (phone absent,
 * process cold-started) from whatever it already has.
 */
class WearCalendarStore private constructor(private val context: Context) {

    /** Holds singleton access. */
    companion object {
        private const val TAG = "WearCalendarStore"
        private const val KEY_JSON = "json"
        private const val KEY_SENT_AT = "sent_at"

        @Volatile
        private var instance: WearCalendarStore? = null

        /** The process-wide store (listener service and UI must agree on one). */
        fun get(context: Context): WearCalendarStore = instance ?: synchronized(this) {
            instance ?: WearCalendarStore(context.applicationContext).also { instance = it }
        }
    }

    private val snapshotFile = File(context.filesDir, "wear-calendar-snapshot.json")

    private val _snapshot = MutableStateFlow(loadSnapshot())

    /** Last snapshot from the phone; empty until the first sync. */
    val snapshot: StateFlow<WearCalendarSnapshot> = _snapshot.asStateFlow()

    /** Replace the stored snapshot (DataClient push from the phone) and tell the phone it landed. */
    fun setSnapshot(snapshot: WearCalendarSnapshot) {
        _snapshot.value = snapshot
        runCatching { snapshotFile.writeText(WearCalendarProtocol.json.encodeToString(snapshot)) }
            .onFailure { Log.status(TAG, "persist snapshot failed: ${it.message}") }
        publishWatchState()
    }

    /** Publish what this watch is rendering from, so the phone can show last-sync truth. */
    fun publishWatchState() {
        val current = _snapshot.value
        val state = WearWatchState(
            appliedGeneratedAtMillis = current.generatedAtMillis,
            eventCount = current.events.size,
        )
        runCatching {
            val request = PutDataMapRequest.create(WearCalendarProtocol.WATCH_STATE_PATH).apply {
                dataMap.putString(KEY_JSON, WearCalendarProtocol.json.encodeToString(state))
                dataMap.putLong(KEY_SENT_AT, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request)
        }.onFailure { Log.status(TAG, "publish watch state failed: ${it.message}") }
    }

    private fun loadSnapshot(): WearCalendarSnapshot = runCatching {
        WearCalendarProtocol.json.decodeFromString<WearCalendarSnapshot>(snapshotFile.readText())
    }.getOrDefault(WearCalendarSnapshot())
}
