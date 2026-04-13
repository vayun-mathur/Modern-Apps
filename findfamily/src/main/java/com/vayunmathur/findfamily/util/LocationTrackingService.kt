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
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.startRepeatedTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class LocationTrackingService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var viewModel: DatabaseViewModel
    private lateinit var users: StateFlow<List<User>>
    private lateinit var waypoints: StateFlow<List<Waypoint>>
    private lateinit var temporaryLinks: StateFlow<List<TemporaryLink>>
    private lateinit var bm: BatteryManager

    private val locationListener = LocationListener { location ->
        if(Networking.userid == 0L) return@LocationListener
        val users = users.value
        val waypoints = waypoints.value
        val temporaryLinks = temporaryLinks.value
        val userIDs = users.map { it.id }
        val now = Clock.System.now()
        val locationValue = LocationValue(
            Networking.userid,
            Coord(location.latitude, location.longitude),
            0f,
            100f,
            Clock.System.now(),
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
        )
        println(locationValue)
        CoroutineScope(Dispatchers.IO).launch {
            Networking.ensureUserExists()
            println(users)
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
                val usersRecieved = locations.map { it.userid }.distinct()
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
                // update the user.locationName
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
        val notification = createNotification()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        val db = buildDatabase<FFDatabase>(listOf(Migration_1_2))
        viewModel = DatabaseViewModel(db,
            User::class to db.userDao(),
            Waypoint::class to db.waypointDao(),
            LocationValue::class to db.locationValueDao(),
            TemporaryLink::class to db.temporaryLinkDao()
        )
        users = viewModel.data<User>()
        waypoints = viewModel.data<Waypoint>()
        temporaryLinks = viewModel.data<TemporaryLink>()
        CoroutineScope(Dispatchers.IO).launch {
            Networking.init(viewModel, DataStoreUtils.getInstance(this@LocationTrackingService))
        }

        bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        try {
            // Request GPS updates
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                10_000L, // 30 seconds
                0f,   // regardless of movement
                locationListener
            )
        } catch (_: SecurityException) {
            // Handle missing permissions
        }
    }

    companion object {
        private const val CHANNEL_ID = "location_tracking_channel"
        private const val BATTERY_CHANNEL_ID = "battery_channel"
        private const val ENTRY_EXIT_CHANNEL_ID = "entry_exit_channel"
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

        // Register all channels

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(listOf(channel, batteryChannel, arrivalChannel))

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
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Consider using specific icons for battery/location
            .setAutoCancel(true)
            .build()

        // Generate a unique ID so notifications don't overwrite each other
        val notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        manager.notify(notificationId, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        // CRITICAL: Stop the hardware sensors
        locationManager.removeUpdates(locationListener)

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