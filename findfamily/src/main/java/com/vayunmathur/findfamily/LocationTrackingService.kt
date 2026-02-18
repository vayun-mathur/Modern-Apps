package com.vayunmathur.findfamily

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
import com.vayunmathur.findfamily.data.havershine
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.startRepeatedTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Clock
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
                viewModel.upsertAll(locations)
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

                // update the user.locationName
                users.forEach { user ->
                    val lastLocation = locations.filter { it.userid == user.id }.maxByOrNull { it.timestamp } ?: return@forEach
                    val inWaypoint = waypoints.find { havershine(it.coord, lastLocation.coord) < it.range }
                    if(inWaypoint != null) {
                        if(inWaypoint.name != user.locationName)
                            viewModel.upsert(user.copy(locationName = inWaypoint.name, lastLocationChangeTime = lastLocation.timestamp))
                    } else {
                        val address =
                            fetchAddress(lastLocation.coord.lat, lastLocation.coord.lon)?.let {
                                it.featureName ?: it.thoroughfare
                            } ?: "Unknown Location"
                        if(user.locationName != address)
                            viewModel.upsert(user.copy(locationName = address, lastLocationChangeTime = lastLocation.timestamp))
                    }
                }
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
        val db = buildDatabase<FFDatabase>()
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
                30_000L, // 30 seconds
                0f,   // regardless of movement
                locationListener
            )
        } catch (unlikely: SecurityException) {
            // Handle missing permissions
        }
    }

    companion object {
        private const val CHANNEL_ID = "location_tracking_channel"
        private const val NOTIFICATION_ID = 101
    }

    private fun createNotification(): Notification {
        // 1. Create the Channel (Required for API 26+)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location Tracking",
            NotificationManager.IMPORTANCE_LOW // Low importance so it doesn't "pop up" or make noise
        ).apply {
            description = "Used for FindFamily background location updates"
        }

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        // 2. Create an Intent to open the app when the notification is clicked
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE // Required for API 31+
        )

        // 3. Build the notification
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FindFamily is active")
            .setContentText("Sharing location with your selected contacts")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure this exists in your res/drawable
            .setOngoing(true) // Makes it persistent
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) // API 31+ specific
            .build()
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
            } catch (e: Exception) {
                continuation.resume(null)
            }
        }

        // Safety: If the calling scope is cancelled, stop the continuation
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