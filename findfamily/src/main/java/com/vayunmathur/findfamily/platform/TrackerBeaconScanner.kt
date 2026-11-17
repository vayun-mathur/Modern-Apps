package com.vayunmathur.findfamily.platform
import com.vayunmathur.findfamily.domain.TrackerBle
import com.vayunmathur.findfamily.domain.TrackerProtocol
import com.vayunmathur.findfamily.domain.TrackerSighting

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Finder side of the crowd-finding network: passively scans for tracker beacons
 * ([TrackerBle.SERVICE_UUID]) and emits a [TrackerSighting] for each advertisement
 * seen. Hosted by the foreground `LocationTrackingService` so scanning continues in
 * the background (gated behind `TrackerFeature.enabled`).
 *
 * Permissions (`BLUETOOTH_SCAN`) are declared only in `src/dev/AndroidManifest.xml`;
 * a missing grant surfaces as a `SecurityException` which is caught and closes the
 * flow rather than crashing.
 */
class TrackerBeaconScanner(private val context: Context) {

    @SuppressLint("MissingPermission")
    // Broad catch is deliberate: BLE throws varied runtime exceptions; scan failure must close the flow, not crash.
    @Suppress("TooGenericExceptionCaught")
    fun sightings(): Flow<TrackerSighting> = callbackFlow {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.status(TAG, "sightings: no BLE scanner (adapter off or unavailable)")
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val serviceData = result.scanRecord
                    ?.getServiceData(ParcelUuid(TrackerBle.SERVICE_UUID)) ?: return
                if (serviceData.size < TrackerProtocol.EPOCH_ID_LEN) return
                val epochId = serviceData.copyOf(TrackerProtocol.EPOCH_ID_LEN)
                val battery = if (serviceData.size > TrackerProtocol.EPOCH_ID_LEN) {
                    serviceData[TrackerProtocol.EPOCH_ID_LEN].toInt() and 0xFF
                } else -1
                Log.status(
                    TAG,
                    "sighting: epochId=${epochId.joinToString("") { "%02x".format(it) }} " +
                        "battery=$battery rssi=${result.rssi}"
                )
                trySend(TrackerSighting(epochId, battery, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                Log.status(TAG, "BLE scan failed: $errorCode")
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(TrackerBle.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            // The beacon is a BLE 5 extended advertisement — 17B of service data under a
            // 128-bit UUID doesn't fit the 31-byte legacy limit (see TrackerBle). The
            // default legacy-only filter would drop it entirely. setLegacy(false) reports
            // both legacy and extended results, so pairing-mode discovery is unaffected.
            .setLegacy(false)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, callback)
            Log.status(TAG, "tracker beacon scan started")
        } catch (e: SecurityException) {
            Log.status(TAG, "startScan denied (missing BLUETOOTH_SCAN)", e)
            close(e)
            return@callbackFlow
        } catch (e: Exception) {
            Log.status(TAG, "startScan failed", e)
            close(e)
            return@callbackFlow
        }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }

    /** Logging tag for [TrackerBeaconScanner]. */
    companion object {
        private const val TAG = "TrackerBeaconScanner"
    }
}
