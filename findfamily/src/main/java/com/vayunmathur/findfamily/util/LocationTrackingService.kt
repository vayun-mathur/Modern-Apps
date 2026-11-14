package com.vayunmathur.findfamily.util
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.havershine
import com.vayunmathur.findfamily.domain.MotionTransition
import com.vayunmathur.findfamily.domain.MovementDetector
import com.vayunmathur.findfamily.platform.CurrentLocationSource
import com.vayunmathur.findfamily.platform.FinalLocationReporter
import com.vayunmathur.findfamily.platform.FixCeilingAlarm
import com.vayunmathur.findfamily.platform.onFixCeilingAlarm
import com.vayunmathur.findfamily.platform.refreshFixCeiling
import com.vayunmathur.findfamily.platform.onLocationFix
import com.vayunmathur.findfamily.platform.keepAliveTick
import com.vayunmathur.findfamily.platform.stopLowPowerUpdates
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.findfamily.uwb.UwbEnvelopeKind
import com.vayunmathur.findfamily.uwb.UwbInbox
import com.vayunmathur.findfamily.BuildConfig
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.tracker.PoweredOffKeyStore
import com.vayunmathur.findfamily.tracker.PoweredOffReporting
import com.vayunmathur.findfamily.tracker.PoweredOffScanner
import com.vayunmathur.findfamily.tracker.poweredOffGrantSigningBytes
import com.vayunmathur.findfamily.tracker.TrackerBeaconScanner
import com.vayunmathur.findfamily.tracker.TrackerReporting
import com.vayunmathur.findfamily.tracker.TrackerStore
import com.vayunmathur.findfamily.MainActivity
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.service.SharingTileService
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.work.startRepeatedTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LocationTrackingService : Service(), SensorEventListener {
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    internal lateinit var powerManager: PowerManager
    private var accelerometer: Sensor? = null
    private var significantMotionSensor: Sensor? = null

    // One extra wake-up source, no longer the only one (#895): fixes from the always-on
    // low-power request and the periodic forced fix can also flip the phone to moving.
    private val triggerEventListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            serviceScope.launch(Dispatchers.Main) { startMovingNow() }
        }
    }

    private val lazyRepository by lazy { FindFamilyRepository.get(this) }

    /** Test seam: an externally built repository instead of the SQLCipher singleton. */
    internal var repositoryOverride: FindFamilyRepository? = null

    internal val repository: FindFamilyRepository get() = repositoryOverride ?: lazyRepository
    internal lateinit var bm: BatteryManager
    
    private var isGpsRunning = false
    private var accelerometerRegistered = false
    internal var lastKnownLocation: Location? = null

    /**
     * Moving/still state machine (#895): acceleration, fix speed and fix displacement all
     * count, so a car or tram at steady speed stays "moving". See [MovementDetector].
     */
    internal val motion = MovementDetector()

    /** True while active (10 s network / GPS) updates run; owned by [motion]. */
    internal val isMoving: Boolean get() = motion.isMoving

    // Sender keep-alive (#895). The logic lives in platform/LocationKeepAlive.kt; the state
    // lives here because it is per-service-instance.

    /** Always-on coarse NETWORK request; catches departures while "still". */
    internal val lowPowerListener = LocationListener { onLocationFix(it) }

    /** PASSIVE request: free fixes other apps (e.g. navigation) already paid for. */
    internal val passiveListener = LocationListener { onLocationFix(it) }

    /** Interval of the current low-power request, or 0 when none is registered. */
    internal var lowPowerIntervalMs = 0L
    internal var passiveRequested = false

    /**
     * elapsedRealtime of the last forced-fix attempt, or null before the first one. Every
     * attempt is an active getCurrentLocation request, so this is also what the 15-minute
     * ceiling measures from (see ForcedFixPolicy.isCeilingDue).
     */
    @Volatile
    internal var lastForcedFixAttemptMs: Long? = null
    internal var forcedFixJob: Job? = null

    /**
     * True while the phone shares with someone: the 15-minute active-check ceiling applies
     * and its Doze-proof alarm is armed (platform/FixCeilingAlarm.kt). Main thread writes.
     */
    @Volatile
    internal var fixCeilingEnabled = false

    /** The forced fix's partial wake lock, created on first use. */
    internal var forcedFixWakeLockHolder: PowerManager.WakeLock? = null

    /** Test seam: replaces LocationManager.getCurrentLocation for the forced fix. */
    internal var currentLocationSourceOverride: CurrentLocationSource? = null

    /**
     * Monotonic timestamp (elapsedRealtime) of the last fix delivered by
     * NETWORK_PROVIDER since the current request, or 0 if none has arrived yet.
     * Stamped on *every* network fix, however coarse — absence of fixes, not bad
     * accuracy, is what the no-lock watchdog watches for.
     */
    @Volatile
    internal var lastNetworkFixElapsedMs = 0L

    /**
     * Monotonic timestamp (elapsedRealtime) of the last successful network-provider
     * request in [setupLocationUpdates]. Reference point for the watchdog when no
     * fix has ever arrived: without it a fresh request on a device that never had
     * a fix would look "overdue" immediately.
     */
    @Volatile
    internal var networkRequestElapsedMs = 0L

    /** True while NETWORK_PROVIDER updates are currently requested. */
    @Volatile
    internal var networkRequested = false
    private var networkWatchdogJob: Job? = null
    private var heartbeatJob: Job? = null
    internal var trackingInitialized = false

    /** The pre-unlock publish loop; null once the user unlocks and the normal path takes over. */
    internal var directBootJob: Job? = null

    /**
     * Guards against double-registering listeners when the pre-unlock path has already
     * registered them and [startTracking] then runs after unlock.
     */
    private var sensorsRegistered = false

    /** Signature of the roster last written to [DirectBootStore], to avoid rewriting it every tick. */
    internal var lastSeededMirror: String? = null

    /**
     * Who a publish would go to right now, sharing switches already applied.
     *
     * Kept in memory purely so [FinalLocationReporter] can read it without a disk round-trip on
     * the shutdown path, where the whole budget is a couple of seconds.
     */
    @Volatile
    internal var publishRoster: List<DirectBootStore.Target> = emptyList()

    /** Serializes the off-reader enrichment batches (see [processIncomingLocations]). */
    internal val enrichmentMutex = Mutex()

    // Custom UWB tracker feature (DEV_BUILD only). Owner-side store of tracker
    // secrets/private keys, and the finder-side beacon scan job.
    internal var trackerStore: TrackerStore? = null
    internal var trackerScanJob: Job? = null

    // Powered-off finding. Not DEV_BUILD gated: the finder half is ordinary BLE and is meant to
    // work on any phone with findfamily installed.
    internal var poweredOffKeys: PoweredOffKeyStore? = null
    internal var poweredOffScanJob: Job? = null
    internal var lastPoweredOffPollMs = 0L

    private val networkListener = LocationListener { location ->
        lastNetworkFixElapsedMs = SystemClock.elapsedRealtime()
        onLocationFix(location)
        if (!location.hasAccuracy() || location.accuracy > MAX_FIX_ACCURACY_METERS) {
            if (!isGpsRunning && isMoving) startGps()
        } else {
            if (isGpsRunning) stopGps()
        }
    }
    private val gpsListener = LocationListener { location ->
        onLocationFix(location)
    }

    // The heartbeat and fix selection live in LocationTrackingHeartbeat.kt.
    // The inbound pipeline lives in LocationTrackingInbound.kt.

    // Custom UWB tracker crowd-finding (DEV_BUILD only).
    // The paths live in LocationTrackingCrowdFinding.kt.

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val accel = sqrt(x*x + y*y + z*z)
            // Low acceleration alone no longer means "still": the detector also
            // needs the recent fixes to show no speed or displacement (#895).
            applyMotionTransition(motion.onAcceleration(accel, SystemClock.elapsedRealtime()))
        }
    }

    /** Something outside the fix stream says "moving" (significant motion, no fix yet). */
    internal fun startMovingNow() {
        motion.markMoving(SystemClock.elapsedRealtime())
        setupLocationUpdates()
        registerAccelerometer()
    }

    /** Switches the radios for a [MovementDetector] verdict. Main thread. */
    internal fun applyMotionTransition(transition: MotionTransition) {
        when (transition) {
            MotionTransition.BECAME_MOVING -> {
                Log.status(TAG_MOTION, "moving: starting active location updates")
                setupLocationUpdates()
                // Watch for stillness again (it was unregistered when the phone went still).
                registerAccelerometer()
            }
            MotionTransition.BECAME_STILL -> {
                Log.status(TAG_MOTION, "still: active updates off, low-power request stays on")
                stopTrackingUpdates()
                if (significantMotionSensor != null) {
                    unregisterAccelerometer()
                    requestSignificantMotion()
                }
            }
            MotionTransition.NONE -> Unit
        }
    }

    private fun registerAccelerometer() {
        if (accelerometerRegistered || !::sensorManager.isInitialized) return
        val sensor = accelerometer ?: return
        accelerometerRegistered =
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterAccelerometer() {
        if (!accelerometerRegistered) return
        sensorManager.unregisterListener(this, accelerometer)
        accelerometerRegistered = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        setupNotificationChannels()
        // Runtime registration is not a style choice: neither ACTION_SHUTDOWN nor
        // ACTION_BATTERY_LOW reaches a manifest-declared receiver.
        FinalLocationReporter.start(this, { lastKnownLocation }, { publishRoster })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The 15-minute ceiling alarm (#895 follow-up). Only meaningful for a service that
        // is already tracking in the foreground; anything else takes the normal start path.
        if (intent?.action == FixCeilingAlarm.ACTION_CHECK) {
            val tracking = trackingInitialized || directBootJob?.isActive == true
            if (tracking && LocationServiceController.hasFineLocationPermission(this)) {
                onFixCeilingAlarm()
                return START_STICKY
            }
            FixCeilingAlarm.releaseWakeLock()
        }
        // Defensive: never run location tracking without fine-location permission.
        // The service can be (re)started by the OS, WorkManager, or BootReceiver,
        // and the permission may have been revoked since it was scheduled
        // (e.g. "Only this time" grant expiring, or the user switching to
        // approximate-only / "Ask every time" / "Don't allow").
        if (!LocationServiceController.hasFineLocationPermission(this)) {
            // We were started via startForegroundService and must satisfy the
            // foreground-start contract. Use the type-less startForeground so it
            // doesn't throw without the location permission, then stop.
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (_: Exception) {
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()

        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (isUserUnlocked()) startTracking() else startDirectBootTracking()
        return START_STICKY
    }

    /**
     * Before the first unlock after a reboot, Room and the default DataStore are
     * credential-encrypted and unreadable, so the normal path cannot run at all. Publish from
     * the device-protected mirror instead and hand over the moment the user unlocks.
     *
     * The body lives in LocationTrackingDirectBoot.kt.
     */
    internal fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    internal val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_UNLOCKED) onUserUnlocked()
        }
    }
    internal var unlockReceiverRegistered = false

    // The direct-boot path lives in LocationTrackingDirectBoot.kt.

    // The unlock handover lives in LocationTrackingDirectBoot.kt.

    // Broad catch is deliberate: mirror-seed failures must not kill the heartbeat loop.
    @Suppress("TooGenericExceptionCaught")
    internal fun startTracking() {
        serviceScope.launch {
            if (!trackingInitialized) {
                Networking.init(
                    repository,
                    DataStoreUtils.getInstance(this@LocationTrackingService),
                    getString(R.string.me_label)
                )

                // Hoist the UWB ranging session into this foreground service
                // so we can auto-accept incoming Find Nearby (UWB) requests
                // (and keep the session alive) without the user having to
                // bring the app to foreground first. See UwbSessionManager.
                UwbSessionManager.init(this@LocationTrackingService, repository)

                // Custom UWB tracker crowd-finding (DEV_BUILD): owner-side secret/key
                // store. Gated so release builds never touch it.
                if (BuildConfig.DEV_BUILD) {
                    trackerStore = TrackerStore(DataStoreUtils.getInstance(this@LocationTrackingService))
                }
                // Device-protected, and it MUST match PoweredOffBeacon.keys(). The beacon half
                // arms from a shutdown broadcast that can fire before first unlock — a flat
                // battery does not wait for a passcode — so it writes the beacon secret and the
                // ML-KEM private bundle to the direct-boot store. Reading them back from the
                // ordinary credential-protected store would find nothing, canRead() would be
                // false for every user forever, and retrieval would return zero sightings with
                // no error anywhere. Keep these two constructions pointing at the same store.
                poweredOffKeys = PoweredOffKeyStore(DirectBootStore.store(this@LocationTrackingService))

                withContext(Dispatchers.Main) {
                    registerSensors()
                    // If we don't have any recent location (e.g. fresh start or recovery
                    // from a crash), start moving so setupLocationUpdates()
                    // immediately starts requesting GPS instead of waiting for the
                    // significant-motion sensor to trigger.
                    if (lastKnownLocation == null) startMovingNow() else setupLocationUpdates()
                }
                trackingInitialized = true
            }

            // Cancel any prior heartbeat coroutine so onStartCommand re-entries
            // (e.g. from ServiceRestartWorker) don't stack multiple heartbeat loops.
            heartbeatJob?.cancel()
            heartbeatJob = launch {
                while (isActive) {
                    // "Only this time" grants are revoked once the app leaves the
                    // foreground; detect that here and shut down gracefully rather
                    // than spinning (or crashing) on location access we can't make.
                    if (!LocationServiceController.hasFineLocationPermission(this@LocationTrackingService)) {
                        withContext(Dispatchers.Main) { stopSelf() }
                        break
                    }
                    // Arm or cancel the 15-minute ceiling as sharing changes, then the
                    // stillness re-check, low-power request upkeep and the periodic
                    // forced fix (#895). Main thread: it owns the motion state.
                    refreshFixCeiling()
                    withContext(Dispatchers.Main) { keepAliveTick() }
                    syncHeartbeat()
                    try {
                        seedDirectBootMirror()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.status(TAG_DIRECT_BOOT, "mirror seed failed", e)
                    }
                    if (BuildConfig.DEV_BUILD) runCatching { pollTrackerReports() }
                    runCatching { pollPoweredOffSightings() }
                    delay(30.seconds)
                }
            }

            // Live relay: the server pushes peer locations and UWB envelopes over the
            // WebSocket the instant they arrive, driving the same processing paths the
            // heartbeat used to. This is the only inbound path — there is no HTTP poll.
            // Inbound fixes arrive with their claimed pair ID attached (see InboundFix);
            // processIncomingFixes authenticates, pins, and time-clamps before storing.
            // Frames received before the first unlock were spooled (still encrypted) by the
            // pre-unlock socket; process them first so they land ahead of newer pushes.
            replayPreUnlockInbound()
            Networking.startLive(
                serviceScope,
                onLocations = { processIncomingFixes(it) },
                onUwb = { handleUwbEnvelopes(it) },
            )

            // Finder side of the crowd-finding network: scan for tracker beacons and
            // report each sighting with our own GPS. DEV_BUILD only.
            if (BuildConfig.DEV_BUILD) startTrackerScanner()

            // Powered-off finding, finder half. Opt-in, and available on any build.
            startPoweredOffScanner()
        }
    }

    internal fun registerSensors() {
        if (sensorsRegistered) return
        sensorsRegistered = true
        bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

        if (significantMotionSensor != null) {
            requestSignificantMotion()
        } else {
            registerAccelerometer()
        }
        // Never fully dark while "still" (#895): a coarse network request with a
        // displacement filter plus a passive request, both kept for the service's life.
        // Updated (power-save interval) on every keepAliveTick().
        keepAliveTick()
    }

    private fun requestSignificantMotion() {
        significantMotionSensor?.let {
            sensorManager.requestTriggerSensor(triggerEventListener, it)
        }
    }

    internal fun setupLocationUpdates() {
        if (!isMoving) return
        val isLowPower = powerManager.isPowerSaveMode
        val networkInterval = if (isLowPower) 30_000L else 10_000L

        // Devices without Play Services / MicroG (e.g. GrapheneOS) may have no
        // NETWORK_PROVIDER at all. Requesting updates from a missing provider
        // throws IllegalArgumentException, which used to crash the app on every
        // launch. Guard the request the same way startGps() guards GPS, and when
        // network location is unavailable fall back to GPS-only tracking (the
        // networkListener that would normally start GPS never fires without a
        // network provider).
        val hasNetworkProvider =
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (hasNetworkProvider) {
            LocationProviderStatus.setUsingGpsFallback(false)
            try {
                locationManager.removeUpdates(networkListener)
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    networkInterval,
                    0f,
                    networkListener
                )
                networkRequested = true
                // Fresh request, fresh deadline: a fix delivered to an earlier
                // request must not make this one look overdue on arrival.
                lastNetworkFixElapsedMs = 0L
                networkRequestElapsedMs = SystemClock.elapsedRealtime()
            } catch (_: SecurityException) {
                networkRequested = false
            } catch (_: IllegalArgumentException) {
                networkRequested = false
            }
            startNetworkNoLockWatchdog()
        } else {
            LocationProviderStatus.setUsingGpsFallback(true)
            stopNetworkNoLockWatchdog()
            startGps()
        }
    }

    /** One watchdog poll verdict: keep waiting, stop watching, or start GPS assist now. */
    private enum class WatchdogVerdict { POLL, STOP, ASSIST }

    private fun CoroutineScope.watchdogVerdict(): WatchdogVerdict {
        // Stale evaluation guards: the watchdog only means something while
        // network is still requested and the device is meant to be tracked.
        if (!isActive) return WatchdogVerdict.STOP
        if (!isMoving || !networkRequested) return WatchdogVerdict.STOP
        val lastFix = lastNetworkFixElapsedMs
        val reference = if (lastFix != 0L) lastFix else networkRequestElapsedMs
        if (reference == 0L) return WatchdogVerdict.POLL
        if (SystemClock.elapsedRealtime() - reference < NETWORK_NO_LOCK_TIMEOUT_MS) {
            return WatchdogVerdict.POLL
        }
        if (isGpsRunning) return WatchdogVerdict.POLL
        return WatchdogVerdict.ASSIST
    }

    /**
     * Start GPS in parallel when NETWORK_PROVIDER is enabled but has not produced
     * a fix for a while — indoors, in a Faraday-like building, or while the radio
     * stack stalls, the network listener simply never fires and the old
     * accuracy-based trigger never gets a chance to start GPS.
     *
     * The watchdog polls rather than posting a delayed callback so a stream of
     * network fixes keeps pushing the deadline out instead of GPS firing once
     * regardless: while any network fix arrives the assist stays off and costs
     * nothing. Coarse fixes also reset the deadline — only *silence* triggers
     * GPS. Once network delivers an accurate fix, the existing listener stops
     * GPS again through its normal path.
     */
    internal fun startNetworkNoLockWatchdog() {
        if (networkWatchdogJob?.isActive == true) return
        networkWatchdogJob = serviceScope.launch {
            while (isActive) {
                delay(NETWORK_NO_LOCK_TIMEOUT_MS)
                when (watchdogVerdict()) {
                    WatchdogVerdict.STOP -> break
                    WatchdogVerdict.ASSIST -> {
                        Log.status(
                            TAG_NETWORK_ASSIST,
                            "no network fix for ${NETWORK_NO_LOCK_TIMEOUT_MS}ms, starting GPS in parallel"
                        )
                        withContext(Dispatchers.Main) {
                            startGps()
                        }
                    }
                    WatchdogVerdict.POLL -> Unit
                }
            }
        }
    }

    internal fun stopNetworkNoLockWatchdog() {
        networkWatchdogJob?.cancel()
        networkWatchdogJob = null
    }

    private fun stopTrackingUpdates() {
        networkRequested = false
        stopNetworkNoLockWatchdog()
        locationManager.removeUpdates(networkListener)
        stopGps()
    }

    companion object {
        internal const val CHANNEL_ID = "location_tracking_channel"
        internal const val BATTERY_CHANNEL_ID = "battery_channel"
        internal const val ENTRY_EXIT_CHANNEL_ID = "entry_exit_channel"
        internal const val UWB_REQUEST_CHANNEL_ID = "uwb_request_channel"
        private const val NOTIFICATION_ID = 101
        internal const val TAG_DIRECT_BOOT = "FF-DirectBoot"
        internal const val TAG_POWERED_OFF = "FF-PoweredOff"
        internal const val TAG_NETWORK_ASSIST = "FF-NetworkAssist"
        internal const val TAG_MOTION = "FF-Motion"

        /**
         * How long NETWORK_PROVIDER may go without delivering any fix before GPS
         * is started in parallel. Must exceed the normal network cadence (10s, 30s
         * in power-save) with margin for radio stalls, but stay short enough that
         * a device with no network lock still gets a position promptly. GPS is
         * stopped again as soon as network produces an accurate fix; a GPS-only
         * device (no network provider at all) never arms this path.
         */
        internal const val NETWORK_NO_LOCK_TIMEOUT_MS = 60_000L

        /** How often to drain powered-off sightings. See [LocationTrackingService.pollPoweredOffSightings]. */
        internal const val POWERED_OFF_POLL_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * How stale the held fix has to be before a less accurate one replaces it.
         *
         * Long enough that a burst of coarse network fixes cannot displace a good GPS one,
         * short enough that a device which has lost GPS still reports where it now is.
         */
        private val FIX_MAX_AGE_NANOS = 2.minutes.inWholeNanoseconds
    }

    private fun startGps() {
        if (!isMoving) return
        val isLowPower = powerManager.isPowerSaveMode
        val gpsInterval = if (isLowPower) 180_000L else 60_000L

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.removeUpdates(gpsListener)
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    gpsInterval,
                    0f,
                    gpsListener
                )
                isGpsRunning = true
            }
        } catch (_: SecurityException) {
        }
    }

    private fun stopGps() {
        locationManager.removeUpdates(gpsListener)
        isGpsRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        FinalLocationReporter.stop(this)
        FixCeilingAlarm.cancel(this)
        FixCeilingAlarm.releaseWakeLock()
        if (unlockReceiverRegistered) {
            runCatching { unregisterReceiver(unlockReceiver) }
            unlockReceiverRegistered = false
        }
        serviceScope.cancel()
        Networking.stopLive()
        // These are only initialized once startTracking()/registerSensors() runs.
        // The service can be destroyed before that (e.g. stopped immediately in
        // onStartCommand when permission is missing), so guard every access.
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this)
            significantMotionSensor?.let {
                sensorManager.cancelTriggerSensor(triggerEventListener, it)
            }
        }
        if (::locationManager.isInitialized) {
            stopTrackingUpdates()
            stopLowPowerUpdates()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}

