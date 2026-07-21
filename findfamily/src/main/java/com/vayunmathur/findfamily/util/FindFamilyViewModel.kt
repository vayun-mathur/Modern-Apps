package com.vayunmathur.findfamily.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
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
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.LocationValueDao
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.TemporaryLinkDao
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.UserDao
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.WaypointDao
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
import org.maplibre.spatialk.geojson.Position
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

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
    private val userDao: UserDao,
    private val waypointDao: WaypointDao,
    private val locationValueDao: LocationValueDao,
    private val temporaryLinkDao: TemporaryLinkDao,
) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()

    // ------------------------------------------------------------------
    // DB-backed exposed flows
    // ------------------------------------------------------------------

    val users: StateFlow<List<User>> = userDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val waypoints: StateFlow<List<Waypoint>> = waypointDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val temporaryLinks: StateFlow<List<TemporaryLink>> = temporaryLinkDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val latestLocationByUser: StateFlow<Map<Long, LocationValue>> = locationValueDao.getLatest()
        .map { list -> list.associateBy { it.userid } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Connected people (mutual connection or awaiting our response) — the main list. */
    val connectedUsers: StateFlow<List<User>> = userDao.getAllFlow()
        .map { list ->
            list.filter {
                it.requestStatus == RequestStatus.MUTUAL_CONNECTION ||
                    it.requestStatus == RequestStatus.AWAITING_RESPONSE
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Inbound requests awaiting the user's acceptance. */
    val awaitingRequestUsers: StateFlow<List<User>> = userDao.getAllFlow()
        .map { list -> list.filter { it.requestStatus == RequestStatus.AWAITING_REQUEST } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Users keyed by id, so the UI can do id-based lookups without scanning. */
    val usersById: StateFlow<Map<Long, User>> = userDao.getAllFlow()
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Names of the users currently located at each location/waypoint name. */
    val usersByLocationName: StateFlow<Map<String, List<String>>> = userDao.getAllFlow()
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
            userDao.delete(user)
            LocationServiceController.syncServiceState(ctx)
        }
    }

    /** Computes the end-to-end verification security code for a connected [user] (off the main thread). */
    suspend fun securityCodeFor(user: User): String? =
        withContext(Dispatchers.IO) { Networking.securityCode(user) }

    fun upsertUser(user: User, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            userDao.upsert(user)
            // A new/updated connection can change whether sharing is enabled, so
            // reconcile the tracking service (e.g. start it after the first
            // person is added).
            LocationServiceController.syncServiceState(ctx)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun deleteWaypoint(waypoint: Waypoint) {
        viewModelScope.launch(Dispatchers.IO) { waypointDao.delete(waypoint) }
    }

    fun deleteTemporaryLink(link: TemporaryLink) {
        viewModelScope.launch(Dispatchers.IO) { temporaryLinkDao.delete(link) }
    }

    // ------------------------------------------------------------------
    // Selection state
    // ------------------------------------------------------------------

    private val _selectedUserId = MutableStateFlow<Long?>(null)
    val selectedUserId: StateFlow<Long?> = _selectedUserId.asStateFlow()

    private val _selectedWaypointId = MutableStateFlow<Long?>(null)
    val selectedWaypointId: StateFlow<Long?> = _selectedWaypointId.asStateFlow()

    private val _isShowingPresent = MutableStateFlow(true)
    val isShowingPresent: StateFlow<Boolean> = _isShowingPresent.asStateFlow()

    private val _historicalPosition = MutableStateFlow<Position?>(null)
    val historicalPosition: StateFlow<Position?> = _historicalPosition.asStateFlow()

    fun setSelectedUserId(id: Long?) { _selectedUserId.value = id }
    fun setSelectedWaypointId(id: Long?) { _selectedWaypointId.value = id }
    fun setShowingPresent(value: Boolean) { _isShowingPresent.value = value }
    fun setHistoricalPosition(position: Position?) { _historicalPosition.value = position }

    fun selectUser(userId: Long) {
        _selectedUserId.value = userId
        _selectedWaypointId.value = null
        _isShowingPresent.value = true
    }

    fun clearSelection() {
        _selectedUserId.value = null
        _selectedWaypointId.value = null
    }

    /**
     * Apply the initial selection passed in via navigation. Called from the
     * MainPage entry so that opening the screen with a deep-linked user or
     * waypoint id selects it on arrival.
     */
    fun applyInitialSelection(initialUserId: Long?, initialWaypointId: Long?) {
        _selectedUserId.value = initialUserId
        _selectedWaypointId.value = initialWaypointId
    }

    // ------------------------------------------------------------------
    // Waypoint editing form
    // ------------------------------------------------------------------

    private val _waypointName = MutableStateFlow("")
    val waypointName: StateFlow<String> = _waypointName.asStateFlow()

    private val _waypointRange = MutableStateFlow("")
    val waypointRange: StateFlow<String> = _waypointRange.asStateFlow()

    private val _waypointCoord = MutableStateFlow(Coord(0.0, 0.0))
    val waypointCoord: StateFlow<Coord> = _waypointCoord.asStateFlow()

    fun setWaypointName(name: String) { _waypointName.value = name }
    fun setWaypointRange(range: String) { _waypointRange.value = range }
    fun setWaypointCoord(coord: Coord) { _waypointCoord.value = coord }

    /** Begin creating a brand-new waypoint with sensible defaults. */
    fun beginCreateWaypoint() {
        _selectedWaypointId.value = 0L
        _waypointName.value = ""
        _waypointRange.value = "100"
        _waypointCoord.value = Coord(0.0, 0.0)
    }

    /** Begin editing an existing waypoint, prefilling the form. */
    fun beginEditWaypoint(waypoint: Waypoint) {
        _selectedWaypointId.value = waypoint.id
        _waypointName.value = waypoint.name
        _waypointRange.value = waypoint.range.toString()
        _waypointCoord.value = waypoint.coord
    }

    /**
     * Persist the in-progress waypoint. Silently no-ops if the form is invalid
     * (matching the original FAB-click behaviour).
     */
    fun saveCurrentWaypoint() {
        val name = _waypointName.value
        val range = _waypointRange.value.toDoubleOrNull() ?: return
        if (name.isBlank()) return
        val id = _selectedWaypointId.value ?: return
        val coord = _waypointCoord.value
        viewModelScope.launch(Dispatchers.IO) {
            val base = if (id == 0L) Waypoint.NEW_WAYPOINT else waypointDao.get(id)
            waypointDao.upsert(base.copy(name = name, range = range, coord = coord))
            withContext(Dispatchers.Main) {
                _selectedWaypointId.value = null
            }
        }
    }

    // ------------------------------------------------------------------
    // Per-user location history (raw, filtered by selected user)
    // ------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    val locationHistory: StateFlow<List<LocationValue>> = _selectedUserId
        .flatMapLatest { userId ->
            if (userId == null) flowOf(emptyList())
            else locationValueDao.getByUseridFlow(userId)
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
        syncLocationService()
    }

    /** Reconcile the background service with the current permission + sharing
     * state (start if eligible, stop otherwise). */
    fun syncLocationService() {
        viewModelScope.launch(Dispatchers.IO) {
            LocationServiceController.syncServiceState(ctx)
        }
    }

    /** Toggle per-person location sharing and reconcile the service so it stops
     * when nobody is being shared with and starts when sharing is (re)enabled. */
    fun setUserSharing(user: User, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            userDao.upsert(user.copy(sendingEnabled = enabled))
            LocationServiceController.syncServiceState(ctx)
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
     * Generate an RSA key pair and persist a [TemporaryLink] for sharing.
     * Calls [onDone] on the main thread once the upsert completes.
     */
    fun createTemporaryLink(name: String, expiry: Duration, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val keypair = Networking.generateKeyPair()
            val newLink = TemporaryLink(
                name,
                Base64.encode(keypair.privateKeyPem),
                Base64.encode(keypair.publicKeyPem),
                Clock.System.now() + expiry,
            )
            temporaryLinkDao.upsert(newLink)
            withContext(Dispatchers.Main) { onDone() }
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
            Networking.init(userDao, DataStoreUtils.getInstance(ctx), ctx.getString(R.string.me_label))
        }
        // Trim location history older than a week.
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = Clock.System.now() - 7.days
            locationValueDao.deleteOlderThan(cutoff.epochSeconds)
        }
        // Schedule the recurring sync work that drives location-tracking restarts.
        ensureSync(ctx)
    }
}

/** Factory for constructing [FindFamilyViewModel] with the four DAOs. */
class FindFamilyViewModelFactory(
    private val application: Application,
    private val userDao: UserDao,
    private val waypointDao: WaypointDao,
    private val locationValueDao: LocationValueDao,
    private val temporaryLinkDao: TemporaryLinkDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FindFamilyViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return FindFamilyViewModel(
            application,
            userDao,
            waypointDao,
            locationValueDao,
            temporaryLinkDao,
        ) as T
    }
}
