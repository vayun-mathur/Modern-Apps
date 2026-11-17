package com.vayunmathur.things.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import androidx.core.util.isNotEmpty
import com.vayunmathur.library.log.Log

/**
 * The connection half of [ScaleBleManager]: scan, passive watch, GATT open,
 * disconnect and close, as `internal` members of the manager so
 * ScaleBleManager.kt stays under the FileLength limit. Packet handling and
 * measurement decoding stay on the manager.
 */

private fun ScaleBleManager.isScaleName(name: String?): Boolean {
    if (name == null) return false
    return ScaleBleManager.SCALE_NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
}

internal fun ScaleBleManager.makeScanCallback(): ScanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        val name = try {
            result.device.name
        } catch (_: SecurityException) {
            return
        }
        if (!isScaleName(name)) return
        val addr = result.device.address
        result.scanRecord?.manufacturerSpecificData?.let { data ->
            if (data.isNotEmpty()) data.valueAt(0)?.let {
                manufacturerData[addr] = it
                Log.dev(
                    ScaleBleManager.TAG,
                    "scan $name company=0x${data.keyAt(0).toString(HEX_RADIX)} mfg=${it.toHex()} " +
                        "category=${qnScaleCategory(it)} " +
                        "encryptRes=${qnUsesResistanceEncrypt(qnScaleCategory(it), it)}",
                )
            }
        } ?: Log.dev(ScaleBleManager.TAG, "scan $name (no manufacturer data)")
        if (DeviceController.scaleDevices.none { it.address == addr }) {
            DeviceController.scaleDevices.add(ScaleBleManager.ScaleBleDevice(name ?: "Scale", addr))
        }
    }

    override fun onScanFailed(errorCode: Int) {
        DeviceController.runOnMain {
            stopScan()
            DeviceController.scaleConnectionState.value = "Scan failed ($errorCode)"
        }
    }
}

internal fun ScaleBleManager.makeWatchCallback(): ScanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        if (!result.device.address.equals(watchAddress, ignoreCase = true)) return
        result.scanRecord?.manufacturerSpecificData?.let { data ->
            if (data.isNotEmpty()) data.valueAt(0)?.let { manufacturerData[result.device.address] = it }
        }
        Log.dev(ScaleBleManager.TAG, "scale woke up; connecting")
        stopWatch()
        openGattFor(result.device)
    }

    override fun onScanFailed(errorCode: Int) {
        Log.error(ScaleBleManager.TAG, "watch scan failed error=$errorCode")
        watchAddress = null
        DeviceController.runOnMain {
            DeviceController.scaleConnectionState.value = "Scan failed ($errorCode)"
        }
    }
}

@SuppressLint("MissingPermission")
internal fun ScaleBleManager.startScanNow(scanCallback: ScanCallback) {
    DeviceController.scaleDevices.clear()
    manufacturerData.clear()
    DeviceController.scaleScanning.value = true
    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()
    scanner?.startScan(null, settings, scanCallback)
    DeviceController.scaleConnectionState.value = SCALE_SCANNING_STATE
    handler.removeCallbacks(scanTimeout)
    handler.postDelayed(scanTimeout, ScaleBleManager.SCAN_TIMEOUT_MS)
}

@SuppressLint("MissingPermission")
internal fun ScaleBleManager.stopScanNow(scanCallback: ScanCallback) {
    handler.removeCallbacks(scanTimeout)
    scanner?.stopScan(scanCallback)
    DeviceController.scaleScanning.value = false
}

/**
 * A body scale is powered off between weigh-ins, so rather than hold a GATT connection open we
 * watch for its advertisement and connect the moment it appears.
 *
 * This deliberately does not use `connectGatt(autoConnect = true)`: the scale advertises a
 * random static address, and [android.bluetooth.BluetoothAdapter.getRemoteDevice] can only
 * build a public-address device, so a background connection against it never matches. The
 * [ScanResult]'s own device carries the correct address type.
 */
@SuppressLint("MissingPermission")
internal fun ScaleBleManager.startWatchNow(address: String, watchCallback: ScanCallback) {
    if (watchAddress == address) return
    stopWatch()
    watchAddress = address
    val filters = listOf(ScanFilter.Builder().setDeviceAddress(address).build())
    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .build()
    runCatching { scanner?.startScan(filters, settings, watchCallback) }
        .onFailure { Log.error(ScaleBleManager.TAG, "watch scan failed to start", it) }
    Log.dev(ScaleBleManager.TAG, "watching for $address to wake up")
    DeviceController.scaleLink.value = DeviceController.LinkState.Waiting
    DeviceController.scaleConnectionState.value = SCALE_WAITING_STATE
}

@SuppressLint("MissingPermission")
internal fun ScaleBleManager.stopWatchNow(watchCallback: ScanCallback) {
    if (watchAddress == null) return
    watchAddress = null
    runCatching { scanner?.stopScan(watchCallback) }
}

@SuppressLint("MissingPermission")
internal fun ScaleBleManager.openGattNow(device: BluetoothDevice) {
    stopScan()
    stopWatch()
    val address = device.address
    // The category and encryption flag are only ever advertised, so a reconnect that never
    // scanned has to fall back to what the last scan learned.
    val mfg = manufacturerData[address]
    if (mfg != null) {
        scaleCategory = qnScaleCategory(mfg)
        useResistanceEncrypt = qnUsesResistanceEncrypt(scaleCategory, mfg)
        DeviceController.saveScaleAdvertisedTraits(scaleCategory, useResistanceEncrypt)
    } else {
        scaleCategory = DeviceController.savedScaleCategory() ?: CATEGORY_DEFAULT
        useResistanceEncrypt = DeviceController.savedScaleEncryptsResistance()
    }
    isVaScale = scaleCategory in VA_CATEGORIES
    Log.dev(
        ScaleBleManager.TAG,
        "connect $address category=$scaleCategory va=$isVaScale encryptRes=$useResistanceEncrypt",
    )
    DeviceController.scaleLink.value = DeviceController.LinkState.Connecting
    DeviceController.scaleConnectionState.value = "Connecting scale..."
    gatt = device.connectGatt(DeviceController.appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
}

internal fun ScaleBleManager.closeGattNow() {
    handler.removeCallbacks(timeRetry)
    handler.removeCallbacks(sendStart)
    stopWatch()
    gatt?.let {
        try {
            it.close()
        } catch (_: SecurityException) {
            // Permission revoked after connecting: the reference is dropped below anyway.
        }
        refreshCache(it)
    }
    gatt = null
}
