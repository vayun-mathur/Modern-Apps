package com.vayunmathur.clock.wear.data

import android.content.Context
import com.vayunmathur.clock.wear.domain.ArmFailureRecord
import com.vayunmathur.clock.wear.domain.ArmedAlarmRecord
import com.vayunmathur.clock.wear.domain.WatchStopwatch
import com.vayunmathur.clock.wear.domain.WatchTimer
import com.vayunmathur.clock.wear.domain.WearClockProtocol
import com.vayunmathur.clock.wear.domain.WearClockSnapshot
import com.vayunmathur.library.log.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Everything the watch persists, in one JSON document in app-private storage. */
@Serializable
data class WearClockPersisted(
    val snapshot: WearClockSnapshot = WearClockSnapshot(),
    /** Proof of successful watch-local armings; absence means display-only. */
    val armedRecords: List<ArmedAlarmRecord> = emptyList(),
    val armFailures: List<ArmFailureRecord> = emptyList(),
    /** Watch-native timers (never from the phone). */
    val timers: List<WatchTimer> = emptyList(),
    /** Watch-native stopwatch. */
    val stopwatch: WatchStopwatch = WatchStopwatch(),
)

/**
 * The watch's local truth: the last phone snapshot, the arming proof for its
 * alarms, and the watch-native timer/stopwatch state — persisted as JSON so
 * every screen renders offline (phone absent, process cold-started).
 */
class WearClockStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WearClockStore"

        @Volatile
        private var instance: WearClockStore? = null

        /** The process-wide store (bridge, receivers and UI must agree on one). */
        fun get(context: Context): WearClockStore = instance ?: synchronized(this) {
            instance ?: WearClockStore(context.applicationContext).also { instance = it }
        }
    }

    private val file = File(context.filesDir, "wear-clock.json")

    private val _state = MutableStateFlow(load())

    /** All persisted watch clock state. */
    val state: StateFlow<WearClockPersisted> = _state.asStateFlow()

    /** Replace the phone snapshot (WearLink data item); arming is reconciled by the bridge. */
    fun setSnapshot(snapshot: WearClockSnapshot) {
        update { it.copy(snapshot = snapshot) }
    }

    /** Record the outcome of reconciling the snapshot against AlarmManager. */
    fun setArming(armed: List<ArmedAlarmRecord>, failures: List<ArmFailureRecord>) {
        update { it.copy(armedRecords = armed, armFailures = failures) }
    }

    fun upsertTimer(timer: WatchTimer) {
        update { persisted ->
            persisted.copy(
                timers = (persisted.timers.filterNot { it.id == timer.id } + timer)
                    .sortedBy { it.id },
            )
        }
    }

    fun removeTimer(id: Long) {
        update { persisted -> persisted.copy(timers = persisted.timers.filterNot { it.id == id }) }
    }

    fun setStopwatch(stopwatch: WatchStopwatch) {
        update { it.copy(stopwatch = stopwatch) }
    }

    @Synchronized
    private fun update(transform: (WearClockPersisted) -> WearClockPersisted) {
        _state.value = transform(_state.value)
        runCatching { file.writeText(WearClockProtocol.json.encodeToString(_state.value)) }
            .onFailure { Log.status(TAG, "persist failed: ${it.message}") }
    }

    private fun load(): WearClockPersisted = runCatching {
        WearClockProtocol.json.decodeFromString<WearClockPersisted>(file.readText())
    }.getOrDefault(WearClockPersisted())
}
