package com.vayunmathur.maps.util

import com.vayunmathur.library.util.localizedAmPmMarker
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.library.util.LiveUpdateSupport
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
import com.vayunmathur.library.util.ensureNotificationChannel

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
        Log.status(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.status(TAG, "ACTION_STOP")
            stopSelfAndSession()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(NavigationSessionManager.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
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
        Log.status(TAG, "onDestroy")
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
        Log.status(TAG, "onTaskRemoved — stopping navigation session")
        stopSelfAndSession()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ----------------------------------------------------------------
    // State -> notification / TTS dispatch
    // ----------------------------------------------------------------

    private fun handleState(state: NavigationSessionManager.NavState) {
        when (state) {
            is NavigationSessionManager.NavState.Navigating -> handleNavigating(state)
            // Tear down after a short delay so the UI / notification can
            // settle on the Arrived state visibly.
            NavigationSessionManager.NavState.Arrived -> handleTerminal(state, ARRIVAL_TEARDOWN_MS)
            is NavigationSessionManager.NavState.Failed -> handleTerminal(state, FAILURE_TEARDOWN_MS)
            else -> {
                updateNotificationNow(state)
            }
        }
    }

    private fun handleNavigating(state: NavigationSessionManager.NavState.Navigating) {
        val route = NavigationSessionManager.session.value.route
        if (route != null && voiceGuidanceEnabled()) {
            NavigationTts.onProgressUpdate(state.progress, route.step)
        }
        maybeUpdateNotification(state)
    }

    /** Settle on a terminal state visibly, then tear the service down. */
    private fun handleTerminal(state: NavigationSessionManager.NavState, delayMs: Long) {
        updateNotificationNow(state)
        // Auto-cleanup Failed too — the overlay shows the reason for
        // a few seconds before the service tears itself down. Without
        // this the user could be left with a dismissable notification
        // that they tap (or swipe away) and the singleton stays
        // "Failed" with no way to clear it short of force-stopping
        // the app.
        serviceScope.launch {
            kotlinx.coroutines.delay(delayMs)
            stopSelfAndSession()
        }
    }

    /**
     * Whether spoken turn-by-turn cues are enabled (P6 settings). Read live from
     * DataStore so toggling voice guidance in settings takes effect immediately;
     * defaults to on when unset.
     */
    private fun voiceGuidanceEnabled(): Boolean =
        com.vayunmathur.library.util.DataStoreUtils.getInstance(this)
            .getBoolean(
                com.vayunmathur.maps.data.MapPreferences.KEY_VOICE_GUIDANCE,
                com.vayunmathur.maps.data.MapPreferences.DEFAULT_VOICE_GUIDANCE,
            )

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
        ensureNotificationChannel(
            id = CHANNEL_ID,
            name = getString(R.string.nav_channel_name),
            importance = NotificationManager.IMPORTANCE_LOW,
            description = getString(R.string.nav_channel_desc),
        ) {
            setSound(null, null)
            enableVibration(false)
        }
    }

    private fun buildNotification(state: NavigationSessionManager.NavState): Notification {
        ensureChannel()
        val tapIntent = PendingIntent.getActivity(
            this, TAP_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, STOP_REQUEST_CODE,
            Intent(this, NavigationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mode = NavigationSessionManager.session.value.travelMode
        val destLabel = NavigationSessionManager.session.value.destinationName
            ?: getString(R.string.nav_default_destination)

        val info = describe(state, mode, destLabel)
        val isOngoing = state !is NavigationSessionManager.NavState.Arrived &&
            state !is NavigationSessionManager.NavState.Failed

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(info.title)
            .setContentText(info.content)
            .setSmallIcon(info.smallIcon)
            .setOngoing(isOngoing)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Promotion requires a colorized notification with a content title.
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .addAction(NO_ICON, getString(R.string.nav_action_end), stopIntent)

        builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)

        if (LiveUpdateSupport.isStyleSupported()) {
            // Live Update (API 36): determinate ProgressStyle with the route
            // fraction and a status chip showing the next-maneuver distance.
            // Compat progress is in whole-percentage units (one segment of 100).
            // Terminal states use a plain style with no tracker; Failed is
            // never promoted.
            val navigating = state as? NavigationSessionManager.NavState.Navigating
            val percent = navigating?.progress?.let {
                (it.fractionComplete * PERCENT_SCALE).roundToInt()
                    .coerceIn(0, FULL_PROGRESS_PERCENT)
            } ?: if (state is NavigationSessionManager.NavState.Arrived) {
                FULL_PROGRESS_PERCENT
            } else {
                0
            }
            builder.setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(FULL_PROGRESS_PERCENT)))
                    .setProgress(percent),
            )
            info.chipText?.let { builder.setShortCriticalText(it) }
            if (isOngoing && state !is NavigationSessionManager.NavState.Failed &&
                LiveUpdateSupport.canPostPromoted(this)
            ) {
                // 36.1+ only behind canPostPromoted — unguarded this crashes on 36.0.
                builder.setRequestPromotedOngoing(true)
            }
        } else {
            info.progressPercent?.let { p -> builder.setProgress(FULL_PROGRESS_PERCENT, p, false) }
        }
        return builder.build()
    }

    private data class NotificationInfo(
        val title: String,
        val content: String,
        val progressPercent: Int?,
        /** Live Update status chip (API 36): next-maneuver distance, or null for no chip. */
        val chipText: String?,
        val smallIcon: Int,
    )

    private fun describe(
        state: NavigationSessionManager.NavState,
        mode: TravelMode?,
        destLabel: String,
    ): NotificationInfo {
        val defaultIcon = R.drawable.location_on_24px
        return when (state) {
            NavigationSessionManager.NavState.Idle -> idleInfo(defaultIcon)
            NavigationSessionManager.NavState.Starting -> statusInfo(
                getString(R.string.nav_status_starting),
                getString(R.string.nav_destination_format, destLabel),
                null, defaultIcon,
            )
            NavigationSessionManager.NavState.Recalculating -> statusInfo(
                getString(R.string.nav_status_recalculating),
                getString(R.string.nav_destination_format, destLabel),
                null, defaultIcon,
            )
            NavigationSessionManager.NavState.Arrived -> statusInfo(
                getString(R.string.nav_arrived_title),
                destLabel,
                FULL_PROGRESS_PERCENT, defaultIcon,
            )
            is NavigationSessionManager.NavState.Failed -> statusInfo(
                getString(R.string.nav_status_failed),
                state.reason,
                null, defaultIcon,
            )
            is NavigationSessionManager.NavState.Navigating ->
                navigatingInfo(state, mode, destLabel, defaultIcon)
        }
    }

    private fun idleInfo(defaultIcon: Int) = NotificationInfo(
        getString(R.string.nav_status_idle), "", null, null, defaultIcon,
    )

    private fun statusInfo(
        title: String,
        content: String,
        progressPercent: Int?,
        smallIcon: Int,
        chipText: String? = null,
    ) = NotificationInfo(title, content, progressPercent, chipText, smallIcon)

    private fun navigatingInfo(
        state: NavigationSessionManager.NavState.Navigating,
        mode: TravelMode?,
        destLabel: String,
        defaultIcon: Int,
    ): NotificationInfo {
        val p = state.progress
        val percent = (p.fractionComplete * PERCENT_SCALE).roundToInt().coerceIn(0, FULL_PROGRESS_PERCENT)
        // Status chip: the glanceable next-maneuver distance (e.g. "2 km").
        val chip = formatDistance(p.distanceToNextManeuver)
        val distRemaining = formatDistance(p.distanceRemaining)
        val etaText = formatEta(p.etaEpochMs)
        val route = NavigationSessionManager.session.value.route
        val currentStep = route?.step?.getOrNull(p.currentStepIndex)
        return when (mode) {
            TravelMode.DRIVE -> driveInfo(currentStep, destLabel, p, etaText, percent, chip, defaultIcon)
            TravelMode.WALK -> progressInfo(
                getString(R.string.nav_walk_title_format, destLabel),
                percent, distRemaining, etaText, chip, defaultIcon,
            )
            TravelMode.BICYCLE -> progressInfo(
                getString(R.string.nav_bike_title_format, destLabel),
                percent, distRemaining, etaText, chip, defaultIcon,
            )
            TravelMode.TRANSIT -> transitInfo(
                currentStep, destLabel, percent, distRemaining, etaText, chip, defaultIcon,
            )
            null -> progressInfo(
                getString(R.string.nav_destination_format, destLabel),
                percent, distRemaining, etaText, chip, defaultIcon,
            )
        }
    }

    private fun driveInfo(
        currentStep: RouteService.Step?,
        destLabel: String,
        p: NavigationProgress,
        etaText: String,
        percent: Int,
        chipText: String?,
        defaultIcon: Int,
    ): NotificationInfo {
        val title = currentStep?.navInstruction?.instructions?.takeIf { it.isNotBlank() }
            ?: getString(R.string.nav_destination_format, destLabel)
        val content = getString(
            R.string.nav_drive_content_format,
            formatDistance(p.distanceToNextManeuver),
            etaText,
        )
        return NotificationInfo(title, content, percent, chipText, defaultIcon)
    }

    private fun progressInfo(
        title: String,
        percent: Int,
        distRemaining: String,
        etaText: String,
        chipText: String?,
        defaultIcon: Int,
    ): NotificationInfo = NotificationInfo(
        title,
        getString(R.string.nav_progress_content_format, percent, distRemaining, etaText),
        percent, chipText, defaultIcon,
    )

    private fun transitInfo(
        currentStep: RouteService.Step?,
        destLabel: String,
        percent: Int,
        distRemaining: String,
        etaText: String,
        chipText: String?,
        defaultIcon: Int,
    ): NotificationInfo {
        val transitTitle = currentStep?.transitDetails?.transitLine?.name
            ?.takeIf { it.isNotBlank() }
            ?: currentStep?.navInstruction?.instructions
            ?: getString(R.string.nav_transit_title_default, destLabel)
        return NotificationInfo(
            transitTitle,
            getString(R.string.nav_progress_content_format, percent, distRemaining, etaText),
            percent, chipText, defaultIcon,
        )
    }

    private fun stopSelfAndSession() {
        NavigationSessionManager.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "NavigationService"
        private const val CHANNEL_ID = "navigation_channel"
        private const val NOTIFICATION_ID = 4242
        private const val NOTIFICATION_THROTTLE_MS = 2_000L
        const val ACTION_STOP = "com.vayunmathur.maps.navigation.STOP"
        /** Scale for the fraction-complete progress percent. */
        private const val PERCENT_SCALE = 100
        private const val FULL_PROGRESS_PERCENT = 100
        /** Settle delay (ms) before tearing down after arrival / failure. */
        private const val ARRIVAL_TEARDOWN_MS = 8_000L
        private const val FAILURE_TEARDOWN_MS = 8_000L
        /** PendingIntent request codes for the tap / stop actions. */
        private const val TAP_REQUEST_CODE = 0
        private const val STOP_REQUEST_CODE = 1
        /** No icon for the notification action. */
        private const val NO_ICON = 0
    }
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
        localizedAmPmMarker()
    }
    return local.format(fmt)
}
