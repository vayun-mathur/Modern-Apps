package com.vayunmathur.findfamily.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Scans for powered-off beacons ([PoweredOffBle]) and emits a [PoweredOffSighting] for each.
 *
 * This is the half of powered-off finding that needs **no privileged permission and no custom
 * OS**. Arming a beacon requires `BLUETOOTH_PRIVILEGED`, which is `signature|privileged`, so only
 * a preinstalled build can do it — but hearing one is ordinary `BLUETOOTH_SCAN`, so any phone
 * with findfamily on it can contribute sightings. Keep it that way: nothing in this file, or
 * anything it calls, may come to depend on a privileged API.
 *
 * ## Battery
 * A finder is doing a favour for a stranger, so this has to cost close to nothing or nobody will
 * leave it on and the network is worthless. Three things do the work:
 *  - **Hardware offload filtering.** [PoweredOffBle.scanFilter] matches on the service-data
 *    prefix, so the controller discards non-FMDN advertisements without waking the application
 *    processor. On a busy street that is essentially all of them.
 *  - **First-match callbacks.** With offloaded filtering the controller reports each matching
 *    device *once* rather than every 2 seconds forever. A beacon's EID only rotates every
 *    [PoweredOffProtocol.SLOT_SECONDS], so per-advertisement callbacks would be ~500 wakeups per
 *    beacon per useful report. Where the controller can't do it we fall back to
 *    `CALLBACK_TYPE_ALL_MATCHES` and [PoweredOffReporting] dedupes in software instead —
 *    correct either way, just less efficient.
 *  - **Low-power duty cycle**, the longest window/interval the framework offers.
 *
 * There is deliberately **no wakelock**. This runs inside the existing location foreground
 * service; if that service is not running we simply are not a finder for a while, which is the
 * right trade for someone else's benefit.
 */
class PoweredOffScanner(private val context: Context) {

    @SuppressLint("MissingPermission")
    // Broad catch is deliberate: BLE throws varied runtime exceptions; scan failure must close the flow, not crash.
    @Suppress("TooGenericExceptionCaught")
    fun sightings(): Flow<PoweredOffSighting> = callbackFlow {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null) {
            Log.status(TAG, "no BLE scanner (adapter off or unavailable)")
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                // Which of the candidate UUIDs the frame actually arrived under is unknown until
                // someone captures one, so try each and take the first that parses.
                val eid = PoweredOffBle.CANDIDATE_SERVICE_UUIDS
                    .firstNotNullOfOrNull { PoweredOffBle.eidFrom(record.getServiceData(it)) } ?: return
                trySend(PoweredOffSighting(eid, result.rssi))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.status(TAG, "scan failed: $errorCode")
            }
        }

        try {
            scanner.startScan(PoweredOffBle.scanFilters(), scanSettings(adapter), callback)
            Log.status(TAG, "powered-off beacon scan started")
        } catch (e: SecurityException) {
            Log.status(TAG, "startScan denied (missing BLUETOOTH_SCAN)", e)
            close(e)
            return@callbackFlow
        } catch (e: Exception) {
            Log.status(TAG, "startScan failed", e)
            close(e)
            return@callbackFlow
        }

        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private fun scanSettings(adapter: BluetoothAdapter): ScanSettings {
        val builder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            // The frame is assumed legacy, but the firmware composes it and we cannot read it
            // (see PoweredOffBle). setLegacy(false) reports legacy *and* extended results, so
            // this stays correct whichever the controller chose.
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        if (adapter.isOffloadedFilteringSupported) {
            builder.setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                // STICKY only reports a beacon once it has been heard convincingly, which suits
                // a report that costs a network round-trip; AGGRESSIVE would fire on a single
                // weak advertisement from something we may never hear again.
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
        } else {
            Log.status(TAG, "no offloaded filtering; falling back to all-matches and software dedup")
        }
        return builder.build()
    }

    /** Logging tag for [PoweredOffScanner]. */
    companion object {
        private const val TAG = "PoweredOffScanner"
    }
}
