package com.vayunmathur.findfamily.platform

import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import com.vayunmathur.findfamily.domain.FixSample
import com.vayunmathur.findfamily.domain.ForcedFixPolicy
import com.vayunmathur.findfamily.util.LocationTrackingService
import com.vayunmathur.findfamily.util.recordFix
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/*
 * Sender keep-alive for GitHub #895 ("the locations of loved ones aren't updating").
 *
 * The sending phone used to request no location at all once it decided it was "still",
 * and only the one-shot significant-motion sensor could wake it. In a vehicle that sensor
 * often never fires, so the phone kept re-publishing a fix from hours earlier. Three
 * pieces here make sure a "still" phone keeps producing fresh fixes:
 *
 * 1. An always-on low-power NETWORK request (minutes apart, displacement-filtered) and a
 *    PASSIVE request, so leaving by any means delivers a fix that flips the phone back to
 *    moving through [onLocationFix].
 * 2. A periodic forced fix ([ForcedFixPolicy]) when the held fix gets old.
 * 3. A single fix intake that lets every fix, however coarse, reach the movement detector
 *    before the display accuracy gate in [recordFix] decides whether it is held.
 *
 * All of it runs on the main thread except the forced-fix wait itself.
 */

private const val TAG_KEEP_ALIVE = "FF-KeepAlive"
private const val NANOS_PER_MILLI = 1_000_000L

/** Low-power NETWORK interval on battery. Network fixes are cheap (no GPS radio). */
internal const val LOW_POWER_INTERVAL_MS = 3 * 60_000L

/** Low-power NETWORK interval in power-save mode. */
internal const val LOW_POWER_INTERVAL_POWER_SAVE_MS = 5 * 60_000L

/**
 * Displacement filter on the low-power request: a stationary phone gets no deliveries
 * (the forced fix keeps it fresh), a phone that has left gets one within minutes.
 */
internal const val LOW_POWER_MIN_DISTANCE_METERS = 75f

/** PASSIVE costs nothing (other apps' fixes); the interval only bounds callback rate. */
internal const val PASSIVE_MIN_INTERVAL_MS = 30_000L

/** Supplies one current fix from a provider, or null on timeout or failure. */
internal fun interface CurrentLocationSource {
    suspend fun current(provider: String, timeoutMs: Long): Location?
}

/** [CurrentLocationSource] over LocationManager.getCurrentLocation (API 30+). */
internal class PlatformCurrentLocationSource(
    private val locationManager: LocationManager,
    private val executor: Executor,
) : CurrentLocationSource {
    // Permission revoked or provider missing: no fix, never a crash.
    override suspend fun current(provider: String, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    locationManager.getCurrentLocation(provider, signal, executor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } catch (_: SecurityException) {
                    continuation.resume(null)
                } catch (_: IllegalArgumentException) {
                    continuation.resume(null)
                }
            }
        }
}

/** The platform fix reduced to what [com.vayunmathur.findfamily.domain.MovementDetector] needs. */
internal fun Location.toFixSample(): FixSample = FixSample(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
    elapsedMs = elapsedRealtimeNanos / NANOS_PER_MILLI,
)

/**
 * The one intake for every fix, from every listener and the forced fix.
 *
 * Movement detection sees the fix first, whatever its accuracy: a coarse fix far from
 * where the phone was still proves it left. Only then does [recordFix] apply the
 * display gate (fixes worse than NoShowPolicy's 100 m are never held or published).
 */
internal fun LocationTrackingService.onLocationFix(location: Location) {
    val transition = motion.onFix(location.toFixSample(), SystemClock.elapsedRealtime())
    recordFix(location)
    applyMotionTransition(transition)
}

/**
 * Runs on every heartbeat tick (30 s while the CPU is awake) on the main thread:
 * re-checks stillness, keeps the low-power requests registered, and starts a forced
 * fix when one is due.
 */
internal fun LocationTrackingService.keepAliveTick() {
    applyMotionTransition(motion.onTick(SystemClock.elapsedRealtime()))
    refreshLowPowerUpdates()
    maybeRequestForcedFix()
}

