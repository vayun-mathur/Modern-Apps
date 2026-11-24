package com.vayunmathur.clock.sync

import android.content.Context
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.domain.WearClockAlarm
import com.vayunmathur.clock.domain.WearClockProtocol
import com.vayunmathur.clock.domain.WearClockSnapshot
import com.vayunmathur.clock.domain.WearClockWatchState
import com.vayunmathur.clock.domain.WearWorldClock
import com.vayunmathur.clock.domain.buildWearClockSnapshot
import com.vayunmathur.clock.platform.AlarmScheduler
import com.vayunmathur.clock.platform.WorldClockCities
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import java.io.DataInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlin.time.Clock

/** Phone-side wear status; absence of a watch is a normal state, never an error. */
data class WearClockSyncState(
    val companionPresent: Boolean = false,
    val lastPublishedAtMillis: Long? = null,
    val lastPublishedAlarmCount: Int = 0,
    /** generatedAtMillis the watch last reported applying; null when unreported. */
    val lastWatchAppliedAtMillis: Long? = null,
    /** Alarms the watch reports actually armed (display-only alarms excluded). */
    val watchArmedCount: Int = 0,
    val watchDisplayOnlyCount: Int = 0,
)

/**
 * Phone side of the wear sync over WearLink (RFCOMM, :library:wearlink):
 * presence is a completed handshake, the alarm/world-clock snapshot is a
 * data item, and the watch's request-sync is a message handled here while
 * this process lives. There is no Play-services listener service: a request
 * arriving while the phone app is dead waits until it next runs, and the
 * snapshot republishes on reconnect and on every alarm/world-clock change.
 */
class WearClockSync private constructor(context: Context) {

    companion object {
        private const val TAG = "WearClockSync"

        @Volatile
        private var instance: WearClockSync? = null

        /** The process-wide sync (activity and any future settings UI agree on one). */
        fun get(context: Context): WearClockSync = instance ?: synchronized(this) {
            instance ?: WearClockSync(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(WearClockSyncState())
    val state: StateFlow<WearClockSyncState> = _state.asStateFlow()

    private val listener = object : WearLinkListener {
        override fun onData(path: String, body: ByteArray) {
            if (path != WearClockProtocol.WATCH_STATE_PATH) return
            val watchState = runCatching {
                WearClockProtocol.json.decodeFromString<WearClockWatchState>(body.decodeToString())
            }.getOrNull() ?: return
            _state.update {
                it.copy(
                    lastWatchAppliedAtMillis =
                        watchState.appliedGeneratedAtMillis.takeIf { applied -> applied > 0L },
                    watchArmedCount = watchState.armedCount,
                    watchDisplayOnlyCount = watchState.displayOnlyCount,
                )
            }
        }

        override fun onMessage(path: String, body: ByteArray) {
            if (path != WearClockProtocol.REQUEST_SYNC_PATH) return
            scope.launch { publishSnapshot() }
        }
    }

    private val node = WearLinkNode(appContext, WearClockProtocol.CAPABILITY, listener)

    private var started = false
    private var pendingPublish: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            node.state.collect { linkState ->
                val present = linkState is WearLinkState.Connected
                val wasPresent = _state.value.companionPresent
                _state.update { it.copy(companionPresent = present) }
                if (present && !wasPresent) syncNow()
            }
        }
        // Republish (debounced) whenever the phone's alarms or world-clock
        // list changes, so the watch snapshot never silently goes stale.
        scope.launch {
            val repository = ClockRepository.get(appContext)
            val ds = DataStoreUtils.getInstance(appContext)
            combine(repository.alarms, WorldClockCities.flow(ds)) { alarms, _ -> alarms }
                .collect { onClockChanged() }
        }
        node.start()
    }

    /** Clock data changed: republish shortly, coalescing bursts (bulk edits). */
    fun onClockChanged() {
        if (!_state.value.companionPresent) return
        pendingPublish?.cancel()
        pendingPublish = scope.launch {
            delay(1_000)
            publishSnapshot()
        }
    }

    /** Manual sync; publishes even when presence is stale/unknown. */
    fun syncNow() {
        scope.launch { publishSnapshot() }
    }

    /** Build the bounded snapshot from Room + world-clock prefs and publish it. */
    suspend fun publishSnapshot() {
        val snapshot = withContext(Dispatchers.IO) { buildSnapshot() } ?: return
        val sent = node.putData(
            WearClockProtocol.SNAPSHOT_PATH,
            WearClockProtocol.json.encodeToString(snapshot).encodeToByteArray(),
        )
        if (sent) {
            _state.update {
                it.copy(
                    lastPublishedAtMillis = snapshot.generatedAtMillis,
                    lastPublishedAlarmCount = snapshot.alarms.size,
                )
            }
        } else {
            Log.status(TAG, "publish snapshot not delivered (no live WearLink session)")
        }
    }

    private suspend fun buildSnapshot(): WearClockSnapshot? = runCatching {
        val repository = ClockRepository.get(appContext)
        val alarms = repository.getAllAlarms().map { alarm ->
            WearClockAlarm(
                alarmId = alarm.id,
                label = alarm.name,
                hour = alarm.time.hour,
                minute = alarm.time.minute,
                daysMask = alarm.days,
                enabled = alarm.enabled,
                phoneNextTriggerMillis = if (alarm.enabled) {
                    runCatching { AlarmScheduler.calculateNextTriggerMillis(alarm) }
                        .getOrDefault(0L)
                } else {
                    0L
                },
                vibrate = alarm.vibrate,
                snoozeMinutes = alarm.snoozeMinutes,
            )
        }
        val ds = DataStoreUtils.getInstance(appContext)
        val cities = WorldClockCities.flow(ds).first()
        val zoneByCity = loadCityZones()
        val worldClocks = cities.mapNotNull { city ->
            zoneByCity[city]?.let { WearWorldClock(city = city, timezoneId = it) }
        }
        buildWearClockSnapshot(
            alarms = alarms,
            worldClocks = worldClocks,
            generatedAtMillis = Clock.System.now().toEpochMilliseconds(),
        )
    }.onFailure { Log.status(TAG, "build snapshot failed: ${it.message}") }.getOrNull()

    /**
     * City -> IANA zone from the bundled cities.bin (same packed format the
     * ViewModel parses; duplicated here because the sync must be able to
     * publish without a ViewModel alive).
     */
    private fun loadCityZones(): Map<String, String> = runCatching {
        appContext.assets.open("cities.bin").use { input ->
            val data = DataInputStream(input.buffered())
            val magic = ByteArray(4).also { data.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "CTB1") { "bad cities.bin magic" }
            val timezones = Array(data.readInt()) {
                val bytes = ByteArray(data.readUnsignedByte())
                data.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
            val map = LinkedHashMap<String, String>()
            repeat(data.readInt()) {
                val name = ByteArray(data.readUnsignedShort())
                data.readFully(name)
                map[String(name, Charsets.UTF_8)] = timezones[data.readUnsignedShort()]
            }
            map
        }
    }.getOrDefault(emptyMap())
}