/**
 * Reverse-geocodes a coordinate, or null if it can't be resolved.
 *
 * Devices without Play Services / microG (e.g. GrapheneOS) have no geocode backend at
 * all, so [Geocoder.isPresent] is checked first. The async API also reports failures via
 * `onError`, which must be implemented: a bare lambda only supplies `onGeocode`, leaving
 * the continuation unresumed forever on any error. The timeout is the final backstop —
 * this is a network call, and hanging here blocks whoever is awaiting it.
 */
suspend fun Context.fetchAddress(lat: Double, lng: Double): Address? {
    if (!Geocoder.isPresent()) return null
    return withTimeoutOrNull(15.seconds) {
        suspendCancellableCoroutine { continuation ->
            val geocoder = Geocoder(this@fetchAddress, Locale.getDefault())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Modern Async API (Android 13+)
                geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            } else {
                // Legacy Synchronous (Must be on background thread)
                try {
                    @Suppress("DEPRECATION")
                    val address = geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
                    continuation.resume(address)
                } catch (_: Exception) {
                    continuation.resume(null)
                }
            }
        }
    }
}

class ServiceRestartWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        // Reconcile the service with the current state: only run when fine
        // location is granted AND the user is sharing with at least one person.
        LocationServiceController.syncServiceState(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

fun ensureSync(context: Context) {
    startRepeatedTask<ServiceRestartWorker>(
        context, "Location Sync", 15.minutes,
        ExistingWorkPolicy.REPLACE, ExistingPeriodicWorkPolicy.REPLACE
    )
}