/**
 * Registers (or re-registers, when power-save toggles) the low-power NETWORK request,
 * and the PASSIVE request once. Requests survive provider disable/enable, so a provider
 * that is off now is simply retried on a later tick.
 */
internal fun LocationTrackingService.refreshLowPowerUpdates() {
    val wanted = if (powerManager.isPowerSaveMode) LOW_POWER_INTERVAL_POWER_SAVE_MS else LOW_POWER_INTERVAL_MS
    if (lowPowerIntervalMs != wanted &&
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    ) {
        lowPowerIntervalMs = try {
            locationManager.removeUpdates(lowPowerListener)
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                wanted,
                LOW_POWER_MIN_DISTANCE_METERS,
                mainExecutor,
                lowPowerListener,
            )
            wanted
        } catch (_: SecurityException) {
            0L
        } catch (_: IllegalArgumentException) {
            0L
        }
    }
    if (!passiveRequested) {
        passiveRequested = try {
            locationManager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                PASSIVE_MIN_INTERVAL_MS,
                0f,
                mainExecutor,
                passiveListener,
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

/** Drops the low-power and passive requests (service teardown). */
internal fun LocationTrackingService.stopLowPowerUpdates() {
    locationManager.removeUpdates(lowPowerListener)
    locationManager.removeUpdates(passiveListener)
    lowPowerIntervalMs = 0L
    passiveRequested = false
    forcedFixJob?.cancel()
    forcedFixJob = null
}

private fun LocationTrackingService.isCharging(): Boolean = runCatching { bm.isCharging }.getOrDefault(false)

/**
 * True (and the attempt recorded) when the held fix is stale enough for a forced fix.
 * See [ForcedFixPolicy.isDue]; never while a previous forced fix is still running.
 */
internal fun LocationTrackingService.forcedFixDue(nowMs: Long): Boolean {
    if (forcedFixJob?.isActive == true) return false
    val interval = ForcedFixPolicy.intervalMs(isCharging(), powerManager.isPowerSaveMode)
    val heldMs = lastKnownLocation?.let { it.elapsedRealtimeNanos / NANOS_PER_MILLI }
    if (!ForcedFixPolicy.isDue(nowMs, heldMs, lastForcedFixAttemptMs, interval)) return false
    lastForcedFixAttemptMs = nowMs
    return true
}

/** Starts a forced fix in the background when [forcedFixDue]. */
internal fun LocationTrackingService.maybeRequestForcedFix() {
    if (!forcedFixDue(SystemClock.elapsedRealtime())) return
    forcedFixJob = serviceScope.launch { runForcedFix() }
}

/**
 * One forced fix: network first; GPS after it only when network gave nothing fine
 * enough to display ([ForcedFixPolicy.shouldFallBackToGps]). Each result goes through
 * [onLocationFix], so a coarse network answer can still wake movement detection.
 */
internal suspend fun LocationTrackingService.runForcedFix() {
    val source = currentLocationSourceOverride ?: PlatformCurrentLocationSource(locationManager, mainExecutor)
    val hasNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    val powerSave = powerManager.isPowerSaveMode
    val network = if (hasNetwork) {
        source.current(LocationManager.NETWORK_PROVIDER, ForcedFixPolicy.NETWORK_TIMEOUT_MS)
    } else {
        null
    }
    network?.let { deliverForcedFix(it) }
    val networkAccuracy = network?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
    if (ForcedFixPolicy.shouldFallBackToGps(hasGps, hasNetwork, networkAccuracy, powerSave)) {
        val gps = source.current(LocationManager.GPS_PROVIDER, ForcedFixPolicy.GPS_TIMEOUT_MS)
        gps?.let { deliverForcedFix(it) }
        val gpsResult = if (gps != null) "ok" else "none"
        Log.status(TAG_KEEP_ALIVE, "forced fix: network acc=$networkAccuracy, gps $gpsResult")
    } else {
        Log.status(TAG_KEEP_ALIVE, "forced fix: network acc=$networkAccuracy")
    }
}

private suspend fun LocationTrackingService.deliverForcedFix(location: Location) {
    withContext(Dispatchers.Main.immediate) { onLocationFix(location) }
}
