package com.vayunmathur.taxi.notifications

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationManagerCompat
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.data.RideStatusResult
import com.vayunmathur.taxi.network.lyft.LyftProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the [RideLiveUpdate] notification current while a ride is
 * in progress, even with the app backgrounded. It polls the same `activeRide()` endpoint
 * the tracking screen uses (falling back to `rideById`), updating every [POLL_MS] until the
 * ride is terminal or gone, then stops itself.
 *
 * Foreground type is `specialUse`: we poll an API on a ride-length timescale, so neither
 * `location` (we read no device location) nor the time-limited `shortService` / `dataSync`
 * types fit.
 */
class RideTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var trackedRideId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rideId = intent?.getStringExtra(EXTRA_RIDE_ID)
        if (rideId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Satisfy the startForeground deadline immediately with a placeholder; the first
        // poll replaces it with real data a moment later.
        try {
            startForegroundCompat(RideLiveUpdate.build(this, placeholder(rideId)))
        } catch (e: SecurityException) {
            Log.error(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            Log.error(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Idempotent: a repeat start for the same ride just refreshes, no new loop.
        if (rideId == trackedRideId && pollJob?.isActive == true) {
            return START_REDELIVER_INTENT
        }
        trackedRideId = rideId
        pollJob?.cancel()
        pollJob = scope.launch { pollLoop(rideId) }
        return START_REDELIVER_INTENT
    }

    private suspend fun pollLoop(rideId: String) {
        val provider = LyftProvider(applicationContext)
        while (true) {
            var ended = false
            val ride: ActiveRide? = when (val result = provider.activeRide()) {
                is RideStatusResult.Active -> result.ride
                RideStatusResult.None -> {
                    // The active-ride endpoint may not surface a just-booked ride; read it
                    // directly by id before giving up.
                    val byId = provider.rideById(rideId) as? RideStatusResult.Active
                    if (byId == null) ended = true
                    byId?.ride
                }
                is RideStatusResult.Failed -> {
                    Log.status(TAG, "activeRide failed: ${result.message}")
                    null // transient; keep the last notification and retry
                }
                RideStatusResult.Unsupported -> {
                    ended = true
                    null
                }
            }

            if (ended) {
                Log.debug(TAG, "ride $rideId gone; stopping")
                finish()
                return
            }

            if (ride != null) {
                Log.dev(TAG, "ride $rideId status=${ride.status} eta=${ride.pickupEtaSeconds}")
                notify(RideLiveUpdate.build(this, ride))
                if (ride.status.isTerminal) {
                    Log.debug(TAG, "ride $rideId terminal; stopping")
                    // Leave the terminal update visible briefly, then clear it.
                    delay(TERMINAL_LINGER_MS)
                    finish()
                    return
                }
            }
            delay(POLL_MS)
        }
    }

    private fun notify(notification: Notification) {
        // notify() needs POST_NOTIFICATIONS on API 33+; without it the foreground
        // placeholder simply stays put rather than crashing.
        try {
            NotificationManagerCompat.from(this).notify(RideLiveUpdate.NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.status(TAG, "notify skipped (no permission)", e)
        }
    }

    private fun finish() {
        RideLiveUpdate.cancel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                RideLiveUpdate.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(RideLiveUpdate.NOTIFICATION_ID, notification)
        }
    }

    private fun placeholder(rideId: String) = ActiveRide(
        rideId = rideId,
        status = RideStatus.UNKNOWN,
        statusRaw = null,
        driver = null,
        vehicle = null,
        driverLocation = null,
        stops = emptyList(),
        raw = "",
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Shared constants and helpers for RideTrackingService. */
    companion object {
        private const val TAG = "RideTrackingService"

        /** Mirrors RideTrackingScreen's active-ride poll cadence. */
        private const val POLL_MS = 5_000L
        private const val TERMINAL_LINGER_MS = 5_000L
        private const val EXTRA_RIDE_ID = "ride_id"

        fun start(context: Context, rideId: String?) {
            if (rideId.isNullOrBlank()) return
            val intent = Intent(context, RideTrackingService::class.java)
                .putExtra(EXTRA_RIDE_ID, rideId)
            // Called after async booking work: the app may no longer be
            // foreground-eligible, and startForegroundService then throws
            // ForegroundServiceStartNotAllowedException (API 31+).
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                Log.error(TAG, "cannot start ride tracking from background", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RideTrackingService::class.java))
        }
    }
}
