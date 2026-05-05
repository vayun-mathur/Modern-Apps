package com.vayunmathur.findfamily.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.getLatestMap
import com.vayunmathur.findfamily.data.havershine
import com.vayunmathur.findfamily.MainActivity
import com.vayunmathur.findfamily.Migration_1_2
import com.vayunmathur.findfamily.Migration_2_3
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.startRepeatedTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

enum class LocationMode(val key: String, val updateIntervalMs: Long, val minDistanceM: Float, val accuracyThresholdM: Float, val usesGps: Boolean) {
    BATTERY_SAVER("battery_saver", 900_000L, 500f, 500f, false),
    BALANCED("balanced", 300_000L, 100f, 100f, true),
    HIGH_ACCURACY("high_accuracy", 30_000L, 10f, 50f, true);

    companion object {
        fun fromKey(key: String): LocationMode = entries.find { it.key == key } ?: BALANCED
    }
}

class LocationTrackingService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var viewModel: DatabaseViewModel
    private lateinit var users: StateFlow<List<User>>
    private lateinit var waypoints: StateFlow<List<Waypoint>>
    private lateinit var temporaryLinks: StateFlow<List<TemporaryLink>>
    private lateinit var bm: BatteryManager
    private lateinit var dataStore: DataStoreUtils

    private var isGpsRunning = false
    private var locationMode = LocationMode.BALANCED
    private var gpsFallbackEnabled = true
    private var smartTrackingEnabled = true

    private var lastLocation: Location? = null
    private var lastUpdateTime = 0L
    private var stationaryCount = 0
    private var trackingJob: Job? = null

    private val networkListener = LocationListener { location ->
        handleLocationUpdate(location, isFromGps = false)
    }

    private val gpsListener = LocationListener { location ->
        handleLocationUpdate(location, isFromGps = true)
    }

    private fun handleLocationUpdate(location: Location, isFromGps: Boolean) {
        if (smartTrackingEnabled && isStationary(location)) {
            stationaryCount++
            if (stationaryCount > 3) return
        } else {
            stationaryCount = 0
        }

        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < locationMode.updateIntervalMs / 10 && !isFromGps) return

        if (!isFromGps && locationMode.usesGps && gpsFallbackEnabled) {
            if (location.accuracy > locationMode.accuracyThresholdM) {
                if (!isGpsRunning) startGps()
            } else {
                if (isGpsRunning) stopGps()
                processLocation(location)
            }
        } else {
            processLocation(location)
        }
    }

    private fun isStationary(location: Location): Boolean {
        val last = lastLocation ?: return false
        val distance = FloatArray(1)
        android.location.Location.distanceBetween(
            last.latitude, last.longitude,
            location.latitude, location.longitude,
            distance
        )
        return distance[0] < 10f
    }

    private fun processLocation(location: Location) {
        if(Networking.userid == 0L) return
        val users = users.value
        val waypoints = waypoints.value
        val temporaryLinks = temporaryLinks.value
        val userIDs = users.map { it.id }
        val now = Clock.System.now()

        lastLocation = location
        lastUpdateTime = System.currentTimeMillis()

        val locationValue = LocationValue(
            Networking.userid,
            Coord(location.latitude, location.longitude),
            location.speed,
            location.accuracy,
            Clock.System.now(),
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
        )
        CoroutineScope(Dispatchers.IO).launch {
            viewModel.upsertAsync(locationValue)
            Networking.ensureUserExists()
            users.forEach { user ->
                Networking.publishLocation(locationValue, user)
            }
            temporaryLinks.filter { now < it.deleteAt }.forEach { link ->
                Networking.publishLocation(locationValue, link)
            }
            temporaryLinks.filter { now >= it.deleteAt }.forEach { link ->
                viewModel.delete(link)
            }
            delay(3000)
            Networking.receiveLocations()?.let { locations ->
                val usersRecieved = locations.filter { it.userid != Networking.userid }.map { it.userid }.distinct()
                val newUsers = usersRecieved.filter { it !in userIDs }
                viewModel.upsertAll(newUsers.map {
                    User(
                        " ",
                        null,
                        "Unknown Location",
                        false,
                        RequestStatus.AWAITING_REQUEST,
                        Clock.System.now(),
                        null,
                        it
                    )
                })

                val locationValues = viewModel.getLatestMap().first()
                users.forEach { user ->
                    val lastLocation = locations.filter { it.userid == user.id }.maxByOrNull { it.timestamp } ?: return@forEach
                    val lastSavedLocation = locationValues[user.id]

                    if(lastLocation.battery <= 15f && (lastSavedLocation?.battery?:100f) > 15f) {
                        if(user.id != Networking.userid)
                            createNotificationWithCategory(
                                user.name,
                                getString(R.string.notification_low_battery, user.name),
                                "BATTERY_LOW"
                            )
                    }

                    val inWaypoint = waypoints.find { havershine(it.coord, lastLocation.coord) < it.range }

                    val newLocationName = inWaypoint?.name ?: fetchAddress(lastLocation.coord.lat, lastLocation.coord.lon)?.let {
                        it.featureName ?: it.thoroughfare
                    } ?: "Unknown Location"

                    if(newLocationName != user.locationName) {
                        viewModel.update<User>(user.id) {
                            it.copy(
                                locationName = newLocationName,
                                lastLocationChangeTime = lastLocation.timestamp
                            )
                        }
                        if(user.id != Networking.userid) {
                            if(inWaypoint != null) {
                                createNotificationWithCategory(
                                    user.name,
                                    getString(R.string.notification_entered_waypoint, user.name, inWaypoint.name),
                                    "ENTRY_EXIT"
                                )
                            } else if(waypoints.find { it.name == user.locationName } != null) {
                                createNotificationWithCategory(
                                    user.name,
                                    getString(R.string.notification_exited_waypoint, user.name, user.locationName),
                                    "ENTRY_EXIT"
                                )
                            }
                        }
                    }
                }
                viewModel.upsertAll(locations)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadSettings()

        val notification = createNotification()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        startTracking()
        return START_STICKY
    }

    private fun loadSettings() {
        dataStore = DataStoreUtils.getInstance(this)
        locationMode = LocationMode.fromKey(dataStore.getString("location_mode") ?: "balanced")
        gpsFallbackEnabled = dataStore.getBoolean("gps_fallback_enabled", true)
        smartTrackingEnabled = dataStore.getBoolean("smart_tracking_enabled", true)
    }

    private fun startTracking() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = buildDatabase<FFDatabase>(listOf(Migration_1_2, Migration_2_3))
            viewModel = DatabaseViewModel(db,
                User::class to db.userDao(),
                Waypoint::class to db.waypointDao(),
                LocationValue::class to db.locationValueDao(),
                TemporaryLink::class to db.temporaryLinkDao()
            )
            users = viewModel.data<User>()
            waypoints = viewModel.data<Waypoint>()
            temporaryLinks = viewModel.data<TemporaryLink>()
            Networking.init(viewModel, DataStoreUtils.getInstance(this@LocationTrackingService))

            withContext(Dispatchers.Main) {
                setupLocationUpdates()
            }
        }
    }

    private fun setupLocationUpdates() {
        bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        stopAllUpdates()

        when (locationMode) {
            LocationMode.BATTERY_SAVER -> setupBatterySaverTracking()
            LocationMode.BALANCED -> setupBalancedTracking()
            LocationMode.HIGH_ACCURACY -> setupHighAccuracyTracking()
        }
    }

    private fun setupBatterySaverTracking() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                locationMode.updateIntervalMs,
                locationMode.minDistanceM,
                networkListener
            )
        } catch (_: SecurityException) {
        }
    }

    private fun setupBalancedTracking() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                locationMode.updateIntervalMs,
                locationMode.minDistanceM,
                networkListener
            )
        } catch (_: SecurityException) {
        }
    }

    private fun setupHighAccuracyTracking() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                locationMode.updateIntervalMs,
                locationMode.minDistanceM,
                networkListener
            )
        } catch (_: SecurityException) {
        }

        if (gpsFallbackEnabled) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        locationMode.updateIntervalMs / 2,
                        locationMode.minDistanceM / 2,
                        gpsListener
                    )
                    isGpsRunning = true
                }
            } catch (_: SecurityException) {
            }
        }
    }

    private fun stopAllUpdates() {
        locationManager.removeUpdates(networkListener)
        locationManager.removeUpdates(gpsListener)
        isGpsRunning = false
        trackingJob?.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "location_tracking_channel"
        private const val BATTERY_CHANNEL_ID = "battery_channel"
        private const val ENTRY_EXIT_CHANNEL_ID = "entry_exit_channel"
        private const val NOTIFICATION_ID = 101
    }

    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_location_tracking_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_location_tracking_desc)
        }

        val batteryChannel = NotificationChannel(
            BATTERY_CHANNEL_ID,
            getString(R.string.notification_channel_battery_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_battery_desc)
        }

        val arrivalChannel = NotificationChannel(
            ENTRY_EXIT_CHANNEL_ID,
            getString(R.string.notification_channel_entry_exit_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_entry_exit_desc)
        }

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(listOf(channel, batteryChannel, arrivalChannel))

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = when (locationMode) {
            LocationMode.BATTERY_SAVER -> getString(R.string.location_mode_battery_saver)
            LocationMode.BALANCED -> getString(R.string.location_mode_balanced)
            LocationMode.HIGH_ACCURACY -> getString(R.string.location_mode_high_accuracy)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText("$modeLabel • ${getString(R.string.notification_tracking_text)}")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationWithCategory(title: String, message: String, category: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channelId = when (category) {
            "BATTERY_LOW" -> BATTERY_CHANNEL_ID
            "ENTRY_EXIT" -> ENTRY_EXIT_CHANNEL_ID
            else -> CHANNEL_ID
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()

        val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        manager.notify(notificationId, notification)
    }

    private fun startGps() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    10_000L,
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
        stopAllUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}

suspend fun Context.fetchAddress(lat: Double, lng: Double): Address? =
    suspendCancellableCoroutine { continuation ->
        val geocoder = Geocoder(this, Locale.getDefault())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(lat, lng, 1) { addresses ->
                val result = addresses.firstOrNull()
                continuation.resume(result) { _, _, _ -> }
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val address = geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
                continuation.resume(address)
            } catch (_: Exception) {
                continuation.resume(null)
            }
        }

        continuation.invokeOnCancellation { }
    }

class ServiceRestartWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        try {
            applicationContext.startForegroundService(Intent(applicationContext, LocationTrackingService::class.java))
            return Result.success()
        } catch (_: Exception) {
            return Result.retry()
        }
    }
}

fun ensureSync(context: Context) {
    startRepeatedTask<ServiceRestartWorker>(context, "Location Sync", 15.minutes)
}
