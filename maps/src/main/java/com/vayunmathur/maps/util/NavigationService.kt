package com.vayunmathur.maps.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.maps.MainActivity
import com.vayunmathur.maps.R
import com.vayunmathur.maps.util.RouteService.TravelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

/**
 * Foreground service that owns the lifetime of a navigation session.
 *
 * Modeled on `findfamily/.../LocationTrackingService`: a `Service` (not
 * `LifecycleService`) with a `SupervisorJob`-scoped `serviceScope` collects
 * [NavigationSessionManager.state] and updates a persistent ongoing
 * notification (the Android equivalent of an iOS "live activity"). The
 * service also feeds [NavigationTts] so voice cues fire even when the app
 * is backgrounded.
 *
 * Started via `startForegroundService(Intent(this, NavigationService::class.java))`
 * from the "Start Navigation" button. Stopped by either:
 *   - the user tapping "End" in the in-app overlay
 *   - the user tapping the "End" notification action
 *   - the navigation session reaching [NavigationSessionManager.NavState.Arrived]
 */
class NavigationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectorJob: Job? = null
    private var lastNotificationUpdateMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        NavigationSessionManager.init(this)
        NavigationTts.init(this)
        Log.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "ACTION_STOP")
            stopSelfAndSession()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(NavigationSessionManager.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        )

        // One collector for the lifetime of the service: pushes state into
        // both the notification (throttled) and the TTS engine.
        collectorJob?.cancel()
        collectorJob = serviceScope.launch {
            NavigationSessionManager.state.collect { state ->
                handleState(state)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        collectorJob?.cancel()
        NavigationTts.shutdown()
        // Defensive cleanup for the case where the system kills the service
        // (low memory / task removed) without going through our own
        // stopSelfAndSession() path. Without this the singleton would stay
        // in Navigating with a dead location listener, and the next time
        // the user opens the app the overlay would re-appear with stale data.
        NavigationSessionManager.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away from recents while navigating,
        // tear down rather than leave the singleton (and the persistent
        // notification) orphaned.
        Log.i(TAG, "onTaskRemoved — stopping navigation session")
        stopSelfAndSession()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ----------------------------------------------------------------
    // State -> notification / TTS dispatch
    // ----------------------------------------------------------------

    private fun handleState(state: NavigationSessionManager.NavState) {
        when (state) {
            is NavigationSessionManager.NavState.Navigating -> {
                val route = NavigationSessionManager.currentRoute
                if (route != null) {
                    NavigationTts.onProgressUpdate(state.progress, route.step)
                }
                maybeUpdateNotification(state)
            }
            NavigationSessionManager.NavState.Arrived -> {
                updateNotificationNow(state)
                // Tear down after a short delay so the UI / notification can
                // settle on the Arrived state visibly.
                serviceScope.launch {
                    kotlinx.coroutines.delay(8_000)
                    stopSelfAndSession()
                }
            }
            is NavigationSessionManager.NavState.Failed -> {
                updateNotificationNow(state)
                // Auto-cleanup Failed too — the overlay shows the reason for
                // a few seconds before the service tears itself down. Without
                // this the user could be left with a dismissable notification
                // that they tap (or swipe away) and the singleton stays
                // "Failed" with no way to clear it short of force-stopping
                // the app.
                serviceScope.launch {
                    kotlinx.coroutines.delay(8_000)
                    stopSelfAndSession()
                }
            }
            else -> {
                updateNotificationNow(state)
            }
        }
    }

    private fun maybeUpdateNotification(state: NavigationSessionManager.NavState) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateMs >= NOTIFICATION_THROTTLE_MS) {
            lastNotificationUpdateMs = now
            updateNotificationNow(state)
        }
    }

    private fun updateNotificationNow(state: NavigationSessionManager.NavState) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    // ----------------------------------------------------------------
    // Notification building
    // ----------------------------------------------------------------

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.nav_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.nav_channel_desc)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(state: NavigationSessionManager.NavState): Notification {
        ensureChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mode = NavigationSessionManager.travelMode
        val destLabel = NavigationSessionManager.destinationName ?: getString(R.string.nav_default_destination)

        val (title, content, progressPercent, smallIcon) = describe(state, mode, destLabel)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(smallIcon)
            .setOngoing(state !is NavigationSessionManager.NavState.Arrived &&
                        state !is NavigationSessionManager.NavState.Failed)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.nav_action_end), stopIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        progressPercent?.let { p -> builder.setProgress(100, p, false) }
        return builder.build()
    }

    private data class NotificationInfo(
        val title: String,
        val content: String,
        val progressPercent: Int?,
        val smallIcon: Int,
    )

    private fun describe(
        state: NavigationSessionManager.NavState,
        mode: TravelMode?,
        destLabel: String,
    ): NotificationInfo {
        val defaultIcon = R.drawable.location_on_24px
        return when (state) {
            NavigationSessionManager.NavState.Idle -> NotificationInfo(
                getString(R.string.nav_status_idle), "", null, defaultIcon
            )
            NavigationSessionManager.NavState.Starting -> NotificationInfo(
                getString(R.string.nav_status_starting),
                getString(R.string.nav_destination_format, destLabel),
                null, defaultIcon
            )
            NavigationSessionManager.NavState.Recalculating -> NotificationInfo(
                getString(R.string.nav_status_recalculating),
                getString(R.string.nav_destination_format, destLabel),
                null, defaultIcon
            )
            NavigationSessionManager.NavState.Arrived -> NotificationInfo(
                getString(R.string.nav_status_arrived),
                destLabel,
                100, defaultIcon
            )
            is NavigationSessionManager.NavState.Failed -> NotificationInfo(
                getString(R.string.nav_status_failed),
                state.reason,
                null, defaultIcon
            )
            is NavigationSessionManager.NavState.Navigating -> {
                val p = state.progress
                val percent = (p.fractionComplete * 100).roundToInt().coerceIn(0, 100)
                val distRemaining = formatDistance(p.distanceRemaining)
                val etaText = formatEta(p.etaEpochMs)
                val route = NavigationSessionManager.currentRoute
                val currentStep = route?.step?.getOrNull(p.currentStepIndex)
                val maneuverIcon = currentStep?.navInstruction?.maneuver?.icon() ?: defaultIcon
                when (mode) {
                    TravelMode.DRIVE -> {
                        val title = currentStep?.navInstruction?.instructions?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.nav_destination_format, destLabel)
                        val content = getString(
                            R.string.nav_drive_content_format,
                            formatDistance(p.distanceToNextManeuver),
                            etaText,
                        )
                        NotificationInfo(title, content, percent, maneuverIcon)
                    }
                    TravelMode.WALK -> NotificationInfo(
                        getString(R.string.nav_walk_title_format, destLabel),
                        getString(R.string.nav_progress_content_format, percent, distRemaining, etaText),
                        percent, defaultIcon
                    )
                    TravelMode.BICYCLE -> NotificationInfo(
                        getString(R.string.nav_bike_title_format, destLabel),
                        getString(R.string.nav_progress_content_format, percent, distRemaining, etaText),
                        percent, defaultIcon
                    )
                    TravelMode.TRANSIT -> {
                        val transitTitle = currentStep?.transitDetails?.transitLine?.name
                            ?.takeIf { it.isNotBlank() }
                            ?: currentStep?.navInstruction?.instructions
                            ?: getString(R.string.nav_transit_title_default, destLabel)
                        NotificationInfo(
                            transitTitle,
                            getString(R.string.nav_progress_content_format, percent, distRemaining, etaText),
                            percent, defaultIcon
                        )
                    }
                    null -> NotificationInfo(
                        getString(R.string.nav_destination_format, destLabel),
                        getString(R.string.nav_progress_content_format, percent, distRemaining, etaText),
                        percent, defaultIcon
                    )
                }
            }
        }
    }

    private fun stopSelfAndSession() {
        NavigationSessionManager.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "NavigationService"
        private const val CHANNEL_ID = "navigation_channel"
        private const val NOTIFICATION_ID = 4242
        private const val NOTIFICATION_THROTTLE_MS = 2_000L
        const val ACTION_STOP = "com.vayunmathur.maps.navigation.STOP"
    }
}

/**
 * Format a distance in meters as a short user-facing string.
 * - "<X m" under 1 km
 * - "X.Y km" otherwise (one decimal)
 */
internal fun formatDistance(meters: Double): String {
    if (meters < 1000.0) return "${meters.roundToInt()} m"
    val km = meters / 1000.0
    return "%.1f km".format(km)
}

/** Format epoch-ms as local HH:mm AM/PM. */
internal fun formatEta(etaEpochMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(etaEpochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val fmt = kotlinx.datetime.LocalDateTime.Format {
        amPmHour(Padding.NONE)
        chars(":")
        minute()
        chars(" ")
        amPmMarker("AM", "PM")
    }
    return local.format(fmt)
}
