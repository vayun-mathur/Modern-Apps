package com.vayunmathur.calendar.sync

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.domain.WearCalendarEvent
import com.vayunmathur.calendar.domain.WearCalendarProtocol
import com.vayunmathur.calendar.domain.WearWatchState
import com.vayunmathur.calendar.domain.buildWearSnapshot
import com.vayunmathur.calendar.domain.wearCompanionPresent
import com.vayunmathur.library.log.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Await a Play services [Task] without taking a coroutines-play-services dependency. */
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

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
 * Phone side of the wear sync: capability discovery, snapshot publish, and
 * watch-state intake.
 *
 * Every Wearable call is wrapped so "no Play services / no watch / watch
 * without the companion" is a normal state ([WearSyncState.companionPresent]
 * = false), never a crash. The Data Layer only connects the two calendars
 * because they share the package name `com.vayunmathur.calendar` and a
 * signing certificate — dev builds both use the debug key, release builds
 * both use the same release key.
 */
class WearCalendarSync private constructor(context: Context) :
    CapabilityClient.OnCapabilityChangedListener,
    DataClient.OnDataChangedListener {

    /** Holds singleton access. */
    companion object {
        private const val TAG = "WearCalendarSync"
        private const val KEY_JSON = "json"
        private const val KEY_SENT_AT = "sent_at"

        @Volatile
        private var instance: WearCalendarSync? = null

        /** The process-wide sync (ViewModel and listener service must agree on one). */
        fun get(context: Context): WearCalendarSync = instance ?: synchronized(this) {
            instance ?: WearCalendarSync(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(WearSyncState())

    /** Current wear presence + last-sync truth for the UI. */
    val state: StateFlow<WearSyncState> = _state.asStateFlow()

    private var started = false
    private var pendingPublish: Job? = null

    /** Begin capability/data listening; no-op when already started or unavailable. */
    fun start() {
        if (started) return
        started = true
        runCatching {
            Wearable.getCapabilityClient(appContext)
                .addListener(this, WearCalendarProtocol.CAPABILITY)
            Wearable.getDataClient(appContext).addListener(this)
        }.onFailure { Log.status(TAG, "Wearable listeners unavailable: ${it.message}") }
        scope.launch { refreshCapability() }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        updatePresence(capabilityInfo.nodes.map { it.id })
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != WearCalendarProtocol.WATCH_STATE_PATH) continue
            val raw = runCatching {
                DataMapItem.fromDataItem(item).dataMap.getString(KEY_JSON)
            }.getOrNull() ?: continue
            val watchState = runCatching {
                WearCalendarProtocol.json.decodeFromString<WearWatchState>(raw)
            }.getOrNull() ?: continue
            _state.update {
                it.copy(lastWatchAppliedAtMillis = watchState.appliedGeneratedAtMillis
                    .takeIf { applied -> applied > 0L })
            }
        }
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

    /** Build the bounded snapshot from the provider and publish it on DataClient. */
    suspend fun publishSnapshot() {
        val snapshot = withContext(Dispatchers.IO) { buildSnapshot() } ?: return
        runCatching {
            val request = PutDataMapRequest.create(WearCalendarProtocol.SNAPSHOT_PATH).apply {
                dataMap.putString(KEY_JSON, WearCalendarProtocol.json.encodeToString(snapshot))
                dataMap.putLong(KEY_SENT_AT, snapshot.generatedAtMillis)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(appContext).putDataItem(request).awaitTask()
            _state.update {
                it.copy(
                    lastPublishedAtMillis = snapshot.generatedAtMillis,
                    lastPublishedEventCount = snapshot.events.size,
                )
            }
        }.onFailure { Log.status(TAG, "publish snapshot failed: ${it.message}") }
    }

    private suspend fun refreshCapability() {
        runCatching {
            val info = Wearable.getCapabilityClient(appContext)
                .getCapability(WearCalendarProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .awaitTask()
            updatePresence(info.nodes.map { it.id })
        }.onFailure { Log.status(TAG, "capability lookup unavailable: ${it.message}") }
    }

    private fun updatePresence(nodeIds: List<String>) {
        val present = wearCompanionPresent(nodeIds)
        val wasPresent = _state.value.companionPresent
        _state.update { it.copy(companionPresent = present) }
        if (present && !wasPresent) syncNow()
    }

    private fun buildSnapshot(): com.vayunmathur.calendar.domain.WearCalendarSnapshot? =
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
