package com.vayunmathur.calendar.sync

import android.content.Context
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.domain.WearCalendarEvent
import com.vayunmathur.calendar.domain.WearCalendarProtocol
import com.vayunmathur.calendar.domain.WearCalendarSnapshot
import com.vayunmathur.calendar.domain.WearWatchState
import com.vayunmathur.calendar.domain.buildWearSnapshot
import com.vayunmathur.calendar.domain.wearCompanionPresent
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Phone-side wear status the Settings screen shows; absence is a normal state, never an error. */
data class WearSyncState(
    val companionPresent: Boolean = false,
    /** When the phone last published a snapshot, epoch millis; null when never. */
    val lastPublishedAtMillis: Long? = null,
    /** generatedAtMillis the watch last reported applying, epoch millis; null when unreported. */
    val lastWatchAppliedAtMillis: Long? = null,
    val lastPublishedEventCount: Int = 0,
)

/**
 * Phone side of the wear sync over WearLink (RFCOMM, :library:wearlink):
 * presence is a completed handshake, the snapshot is a data item, and the
 * watch's request-sync is a message handled here while this process lives.
 *
 * "No Bluetooth permission / no watch / watch without the companion" is a
 * normal state ([WearSyncState.companionPresent] = false), never a crash.
 * There is no Play-services listener service any more: a sync request
 * arriving while the phone app is dead waits until it next runs.
 */
class WearCalendarSync private constructor(context: Context) {

    companion object {
        private const val TAG = "WearCalendarSync"

        @Volatile
        private var instance: WearCalendarSync? = null

        /** The process-wide sync (ViewModel must agree on one). */
        fun get(context: Context): WearCalendarSync = instance ?: synchronized(this) {
            instance ?: WearCalendarSync(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(WearSyncState())
    val state: StateFlow<WearSyncState> = _state.asStateFlow()

    private val listener = object : WearLinkListener {
        override fun onData(path: String, body: ByteArray) {
            if (path != WearCalendarProtocol.WATCH_STATE_PATH) return
            val watchState = runCatching {
                WearCalendarProtocol.json.decodeFromString<WearWatchState>(body.decodeToString())
            }.getOrNull() ?: return
            _state.update {
                it.copy(
                    lastWatchAppliedAtMillis = watchState.appliedGeneratedAtMillis
                        .takeIf { applied -> applied > 0L },
                )
            }
        }

        override fun onMessage(path: String, body: ByteArray) {
            if (path != WearCalendarProtocol.REQUEST_SYNC_PATH) return
            scope.launch { publishSnapshot() }
        }
    }

    private val node = WearLinkNode(appContext, WearCalendarProtocol.CAPABILITY, listener)

    private var started = false
    private var pendingPublish: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            node.state.collect { linkState ->
                val present = linkState is WearLinkState.Connected
                val wasPresent = _state.value.companionPresent
                _state.update { it.copy(companionPresent = wearCompanionPresent(if (present) listOf("peer") else emptyList())) }
                if (present && !wasPresent) syncNow()
            }
        }
        node.start()
    }

    /** Calendar data changed: republish shortly, coalescing bursts (imports, bulk edits). */
    fun onCalendarChanged() {
        if (!_state.value.companionPresent) return
        pendingPublish?.cancel()
        pendingPublish = scope.launch {
            delay(1_000)
            publishSnapshot()
        }
    }

    /** Manual sync from Settings; publishes even when presence is stale/unknown. */
    fun syncNow() {
        scope.launch { publishSnapshot() }
    }

    /** Build the bounded snapshot from the provider and publish it as a data item. */
    suspend fun publishSnapshot() {
        val snapshot = withContext(Dispatchers.IO) { buildSnapshot() } ?: return
        val sent = node.putData(
            WearCalendarProtocol.SNAPSHOT_PATH,
            WearCalendarProtocol.json.encodeToString(snapshot).encodeToByteArray(),
        )
        if (sent) {
            _state.update {
                it.copy(
                    lastPublishedAtMillis = snapshot.generatedAtMillis,
                    lastPublishedEventCount = snapshot.events.size,
                )
            }
        } else {
            Log.status(TAG, "publish snapshot not delivered (no live WearLink session)")
        }
    }

    private fun buildSnapshot(): WearCalendarSnapshot? =
        runCatching {
            val now = Clock.System.now()
            val zone = TimeZone.currentSystemDefault()
            val today = now.toLocalDateTime(zone).date
            val windowStart = today.atStartOfDayIn(zone)
            val windowEnd = windowStart + WearCalendarProtocol.WINDOW_DAYS.days
            val instances = Instance.getVisibleInstances(appContext, windowStart, windowEnd)
            val eventsById = Event.getAllEvents(appContext).associateBy { it.id }
            val calendarsById = Calendar.getAllCalendars(appContext).associateBy { it.id }
            val candidates = instances.map { instance ->
                val event = eventsById[instance.eventID]
                WearCalendarEvent(
                    eventId = instance.eventID,
                    title = instance.eventTitle,
                    location = event?.location.orEmpty(),
                    description = event?.description.orEmpty(),
                    calendarName = event?.let { calendarsById[it.calendarID]?.displayName }.orEmpty(),
                    color = instance.color,
                    startMillis = instance.begin,
                    endMillis = instance.end,
                    allDay = instance.allDay,
                    timezone = instance.timezone,
                )
            }
            buildWearSnapshot(
                candidates = candidates,
                generatedAtMillis = now.toEpochMilliseconds(),
                windowStartMillis = windowStart.toEpochMilliseconds(),
                windowEndMillis = windowEnd.toEpochMilliseconds(),
            )
        }.onFailure { Log.status(TAG, "build snapshot failed: ${it.message}") }.getOrNull()
}
