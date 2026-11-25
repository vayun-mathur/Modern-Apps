package com.vayunmathur.findfamily.sync

import android.content.Context
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.domain.WearAlert
import com.vayunmathur.findfamily.domain.WearAlertKind
import com.vayunmathur.findfamily.domain.WearFindFamilyOp
import com.vayunmathur.findfamily.domain.WearFindFamilyProtocol
import com.vayunmathur.findfamily.domain.WearOpKind
import com.vayunmathur.findfamily.domain.WearPerson
import com.vayunmathur.findfamily.domain.WearWatchState
import com.vayunmathur.findfamily.domain.buildWearSnapshot
import com.vayunmathur.findfamily.domain.wearMotionFor
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.findfamily.platform.LocationServiceController
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
import kotlinx.serialization.encodeToString

/** Phone-side wear status; absence of a watch is a normal state, never an error. */
data class WearFindFamilySyncState(
    val companionPresent: Boolean = false,
    val lastPublishedAtMillis: Long? = null,
    val lastPublishedPeopleCount: Int = 0,
    val lastWatchAppliedAtMillis: Long? = null,
)

/**
 * Phone side of the FindFamily wear companion over WearLink.
 *
 * The phone is the source of truth and the only E2EE endpoint: peer
 * reports are decrypted into Room by the existing pipeline, and this
 * class reads that already-decrypted state to build all-people location
 * summaries (see [WearFindFamilyProtocol] for the privacy boundary).
 * Watch ops apply here: STOP_SHARING_NOW flips the same global sharing
 * switch as the Quick Settings tile ([LocationServiceController]),
 * REQUEST_REFRESH republishes from the freshest Room state and nudges
 * the tracking service to reconcile — it does not fake a fresh peer fix,
 * peers still publish on their own heartbeat.
 */
class WearFindFamilySync private constructor(context: Context) {

    companion object {
        private const val TAG = "WearFindFamilySync"

        @Volatile
        private var instance: WearFindFamilySync? = null

        /** The process-wide sync (activity must agree on one). */
        fun get(context: Context): WearFindFamilySync = instance ?: synchronized(this) {
            instance ?: WearFindFamilySync(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository get() = FindFamilyRepository.get(appContext)

    private val _state = MutableStateFlow(WearFindFamilySyncState())
    val state: StateFlow<WearFindFamilySyncState> = _state.asStateFlow()

    private val listener = object : WearLinkListener {
        override fun onData(path: String, body: ByteArray) {
            if (path != WearFindFamilyProtocol.WATCH_STATE_PATH) return
            val watchState = runCatching {
                WearFindFamilyProtocol.json.decodeFromString<WearWatchState>(body.decodeToString())
            }.getOrNull() ?: return
            _state.update {
                it.copy(lastWatchAppliedAtMillis = watchState.appliedGeneratedAtMillis.takeIf { v -> v > 0L })
            }
        }

        override fun onMessage(path: String, body: ByteArray) {
            when (path) {
                WearFindFamilyProtocol.REQUEST_SYNC_PATH -> scope.launch { publishSnapshot() }
                WearFindFamilyProtocol.OP_PATH -> {
                    val op = runCatching {
                        WearFindFamilyProtocol.json.decodeFromString<WearFindFamilyOp>(body.decodeToString())
                    }.getOrNull()
                    if (op == null) {
                        Log.status(TAG, "ignoring malformed wear op")
                        return
                    }
                    scope.launch { applyWearOp(op) }
                }
            }
        }
    }

    private val node = WearLinkNode(appContext, WearFindFamilyProtocol.CAPABILITY, listener)
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
                if (present && !wasPresent) {
                    publishSnapshot()
                    startLiveCollectorsOnce()
                }
            }
        }
        // No eager Room collectors here: the SQLCipher store is opened by
        // the existing ViewModel/service flows, and this sync publishes on
        // connect, on watch request/op, and via [WearFindFamilySync] callers.
        // (Debounced live republishing can ride those flows later without
        // making MainActivity launch open the database on its own.)
        node.start()
    }

    private var liveCollectorsStarted = false

