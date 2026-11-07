package com.vayunmathur.findfamily.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import com.vayunmathur.library.log.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.NoShowAlert
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.platform.NoShowCheckScheduler
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.library.map.GeoPoint
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * ViewModel for the FindFamily app.
 *
 * Owns:
 *  - selection state (selected user / waypoint, history vs present, historical position)
 *  - waypoint editing form state + persistence
 *  - exposed DB-backed flows for users / waypoints / temporary links and the
 *    latest LocationValue per user (`latestLocationByUser`)
 *  - raw per-user location-history flow (keyed on selected user)
 *  - location-permission flags (foreground + background)
 *  - cached feature-availability check (network provider + geocoder)
 *  - networking-backed writes invoked from dialogs (temporary-link creation,
 *    add/accept person)
 *  - UWB Find Nearby (UWB) session state and handshake
 *  - one-time startup work previously triggered from composables (sync job,
 *    self-user registration, week-old location cleanup)
 */
class FindFamilyViewModel(
    application: Application,
    private val repository: FindFamilyRepository,
) : AndroidViewModel(application), FamilyListActions, PersonActions {

    private val ctx: Context get() = getApplication()

    // ------------------------------------------------------------------
    // DB-backed exposed flows
    // ------------------------------------------------------------------

    val users: StateFlow<List<User>> = repository.users
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val waypoints: StateFlow<List<Waypoint>> = repository.waypoints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val temporaryLinks: StateFlow<List<TemporaryLink>> = repository.temporaryLinks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val latestLocationByUser: StateFlow<Map<Long, LocationValue>> = repository.latestLocationByUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Connected people (mutual connection or awaiting our response) — the main list. */
    val connectedUsers: StateFlow<List<User>> = repository.users
        .map { list ->
            list.filter {
                it.requestStatus == RequestStatus.MUTUAL_CONNECTION ||
                    it.requestStatus == RequestStatus.AWAITING_RESPONSE
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Inbound requests awaiting the user's acceptance. */
    val awaitingRequestUsers: StateFlow<List<User>> = repository.users
        .map { list -> list.filter { it.requestStatus == RequestStatus.AWAITING_REQUEST } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Users keyed by id, so the UI can do id-based lookups without scanning. */
    val usersById: StateFlow<Map<Long, User>> = repository.users
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Names of the users currently located at each location/waypoint name. */
    val usersByLocationName: StateFlow<Map<String, List<String>>> = repository.users
        .map { list -> list.groupBy({ it.locationName }, { it.name }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // ------------------------------------------------------------------
    // Composable single-item accessors
    // ------------------------------------------------------------------

    @Composable
    fun userByIdState(id: Long, default: () -> User? = { null }): State<User?> {
        val list by users.collectAsState()
        return remember(id) { derivedStateOf { list.firstOrNull { it.id == id } ?: default() } }
    }

    @Composable
    fun waypointByIdState(id: Long, default: () -> Waypoint? = { null }): State<Waypoint?> {
        val list by waypoints.collectAsState()
        return remember(id) { derivedStateOf { list.firstOrNull { it.id == id } ?: default() } }
    }

    // ------------------------------------------------------------------
    // Mutation methods
    // ------------------------------------------------------------------

    fun deleteUser(user: User) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteUser(user)
            LocationServiceController.syncServiceState(ctx)
        }
    }

    /** Computes the end-to-end verification security code for a connected [user] (off the main thread). */
    suspend fun securityCodeFor(user: User): String? =
        withContext(Dispatchers.IO) { Networking.securityCode(user) }

    fun upsertUser(user: User, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.upsertUser(user)
            LocationServiceController.syncServiceState(ctx)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    /**
     * Connects to a peer, but first checks their post-quantum capability. If the peer is
     * on an outdated (classic-only) app, invokes [onNeedsUpdate] and does NOT add them.
     * Unknown (not-yet-registered) peers are allowed through — they may register with PQC
     * once they install the app.
     *
     * [inviteFingerprint] is the `k=` value of the invite link this started from, if any. The
     * relay's key must hash to it, otherwise [onKeyMismatch] fires and the peer is not added.
     */
    fun connectUser(
        user: User,
        inviteFingerprint: String? = null,
        onNeedsUpdate: () -> Unit,
        onKeyMismatch: () -> Unit = {},
        onDone: () -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val check = Networking.peerCryptoStatus(user.id, inviteFingerprint)
            when (check.status) {
                Networking.PeerCrypto.NEEDS_UPDATE -> withContext(Dispatchers.Main) { onNeedsUpdate() }
                Networking.PeerCrypto.KEY_MISMATCH -> withContext(Dispatchers.Main) { onKeyMismatch() }
                else -> {
                    // Store the fingerprint-checked key with the row itself, so the key that was
                    // verified is the one used from here on rather than a fresh relay lookup.
                    val withKey = check.verifiedBundleB64?.let { user.copy(pqcEncryptionKey = it) } ?: user
                    // Hidden sender-auth secret (GHSA-x94x-v6f7-wwpp): the outbound pair ID this
                    // device puts in every packet to this peer. Minted once at explicit connect
                    // time — never rotated (the peer pins the first value it sees), never
                    // overwritten here (a re-add must not invalidate the peer's pin).
                    val withPair = withKey.outboundPairId?.let { withKey }
                        ?: withKey.copy(
                            outboundPairId = com.vayunmathur.findfamily.domain.PairAuth.generatePairId()
                        )
                    repository.upsertUser(withPair)
                    LocationServiceController.syncServiceState(ctx)
                    withContext(Dispatchers.Main) { onDone() }
                }
            }
        }
    }

    fun updateContactNamePhoto(userId: Long, name: String, photo: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateContactNamePhoto(userId, name, photo)
        }
    }

    fun deleteWaypoint(waypoint: Waypoint) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteWaypoint(waypoint) }
    }

    override fun deleteTemporaryLink(link: TemporaryLink) {
        viewModelScope.launch(Dispatchers.IO) { repository.temporaryLinkStore.delete(link) }
    }

    // ------------------------------------------------------------------
    // Map selection + waypoint-form state (extracted holder, same observables)
    // ------------------------------------------------------------------

    /**
     * Which user / waypoint is selected, present-vs-history, the historical
     * position, and the waypoint editing form. Lives in [MapPageState] so this
     * class stays under the function cap; the StateFlows below are the same
     * observables as before.
     */
    val mapState = MapPageState(viewModelScope, repository)

    val selectedUserId: StateFlow<Long?> = mapState.selectedUserId
    val isShowingPresent: StateFlow<Boolean> = mapState.isShowingPresent
    val historicalPosition: StateFlow<GeoPoint?> = mapState.historicalPosition

    override fun selectUser(userId: Long) = mapState.selectUser(userId)

    /**
     * Apply the initial selection passed in via navigation. Called from the
     * MainPage entry so that opening the screen with a deep-linked user or
     * waypoint id selects it on arrival.
     */
    fun applyInitialSelection(initialUserId: Long?, initialWaypointId: Long?) =
        mapState.applyInitialSelection(initialUserId, initialWaypointId)

    val waypointName: StateFlow<String> = mapState.waypointName
    val waypointRange: StateFlow<String> = mapState.waypointRange

    /** Begin editing an existing waypoint, prefilling the form. */
    override fun beginEditWaypoint(waypoint: Waypoint) = mapState.beginEditWaypoint(waypoint)

    // ------------------------------------------------------------------
    // Per-user location history (raw, filtered by selected user)
    // ------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    val locationHistory: StateFlow<List<LocationValue>> = mapState.selectedUserId
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else repository.locationHistory(userId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    private val _hasForeground = MutableStateFlow(
        ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    )
    val hasForeground: StateFlow<Boolean> = _hasForeground.asStateFlow()

    private val _hasCoarse = MutableStateFlow(
        ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    )
    /** Approximate (coarse) location permission. The app requires fine; this is
     * only used to detect the "approximate only" case and prompt for an upgrade. */
    val hasCoarse: StateFlow<Boolean> = _hasCoarse.asStateFlow()

    private val _hasBackground = MutableStateFlow(
        ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    )
    val hasBackground: StateFlow<Boolean> = _hasBackground.asStateFlow()

    private val _hasBluetooth = MutableStateFlow(
        ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    )

    /**
     * BLUETOOTH_SCAN. Required rather than optional: the finder half of powered-off finding is
     * always on, and without this the scanner starts and immediately closes with no error.
     */
    val hasBluetooth: StateFlow<Boolean> = _hasBluetooth.asStateFlow()

    /** Re-read all permission flags from the OS and reconcile the tracking
     * service. Call on resume so returning from Settings (grant, revoke,
     * downgrade to approximate, "Only this time" expiry) takes effect. */
    fun refreshPermissions() {
        _hasForeground.value = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        _hasCoarse.value = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        _hasBackground.value = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        _hasBluetooth.value = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        syncLocationService()
    }

    /** Reconcile the background service with the current permission + sharing
     * state (start if eligible, stop otherwise). */
    fun syncLocationService() {
        viewModelScope.launch(Dispatchers.IO) {
            LocationServiceController.syncServiceState(ctx)
        }
    }

    /** False while the Quick Settings sharing tile has publishing paused (GitHub #648);
     * the per-person switches are shown but disabled so their state is still visible. */
    val globalSharingEnabled: StateFlow<Boolean> =
        LocationServiceController.globalSharingEnabledFlow(ctx)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Whether this phone has opted in to finding other people's powered-off devices.
     * Opt-in, so it starts false — see [LocationServiceController.CROWD_FINDING_ENABLED_KEY]. */
    val crowdFindingEnabled: StateFlow<Boolean> =
        LocationServiceController.crowdFindingEnabledFlow(ctx)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    override fun setCrowdFinding(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            LocationServiceController.setCrowdFindingEnabled(ctx, enabled)
        }
    }

    /** Toggle per-person location sharing and reconcile the service so it stops
     * when nobody is being shared with and starts when sharing is (re)enabled.
     * Manual toggle always resets auto-toggle to Never.
     *
     * Uses atomic DB update so a stale UI snapshot cannot clobber other columns
     * (e.g. accidentally disabling/enabling when you didn't intend it). */
    override fun setUserSharing(user: User, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setSendingEnabledAndClearToggle(user.id, enabled)
            LocationServiceController.syncServiceState(ctx)
        }
    }

    /** Set auto-toggle for sharing — same durations as one-time links, Never = null. Single field.
     * Atomic — only touches sharingAutoToggleAt, so a stale User snapshot from Compose cannot
     * accidentally flip sendingEnabled when you didn't intend it. */
    override fun setUserAutoToggle(user: User, duration: kotlin.time.Duration?) {
        viewModelScope.launch(Dispatchers.IO) {
            val epoch: Long? = if (duration == null) null else (Clock.System.now() + duration).epochSeconds
            repository.setSharingAutoToggleAt(user.id, epoch)
        }
    }

    /** Set arrival-based auto-toggle for sharing — flips sendingEnabled when "Me" arrives at
     * [waypointId]. Null means Never. Atomic and mutually exclusive with the time-based deadline. */
    override fun setUserArrivalToggle(user: User, waypointId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setSharingAutoToggleWaypointId(user.id, waypointId)
        }
    }

    /** Called from foreground service (or init) when any auto-toggle is due.
     * Atomic SQL flip guarded by the timer value itself — prevents TOCTOU where the user
     * manually cleared or rescheduled the timer but a stale service snapshot still flips. */
    suspend fun applyDueAutoToggles() {
        val nowEpoch = Clock.System.now().epochSeconds
        repository.applyDueAutoToggles(nowEpoch)
    }

    /** Clears expired auto-toggles that are past due but without flipping — used during init cleanup. */
    fun clearExpiredAutoTogglesIfAny() {
        viewModelScope.launch(Dispatchers.IO) {
            applyDueAutoToggles()
        }
    }

    /**
     * Set a no-show watch on [user]: persist a [NoShowAlert] row and schedule
     * its one-shot check. Past-due input is still stored — the scheduler
     * sweeps it immediately rather than rejecting it.
     */
    override fun setNoShowAlert(
        user: User,
        waypointId: Long?,
        expectedAt: kotlin.time.Instant,
        graceMinutes: Int,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val alert = NoShowAlert(
                watchedUserId = user.id,
                waypointId = waypointId,
                expectedAt = expectedAt,
                grace = graceMinutes.minutes,
            )
            val id = repository.noShowAlertStore.upsert(alert)
            NoShowCheckScheduler.schedule(ctx, alert.copy(id = id))
        }
    }

    // ------------------------------------------------------------------
    // Feature check (network provider + geocoder)
    // ------------------------------------------------------------------

    /**
     * True iff this device is missing one of the features FindFamily needs
     * (network location provider or system geocoder). Computed once.
     */
    val missingFeatures: Boolean by lazy {
        val locationManager =
            ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isNetworkEnabled =
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val isGeocoderPresent = Geocoder.isPresent()
        !isNetworkEnabled || !isGeocoderPresent
    }

    /** SQLCipher passphrase for the DB backup buttons; read once, off the composables. */
    val backupPassphrase: String by lazy { DatabaseHelper(ctx).getPassphrase() }

    // ------------------------------------------------------------------
    // Networking-backed dialog actions
    // ------------------------------------------------------------------

    /**
     * Generate an ML-KEM link key and persist a [TemporaryLink] for sharing. The link's whole
     * secret is a 32-byte seed; the public bundle is what locations get encrypted to.
     * Calls [onDone] on the main thread once the upsert completes.
     */
    fun createTemporaryLink(name: String, expiry: Duration, onDone: (success: Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            // Links are post-quantum only: if the PQC keygen fails (native lib unavailable)
            // there is nothing to fall back to, so report failure instead of minting a link
            // that can never publish.
            val linkKey = runCatching { Networking.generatePqcLinkKey() }
                .onFailure { Log.status("FindFamilyViewModel", "createTemporaryLink: PQC keygen failed", it) }
                .getOrNull()
            if (linkKey == null) {
                withContext(Dispatchers.Main) { onDone(false) }
                return@launch
            }
            val newLink = TemporaryLink(
                name = name,
                deleteAt = Clock.System.now() + expiry,
                pqcPublicKey = linkKey.publicBundleB64,
                pqcSeed = linkKey.seedB64Url,
            )
            repository.temporaryLinkStore.upsert(newLink)
            withContext(Dispatchers.Main) { onDone(true) }
        }
    }

    // ------------------------------------------------------------------
    // UWB Find Nearby (UWB)
    //
    // The session lifecycle is owned by the process-global UwbSessionManager
    // (hosted by LocationTrackingService so it can auto-accept incoming
    // requests in the background). This VM is a thin façade so the UI can
    // observe state and trigger initiator/stop actions.
    // ------------------------------------------------------------------

    val uwbSession: StateFlow<UwbSessionManager.UwbSessionState> = UwbSessionManager.state

    /** The peer this session is currently with (initiator OR responder), if any. */
    val uwbPeerUserId: StateFlow<Long?> = UwbSessionManager.peerUserId

    /**
     * True iff the tracking service has fallen back to GPS-only location because
     * the device has no network location provider. Backed by
     * [LocationProviderStatus]; the UI uses it to show a battery-drain warning.
     */
    val usingGpsFallback: StateFlow<Boolean> = LocationProviderStatus.usingGpsFallback

    /** User tapped "Find with Precision" on the given peer. */
    fun startRanging(peerUserId: Long) {
        UwbSessionManager.startAsInitiator(peerUserId)
    }

    /** User dismissed the Find Nearby (UWB) screen. */
    fun stopRanging() {
        UwbSessionManager.stop()
    }

    // ------------------------------------------------------------------
    // One-time startup work
    // ------------------------------------------------------------------

    init {
        // Ensure the device identity (userid + keypair + self-user row) exists as
        // soon as the app is opened, independent of location permission or the
        // tracking service. Networking.init() is idempotent and mutex-guarded, so
        // it's safe for the service to also call it (it may start before the UI).
        viewModelScope.launch(Dispatchers.IO) {
            Networking.init(repository, DataStoreUtils.getInstance(ctx), ctx.getString(R.string.me_label))
            // Apply any due auto-toggles on cold start
            applyDueAutoToggles()
            // Re-arm no-show checks: WorkManager work can be lost (app data
            // restore, force-stop quirks), and an overdue unfired alert then
            // sweeps immediately instead of never firing.
            runCatching { NoShowCheckScheduler.rescheduleAll(ctx) }
        }
        // Trim location history older than a week.
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = Clock.System.now() - 7.days
            repository.deleteLocationsOlderThan(cutoff.epochSeconds)
        }
        // Schedule the recurring sync work that drives location-tracking restarts.
        ensureSync(ctx)
    }
}

/** Factory for constructing [FindFamilyViewModel] with the shared repository. */
class FindFamilyViewModelFactory(
    private val application: Application,
    private val repository: FindFamilyRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FindFamilyViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return FindFamilyViewModel(application, repository) as T
    }
}
