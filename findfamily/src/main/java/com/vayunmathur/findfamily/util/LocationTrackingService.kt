package com.vayunmathur.findfamily.util
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.math.sqrt
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.LocationValueDao
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.TemporaryLinkDao
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.UserDao
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.WaypointDao
import com.vayunmathur.findfamily.data.havershine
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.findfamily.uwb.UwbEnvelopeKind
import com.vayunmathur.findfamily.uwb.UwbInbox
import com.vayunmathur.findfamily.MainActivity
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.work.startRepeatedTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LocationTrackingService : Service(), SensorEventListener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private var accelerometer: Sensor? = null
    private var significantMotionSensor: Sensor? = null

    private val triggerEventListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            isMoving = true
            lastMovementTime = System.currentTimeMillis()
            serviceScope.launch(Dispatchers.Main) {
                setupLocationUpdates()
            }
            // Start monitoring for stillness
            accelerometer?.let {
                sensorManager.registerListener(this@LocationTrackingService, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    private lateinit var userDao: UserDao
    private lateinit var waypointDao: WaypointDao
    private lateinit var locationValueDao: LocationValueDao
    private lateinit var temporaryLinkDao: TemporaryLinkDao
    private lateinit var bm: BatteryManager
    
    private var isGpsRunning = false
    private var isMoving = false
    private var lastMovementTime = 0L
    private var lastKnownLocation: Location? = null
    private var heartbeatJob: Job? = null
    private var uwbPollJob: Job? = null
    private var trackingInitialized = false

    private val networkListener = LocationListener { location ->
        lastKnownLocation = location
        if (location.accuracy > 100f) {
            if (!isGpsRunning && isMoving) startGps()
        } else {
            if (isGpsRunning) stopGps()
        }
    }
    private val gpsListener = LocationListener { location ->
        lastKnownLocation = location
    }

    private suspend fun syncHeartbeat() {
        val location = lastKnownLocation ?: return
        if (Networking.userid == 0L) return
        
        val currentUsers = userDao.getAll()
        val currentWaypoints = waypointDao.getAll()
        val currentLinks = temporaryLinkDao.getAll()
        val userIDs = currentUsers.map { it.id }
        val now = Clock.System.now()

        val locationValue = LocationValue(
            Networking.userid,
            Coord(location.latitude, location.longitude),
            0f,
            location.accuracy,
            now,
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
        )

        withContext(Dispatchers.IO) {
            locationValueDao.upsert(locationValue)
            Networking.ensureUserExists()
            
            if (currentUsers.none { it.id == Networking.userid }) {
                userDao.upsert(
                    User(
                        getString(R.string.me_label),
                        null,
                        "Unnamed Location",
                        true,
                        RequestStatus.MUTUAL_CONNECTION,
                        Clock.System.now(),
                        null,
                        Networking.userid
                    )
                )
            }

            currentUsers.filter { it.id != Networking.userid }.forEach { Networking.publishLocation(locationValue, it) }
            currentLinks.filter { now < it.deleteAt }.forEach { Networking.publishLocation(locationValue, it) }
            currentLinks.filter { now >= it.deleteAt }.forEach { temporaryLinkDao.delete(it) }

            delay(3000)
            Networking.receiveLocations()?.let { locations ->
                val usersRecieved = locations.map { it.userid }.distinct()
                val newUsers = usersRecieved.filter { it !in userIDs && it != Networking.userid }
                userDao.insertAllIgnore(newUsers.map {
                    User(" ", null, "Unknown Location", false, RequestStatus.AWAITING_REQUEST, Clock.System.now(), null, it)
                })

                val latestMap = locationValueDao.getLatest().first().associateBy { it.userid }
                currentUsers.forEach { user ->
                    val lastLoc = locations.filter { it.userid == user.id }.maxByOrNull { it.timestamp } ?: return@forEach
                    val lastSavedLoc = latestMap[user.id]

                    if (lastLoc.battery <= 15f && (lastSavedLoc?.battery ?: 100f) > 15f) {
                        if (user.id != Networking.userid) {
                            createNotificationWithCategory(user.name, getString(R.string.notification_low_battery, user.name), "BATTERY_LOW", user.id)
                        }
                    }

                    val inWaypoint = currentWaypoints.find { havershine(it.coord, lastLoc.coord) < it.range }
                    val prevId = user.lastWaypointId
                    val stillInsidePrev = prevId?.let { pid ->
                        currentWaypoints.find { it.id == pid }?.let {
                            havershine(it.coord, lastLoc.coord) < it.range * 1.2
                        }
                    } ?: false
                    val currentId: Long? = inWaypoint?.id ?: if (stillInsidePrev) prevId else null

                    // Display name: prefer waypoint name (either entered or sticky-via-hysteresis), then geocoded address.
                    val displayName = inWaypoint?.name
                        ?: currentWaypoints.find { it.id == currentId }?.name
                        ?: fetchAddress(lastLoc.coord.lat, lastLoc.coord.lon)?.let {
                            it.featureName ?: it.thoroughfare
                        }
                        ?: "Unknown Location"

                    if (currentId != prevId || displayName != user.locationName) {
                        userDao.upsert(user.copy(
                            locationName = displayName,
                            lastWaypointId = currentId,
                            lastLocationChangeTime = lastLoc.timestamp
                        ))
                    }

                    if (currentId != prevId && user.id != Networking.userid) {
                        if (currentId != null) {
                            val enteredName = inWaypoint?.name
                                ?: currentWaypoints.find { it.id == currentId }?.name
                                ?: displayName
                            createNotificationWithCategory(user.name, getString(R.string.notification_entered_waypoint, user.name, enteredName), "ENTRY_EXIT", user.id)
                        } else if (prevId != null) {
                            val exitedName = currentWaypoints.find { it.id == prevId }?.name ?: user.locationName
                            createNotificationWithCategory(user.name, getString(R.string.notification_exited_waypoint, user.name, exitedName), "ENTRY_EXIT", user.id)
                        }
                    }
                }
                locationValueDao.upsertAll(locations)
            }
        }
    }

    /**
     * Fast-path drain of the UWB session-setup channel, run on a dedicated
     * coroutine every 3s (vs. the 30s heartbeat). Each envelope is forwarded
     * to [UwbInbox] so the manager / open screen can react; REQUEST envelopes
     * also fire a local notification so the user knows ranging is happening.
     */
    private suspend fun drainUwbInbox() {
        val envelopes = Networking.receiveUwbMessages() ?: return
        if (envelopes.isEmpty()) return
        val users = userDao.getAll()
        for (envelope in envelopes) {
            UwbInbox.tryEmit(envelope)
            if (envelope.kind == UwbEnvelopeKind.REQUEST) {
                val senderId = envelope.sender.toLong()
                val senderName = users.firstOrNull { it.id == senderId }?.name
                    ?: getString(R.string.uwb_unknown_peer_name)
                createUwbRequestNotification(senderName, senderId)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val accel = sqrt(x*x + y*y + z*z)
            println(accel)
            if (accel > 0.5f) {
                lastMovementTime = System.currentTimeMillis()
                if (!isMoving) {
                    isMoving = true
                    setupLocationUpdates()
                }
            } else {
                if (isMoving && (System.currentTimeMillis() - lastMovementTime > 60_000L)) {
                    isMoving = false
                    stopTrackingUpdates()
                    if (significantMotionSensor != null) {
                        sensorManager.unregisterListener(this, accelerometer)
                        requestSignificantMotion()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        serviceScope.launch {
            if (!trackingInitialized) {
                val db = buildDatabase<FFDatabase>()
                userDao = db.userDao()
                waypointDao = db.waypointDao()
                locationValueDao = db.locationValueDao()
                temporaryLinkDao = db.temporaryLinkDao()
                Networking.init(userDao, DataStoreUtils.getInstance(this@LocationTrackingService), getString(R.string.me_label))

                // Hoist the UWB ranging session into this foreground service
                // so we can auto-accept incoming Find Nearby (UWB) requests
                // (and keep the session alive) without the user having to
                // bring the app to foreground first. See UwbSessionManager.
                UwbSessionManager.init(this@LocationTrackingService, userDao)

                withContext(Dispatchers.Main) {
                    registerSensors()
                    // If we don't have any recent location (e.g. fresh start or recovery
                    // from a crash), force isMoving = true so setupLocationUpdates()
                    // immediately starts requesting GPS instead of waiting for the
                    // significant-motion sensor to trigger.
                    if (lastKnownLocation == null) {
                        isMoving = true
                        lastMovementTime = System.currentTimeMillis()
                    }
                    setupLocationUpdates()
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
                    syncHeartbeat()
                    delay(30.seconds)
                }
            }

            // Separate, faster poll for the UWB session-setup channel. The
            // location heartbeat runs every 30s for battery reasons, but UWB
            // REQUEST envelopes need to be picked up quickly so the responder
            // device auto-accepts within a few seconds. This loop ONLY drains
            // /api/uwb/receive — no location publish/receive, no geocoding,
            // no waypoint logic.
            uwbPollJob?.cancel()
            uwbPollJob = launch {
                while (isActive) {
                    drainUwbInbox()
                    delay(3.seconds)
                }
            }
        }
    }

    private fun registerSensors() {
        bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

        if (significantMotionSensor != null) {
            requestSignificantMotion()
        } else {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun requestSignificantMotion() {
        significantMotionSensor?.let {
            sensorManager.requestTriggerSensor(triggerEventListener, it)
        }
    }

    private fun setupLocationUpdates() {
        if (!isMoving) return
        val isLowPower = powerManager.isPowerSaveMode
        val networkInterval = if (isLowPower) 30_000L else 10_000L

        try {
            locationManager.removeUpdates(networkListener)
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                networkInterval,
                0f,
                networkListener
            )
        } catch (_: SecurityException) {
        }
    }

    private fun stopTrackingUpdates() {
        locationManager.removeUpdates(networkListener)
        stopGps()
    }

    companion object {
        private const val CHANNEL_ID = "location_tracking_channel"
        private const val BATTERY_CHANNEL_ID = "battery_channel"
        private const val ENTRY_EXIT_CHANNEL_ID = "entry_exit_channel"
        private const val UWB_REQUEST_CHANNEL_ID = "uwb_request_channel"
        private const val NOTIFICATION_ID = 101
    }

    private fun createNotification(): Notification {
        // 1. Create the Channel (Required for API 26+)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_location_tracking_name),
            NotificationManager.IMPORTANCE_LOW // Low importance so it doesn't "pop up" or make noise
        ).apply {
            description = getString(R.string.notification_channel_location_tracking_desc)
        }

        // 2. Battery Alerts Channel (High Importance for visibility)
        val batteryChannel = NotificationChannel(
            BATTERY_CHANNEL_ID,
            getString(R.string.notification_channel_battery_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_battery_desc)
        }

        // 3. Entry/Exit Channel
        val arrivalChannel = NotificationChannel(
            ENTRY_EXIT_CHANNEL_ID,
            getString(R.string.notification_channel_entry_exit_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_entry_exit_desc)
        }

        // 4. UWB Find Nearby (UWB) Request Channel
        val uwbChannel = NotificationChannel(
            UWB_REQUEST_CHANNEL_ID,
            getString(R.string.notification_channel_uwb_request_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_uwb_request_desc)
        }

        // Register all channels

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(listOf(channel, batteryChannel, arrivalChannel, uwbChannel))

        // 2. Create an Intent to open the app when the notification is clicked
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE // Required for API 31+
        )

        // 3. Build the notification
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText(getString(R.string.notification_tracking_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure this exists in your res/drawable
            .setOngoing(true) // Makes it persistent
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) // API 31+ specific
            .build()
    }

    private fun createNotificationWithCategory(title: String, message: String, category: String, userId: Long) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channelId = when (category) {
            "BATTERY_LOW" -> BATTERY_CHANNEL_ID
            "ENTRY_EXIT" -> ENTRY_EXIT_CHANNEL_ID
            else -> CHANNEL_ID
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Consider using specific icons for battery/location
            .setAutoCancel(true)
            .build()

        // Stable per-(user, category) ID so repeat notifications replace rather than stack.
        val notificationId = "$userId::$category".hashCode()
        manager.notify(notificationId, notification)
    }

    /**
     * Notification fired when an incoming UWB Find Nearby (UWB) request arrives
     * via the heartbeat. Tapping it opens MainActivity with a deep link to the
     * ranging screen for the requesting user.
     */
    private fun createUwbRequestNotification(senderName: String, senderId: Long) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_UWB_PEER_ID, senderId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, senderId.hashCode(), openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, UWB_REQUEST_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_uwb_request_title))
            .setContentText(getString(R.string.notification_uwb_request_text, senderName))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        manager.notify("$senderId::UWB_REQUEST".hashCode(), n)
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
        serviceScope.cancel()
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
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}

suspend fun Context.fetchAddress(lat: Double, lng: Double): Address? =
    suspendCancellableCoroutine { continuation ->
        val geocoder = Geocoder(this, Locale.getDefault())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Modern Async API (Android 13+)
            geocoder.getFromLocation(lat, lng, 1) { addresses ->
                val result = addresses.firstOrNull()

                // Use the stable 3-parameter lambda
                continuation.resume(result) { _, _, _ ->
                    /* No specific cleanup needed for Address objects */
                }
            }
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

        // Safety: If the calling scope is canceled, stop the continuation
        continuation.invokeOnCancellation {
            // Geocoder doesn't support manual cancellation,
            // but this prevents memory leaks in the listener.
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

/**
 * Single source of truth for whether the [LocationTrackingService] should be
 * running and for (re)starting / stopping it accordingly.
 *
 * The service must only run when BOTH conditions hold:
 *  - fine (precise) location permission is granted (the app does not work with
 *    approximate-only location), and
 *  - location sharing is enabled for at least one connected person (the
 *    per-person "share your location with this person" toggle).
 */
object LocationServiceController {

    fun hasFineLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * True iff the user is sharing their location with at least one *other*
     * person (the self user is excluded). Reads directly from the DB so the
     * answer is correct regardless of whether the UI/ViewModel is alive.
     */
    suspend fun isSharingEnabled(context: Context): Boolean {
        val selfId = DataStoreUtils.getInstance(context).getLong("userid")
        val db = context.buildDatabase<FFDatabase>()
        return db.userDao().getAll().any { it.sendingEnabled && it.id != selfId }
    }

    /**
     * Start the service if eligible, otherwise make sure it is stopped. Safe to
     * call from any context (worker, boot, ViewModel, permission refresh).
     */
    suspend fun syncServiceState(context: Context) {
        val appContext = context.applicationContext
        val eligible = hasFineLocationPermission(appContext) && isSharingEnabled(appContext)
        val intent = Intent(appContext, LocationTrackingService::class.java)
        withContext(Dispatchers.Main) {
            if (eligible) {
                try {
                    appContext.startForegroundService(intent)
                } catch (_: Exception) {
                }
            } else {
                appContext.stopService(intent)
            }
        }
    }

    /** Unconditionally stop the service. */
    fun stop(context: Context) {
        context.applicationContext.stopService(
            Intent(context.applicationContext, LocationTrackingService::class.java)
        )
    }
}

fun ensureSync(context: Context) {
    startRepeatedTask<ServiceRestartWorker>(
        context, "Location Sync", 15.minutes,
        ExistingWorkPolicy.REPLACE, ExistingPeriodicWorkPolicy.REPLACE
    )
}