    /** Debounced live republishing, started only after a watch actually connects. */
    private fun startLiveCollectorsOnce() {
        if (liveCollectorsStarted) return
        liveCollectorsStarted = true
        fun republishSoon() {
            pendingPublish?.cancel()
            pendingPublish = scope.launch {
                delay(1_000)
                publishSnapshot()
            }
        }
        scope.launch { runCatching { repository.users.collect { republishSoon() } } }
        scope.launch { runCatching { repository.latestLocationByUser.collect { republishSoon() } } }
    }

    /** Applies one watch op on the phone (source of truth). Visible for tests. */
    internal suspend fun applyWearOp(op: WearFindFamilyOp) {
        when (op.kind) {
            WearOpKind.STOP_SHARING_NOW ->
                LocationServiceController.setGlobalSharingEnabled(appContext, false)
            WearOpKind.REQUEST_REFRESH -> LocationServiceController.syncServiceState(appContext)
        }
        publishSnapshot()
    }

    /** Build the all-people snapshot from Room and publish it as a data item. */
    suspend fun publishSnapshot() {
        val snapshot = buildPhoneSnapshot()
        val sent = node.putData(
            WearFindFamilyProtocol.SNAPSHOT_PATH,
            WearFindFamilyProtocol.json.encodeToString(snapshot).encodeToByteArray(),
        )
        if (sent) {
            _state.update {
                it.copy(
                    lastPublishedAtMillis = snapshot.generatedAtMillis,
                    lastPublishedPeopleCount = snapshot.people.size,
                )
            }
        } else {
            Log.status(TAG, "publish snapshot not delivered (no live WearLink session)")
        }
    }

    /** Room -> wire snapshot. Pure mapping except for the repository reads. */
    internal suspend fun buildPhoneSnapshot() = run {
        val users = repository.getAllUsers().filter {
            it.requestStatus == RequestStatus.MUTUAL_CONNECTION ||
                it.requestStatus == RequestStatus.AWAITING_RESPONSE ||
                it.id == Networking.userid
        }
        val latest = repository.latestLocationsOnce().associateBy { it.userid }
        val waypoints = runCatching { repository.waypointStore.getAll() }.getOrDefault(emptyList())
        val waypointNames = waypoints.associate { it.id to it.name }
        val people = users.map { user ->
            val loc = latest[user.id]
            val sighting = loc?.source == LocationSource.NETWORK_SIGHTING
            WearPerson(
                id = user.id,
                name = user.name,
                placeLabel = user.locationName.takeUnless { it == "Unnamed Location" }.orEmpty(),
                placeSinceMillis = user.lastLocationChangeTime.toEpochMilliseconds(),
                latE7 = loc?.let { (it.coord.lat * 1e7).toInt() },
                lonE7 = loc?.let { (it.coord.lon * 1e7).toInt() },
                accuracyMeters = if (sighting || loc == null) -1 else loc.acc.toInt(),
                updatedAtMillis = loc?.timestamp?.toEpochMilliseconds() ?: 0L,
                reportedAtMillis = loc?.reportedAt?.toEpochMilliseconds() ?: 0L,
                batteryPercent = if (sighting || loc == null) -1 else loc.battery.toInt().coerceIn(0, 100),
                motion = wearMotionFor(loc?.speed, loc != null && !sighting),
                source = loc?.source?.name.orEmpty(),
                sharingEnabled = user.sendingEnabled,
                isSelf = Networking.userid != 0L && user.id == Networking.userid,
            )
        }
        val alerts = buildList {
            for (person in people) {
                if (person.placeLabel.isNotBlank()) {
                    add(WearAlert(WearAlertKind.AT_PLACE, person.id, person.placeLabel, person.placeSinceMillis))
                }
            }
            val noShows = runCatching { repository.noShowAlertStore.getAll() }.getOrDefault(emptyList())
            for (alert in noShows) {
                add(
                    WearAlert(
                        kind = WearAlertKind.NOSHOW,
                        personId = alert.watchedUserId,
                        placeLabel = alert.waypointId?.let { waypointNames[it] }.orEmpty(),
                        atMillis = alert.expectedAt.toEpochMilliseconds(),
                        fired = alert.fired,
                    ),
                )
            }
        }
        buildWearSnapshot(
            candidates = people,
            alerts = alerts,
            sharingEnabled = LocationServiceController.isGlobalSharingEnabled(appContext),
            generatedAtMillis = System.currentTimeMillis(),
        )
    }
}
