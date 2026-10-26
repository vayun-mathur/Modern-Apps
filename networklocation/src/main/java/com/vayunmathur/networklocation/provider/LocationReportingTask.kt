package com.vayunmathur.networklocation.provider

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.vayunmathur.library.log.Log
import com.vayunmathur.networklocation.BeaconFix
import com.vayunmathur.networklocation.BeaconId
import com.vayunmathur.networklocation.DevicePosition
import com.vayunmathur.networklocation.NetworkLocationNative
import com.vayunmathur.networklocation.cell.NearbyCells
import com.vayunmathur.networklocation.wifi.NearbyWifi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The periodic fix loop: scan nearby radios, resolve them to coordinates against the offline
 * WPSDB stores, and estimate the device position with the native solver. Reconstructs
 * GrapheneOS's LocationReportingTask.
 *
 * Resolution is entirely on-device. A store lookup is a few probes into a memory-mapped file,
 * so beacons are resolved fresh on every scan rather than cached: a cache would only hold a
 * second copy of coordinates the mapping already serves, in heap instead of reclaimable page
 * cache. Beacons absent from the stores are dropped, so a fix is produced only from what the
 * downloaded stores know; if they have not been downloaded yet, no fix is produced at all.
 */
class LocationReportingTask(
    context: Context,
    private val onFix: (Location) -> Unit,
) {
    private val appContext = context.applicationContext
    private val wifi = NearbyWifi(appContext)
    private val cells = NearbyCells(appContext)
    private val offlineStore = OfflineBeaconStore(appContext)

    private var scope: CoroutineScope? = null

    fun start(intervalMillis: Long) {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        newScope.launch {
            while (isActive) {
                runCatching { reportOnce() }.onFailure { Log.error(TAG, "fix attempt failed", it) }
                delay(intervalMillis.coerceAtLeast(MIN_INTERVAL_MS))
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        offlineStore.close()
    }

    private fun reportOnce() {
        val position = estimate() ?: return
        onFix(position.toLocation())
    }

    /** Full pipeline for a single fix; null when there are no usable beacons. */
    private fun estimate(): DevicePosition? {
        val wifiIds = wifi.scan()
        val cellIds = cells.scan()
        if (wifiIds.isEmpty() && cellIds.isEmpty()) {
            trace("no beacons scanned")
            return null
        }

        val fixes = resolveFixes(wifiIds, cellIds)
        if (fixes.isEmpty()) {
            trace("wifi=${wifiIds.size} cell=${cellIds.size} resolved=0 ${offlineStore.describe()}")
            return null
        }

        val interleaved = DoubleArray(fixes.size * COORDS_PER_FIX)
        for ((i, f) in fixes.withIndex()) {
            interleaved[i * COORDS_PER_FIX] = f.latitude
            interleaved[i * COORDS_PER_FIX + 1] = f.longitude
            interleaved[i * COORDS_PER_FIX + 2] = f.accuracyMeters
        }
        val out = NetworkLocationNative.estimatePosition(interleaved)
        trace(
            "wifi=${wifiIds.size} cell=${cellIds.size} resolved=${fixes.size} " +
                "solver=${if (out == null) "no fix" else "fix"}",
        )
        return out?.let { DevicePosition(it[0], it[1], it[2]) }
    }

    private var lastTrace: String? = null

    /**
     * One status line whenever the pipeline's outcome changes, so a provider that never
     * reports says why. Counts only: beacon identities and positions are never logged.
     */
    private fun trace(summary: String) {
        if (summary == lastTrace) return
        lastTrace = summary
        Log.status(TAG, summary)
    }

    /** Store hits for this scan; beacons the stores do not know are left out of the solve. */
    private fun resolveFixes(wifiIds: List<BeaconId.Wifi>, cellIds: List<BeaconId.Cell>): List<BeaconFix> =
        offlineStore.resolveWifi(wifiIds).values + offlineStore.resolveCell(cellIds).values

    private fun DevicePosition.toLocation(): Location =
        Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = this@toLocation.latitude
            longitude = this@toLocation.longitude
            accuracy = accuracyMeters.toFloat()
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    private companion object {
        const val TAG = "NetworkLocationFix"
        const val MIN_INTERVAL_MS = 1_000L

        /** Coordinates per fix in the interleaved native array (lat, lon, accuracy). */
        const val COORDS_PER_FIX = 3
    }
}
