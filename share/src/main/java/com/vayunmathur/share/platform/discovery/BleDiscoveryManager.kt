package com.vayunmathur.share.platform.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import com.vayunmathur.library.log.Log
import androidx.core.content.ContextCompat
import com.vayunmathur.share.protocol.EndpointInfoFields
import com.vayunmathur.share.protocol.ShareNativeDiscovery
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "BleDiscovery"

/**
 * Nearby Connections BLE service UUID — `p000\dses.java:21`.
 *
 * The secondary UUID is `0000FC73-…` (`p000\dses.java:22`) and the GATT-server variant is
 * `0000FEF3-0004-1000-8000-001A11000100` (`:20`). Usage: `p000\dsbl.java:167`, `:455-480`,
 * `:871-876`.
 */
val NEARBY_CONNECTIONS_SERVICE_UUID: ParcelUuid =
    ParcelUuid.fromString("0000FEF3-0000-1000-8000-00805F9B34FB")

/**
 * FastInitiation service UUID — `p000\dvyf.java:10` (`dmxe.m61842a("FE2C")`).
 *
 * Advertised at `p000\dvys.java:288-295`. This is the "a device nearby is sharing"
 * beacon that triggers the receiver's heads-up notification; it is not required for a
 * transfer.
 */
val FAST_INITIATION_SERVICE_UUID: ParcelUuid =
    ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

/**
 * BLE advertisement and scanning for the Nearby Connections bootstrap.
 *
 * Advertises a Nearby Connections `BleAdvertisement` as service-data under
 * [NEARBY_CONNECTIONS_SERVICE_UUID], carrying the Nearby Sharing endpoint-info blob a peer
 * needs to list us; a peer that cannot parse it logs `"Failed to parse endpoint %s (%s)"`
 * (`p000\eafg.java:89-93`) and never shows us. Both byte codecs live in Rust
 * (`share/src/main/rust/src/ble_adv.rs`, `endpoint_info.rs`) and are reached through
 * [ShareNativeDiscovery], so there is exactly one implementation of each wire format and it is
 * unit-tested on the host. There is deliberately **no Kotlin fallback**: a silent fallback
 * that emits a *different* wire format is how the previous divergence went unnoticed. If
 * the native library is unavailable, advertising and scanning fail loudly.
 *
 * ## Extended versus legacy advertising
 *
 * A real endpoint info does not fit a legacy 31-byte advertisement: for a 7-character name
 * the extended service-data is 33 bytes and the fast-mode service-data 27, and both exceed
 * the budget once the AD wrappers are added. GMS uses BLE extended advertising here
 * (`p000\dsbl.java:513`, `"Started BLE extended advertising"`) with a legacy fallback
 * (`:624`), so [startAdvertising] prefers `startAdvertisingSet` and falls back to a
 * fast-mode legacy advertisement, which only fits a name of about four characters.
 *
 * Nearby Presence (`0xFCF1`, `np_adv`) is a separate subsystem and is not on this path;
 * its Rust entry points remain but are unused by discovery.
 */
class BleDiscoveryManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertisingSetCallback: AdvertisingSetCallback? = null
    private var fastInitAdvertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    private val _bleDevices = MutableStateFlow<Map<String, NearbyDevice>>(emptyMap())
    val bleDevices: StateFlow<Map<String, NearbyDevice>> = _bleDevices.asStateFlow()

    private data class AdvertisedPayload(
        val data: ByteArray,
        val fields: EndpointInfoFields,
    )

    private fun decodeAdvertisedPayload(serviceData: ByteArray, addr: String): AdvertisedPayload? {
        // Rust rejects a foreign serviceIdHash, so a null here means "not us".
        val data = parseAdvertisementBytes(serviceData) ?: return null
        // `data` nests the Sharing blob inside the Nearby Connections envelope; the
        // envelope is what carries the peer's endpoint id.
        val endpointInfo = try {
            ShareNativeDiscovery.nativeParseBleEndpointInfo(data)
        } catch (e: UnsatisfiedLinkError) {
            Log.error(TAG, "libshare_nearby unavailable — cannot parse endpoint info", e)
            return null
        } ?: run {
            Log.dev(
                TAG,
                "skipping $addr: not a NearbySharing endpoint payload (" +
                    data.joinToString("") { "%02x".format(it) } + ")",
            )
            return null
        }
        val fields = ShareNativeDiscovery.parseEndpointInfo(endpointInfo) ?: run {
            Log.dev(
                TAG,
                "skipping $addr: endpoint info not parseable (" +
                    endpointInfo.joinToString("") { "%02x".format(it) } + ")",
            )
            return null
        }
        return AdvertisedPayload(data, fields)
    }

    private fun parseAdvertisementBytes(serviceData: ByteArray): ByteArray? {
        return try {
            ShareNativeDiscovery.nativeParseBleAdvertisement(serviceData)
        } catch (e: UnsatisfiedLinkError) {
            Log.error(TAG, "libshare_nearby unavailable — cannot parse advertisements", e)
            null
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun advertiserOrNull(): BluetoothLeAdvertiser? {
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            Log.status(TAG, "missing BLUETOOTH_ADVERTISE")
            return null
        }
        val btAdapter = adapter ?: run {
            Log.status(TAG, "no BluetoothAdapter")
            return null
        }
        if (!btAdapter.isEnabled) {
            Log.status(TAG, "Bluetooth disabled")
            return null
        }
        return btAdapter.bluetoothLeAdvertiser ?: run {
            Log.status(TAG, "bluetoothLeAdvertiser null")
            null
        }
    }

    private fun lowLatencySettings(connectable: Boolean) = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(connectable)
        .build()

    // ------------------------------------------------------------------
    // Advertising (Receive: "visible to nearby devices")
    // ------------------------------------------------------------------

    /**
     * Advertise a Nearby Connections `BleAdvertisement` for [endpointId] carrying
     * [endpointInfo].
     *
     * [endpointInfo] must be a Nearby Sharing endpoint-info blob
     * ([ShareNativeDiscovery.nativeBuildEndpointInfo]); it is wrapped in the Nearby Connections BLE
     * envelope, which is what carries the endpoint id a peer needs to list us.
     * [deviceToken] must be empty or exactly 2 bytes.
     *
     * Prefers extended advertising, which is the only mode a full-length blob fits, and
     * falls back to a fast-mode legacy advertisement — including when the extended set
     * fails asynchronously. Logs which one started, so a device that silently cannot
     * advertise is diagnosable.
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising(
        endpointId: String,
        endpointInfo: ByteArray,
        deviceToken: ByteArray = ByteArray(0),
    ): Boolean {
        val adv = advertiserOrNull() ?: return false
        val payload = try {
            ShareNativeDiscovery.nativeBuildBleEndpointPayload(endpointId, endpointInfo)
        } catch (e: UnsatisfiedLinkError) {
            Log.error(TAG, "libshare_nearby unavailable — refusing to advertise a guessed format", e)
            return false
        }
        if (payload == null) {
            Log.status(TAG, "could not wrap endpointInfo for endpointId '$endpointId'")
            return false
        }
        val extendedSupported = adapter?.isLeExtendedAdvertisingSupported == true
        stopAdvertising()
        advertiser = adv
        if (extendedSupported && startExtended(adv, payload, deviceToken)) return true
        return startLegacyFast(adv, payload, deviceToken)
    }

    /** Build `0xFEF3` service-data, or null when the payload does not fit [fast] mode. */
    private fun serviceData(payload: ByteArray, deviceToken: ByteArray, fast: Boolean): ByteArray? {
        val serviceData = try {
            ShareNativeDiscovery.nativeBuildBleAdvertisement(payload, deviceToken, fast)
        } catch (e: UnsatisfiedLinkError) {
            Log.error(TAG, "libshare_nearby unavailable — refusing to advertise a guessed format", e)
            return null
        }
        if (serviceData == null || serviceData.isEmpty()) {
            Log.status(
                TAG,
                "BleAdvertisement did not fit (${payload.size}B payload, fast=$fast)",
            )
            return null
        }
        return serviceData
    }

    /**
     * `0xFEF3` service-data only — no `addServiceUuid`.
     *
     * GMS's scan filters accept either the service UUID (`p000\dsbl.java:872`) or the
     * service data (`:876`), and its own advertisement carries only the latter (`:454`,
     * `:461`), so the extra UUID AD would waste four bytes of a very tight budget.
     */
    private fun advertiseData(serviceData: ByteArray) = AdvertiseData.Builder()
        .addServiceData(NEARBY_CONNECTIONS_SERVICE_UUID, serviceData)
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .build()

    @SuppressLint("MissingPermission")
    // Broad catch is deliberate: Bluetooth advertising throws undocumented
    // RuntimeExceptions (not just SecurityException) on some stacks.
    @Suppress("TooGenericExceptionCaught")
    private fun startExtended(
        adv: BluetoothLeAdvertiser,
        payload: ByteArray,
        deviceToken: ByteArray,
    ): Boolean {
        val serviceData = serviceData(payload, deviceToken, fast = false) ?: return false
        // Neither connectable nor scannable. `:share` only ever accepts a connection over
        // WIFI_LAN, so connectable buys nothing — and a *scannable* extended set carries no
        // advertising data at all (the controller rejects `LE Set Extended Advertising Data`
        // for one), which would force the payload into a scan response that only an active
        // scanner would ever request.
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status == ADVERTISE_SUCCESS) {
                    Log.status(TAG, "BLE extended advertising started (0xFEF3, ${serviceData.size}B)")
                    return
                }
                // The failure is asynchronous, so this is the only place a fallback can
                // happen — returning early from startAdvertisingSet tells us nothing.
                Log.status(TAG, "BLE extended advertising failed ($status) — trying fast legacy mode")
                advertisingSetCallback = null
                startLegacyFast(adv, payload, deviceToken)
            }
        }
        advertisingSetCallback = callback
        return try {
            adv.startAdvertisingSet(params, advertiseData(serviceData), null, null, null, callback)
            true
        } catch (e: Exception) {
            Log.status(TAG, "startAdvertisingSet threw", e)
            advertisingSetCallback = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    // Broad catch is deliberate: Bluetooth advertising throws undocumented
    // RuntimeExceptions (not just SecurityException) on some stacks.
    @Suppress("TooGenericExceptionCaught")
    private fun startLegacyFast(
        adv: BluetoothLeAdvertiser,
        payload: ByteArray,
        deviceToken: ByteArray,
    ): Boolean {
        val serviceData = serviceData(payload, deviceToken, fast = true) ?: return false
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.status(TAG, "BLE legacy advertising started (0xFEF3, ${serviceData.size}B)")
            }

            override fun onStartFailure(errorCode: Int) {
                // ADVERTISE_FAILED_DATA_TOO_LARGE (1) means the blob outgrew the 31-byte
                // legacy budget, which a device name longer than about four characters
                // does. Extended advertising is the only way out.
                Log.status(TAG, "BLE legacy advertising failed: $errorCode")
            }
        }
        advertiseCallback = callback
        return try {
            // Non-connectable, like the extended path: it drops the 3-byte Flags AD, which
            // is three more characters of device name inside a 31-byte budget.
            adv.startAdvertising(
                lowLatencySettings(connectable = false),
                advertiseData(serviceData),
                callback,
            )
            true
        } catch (e: Exception) {
            Log.status(TAG, "startAdvertising threw", e)
            advertiseCallback = null
            false
        }
    }

    /**
     * Advertise the `0xFE2C` FastInitiation beacon, off by default.
     *
     * Makes nearby receivers show the "device is sharing" heads-up notification
     * (`p000\dvys.java:288-295`). Independent of [startAdvertising] and not required for
     * a transfer, so it is opt-in.
     */
    @SuppressLint("MissingPermission")
    // Broad catch is deliberate: Bluetooth advertising throws undocumented
    // RuntimeExceptions (not just SecurityException) on some stacks.
    @Suppress("TooGenericExceptionCaught")
    fun startFastInitiation(metadata: ByteArray = ByteArray(2)): Boolean {
        val adv = advertiserOrNull() ?: return false
        val serviceData = try {
            ShareNativeDiscovery.nativeFastInitiationServiceData(metadata)
        } catch (e: UnsatisfiedLinkError) {
            Log.error(TAG, "libshare_nearby unavailable — cannot build FastInitiation data", e)
            return false
        } ?: run {
            Log.status(TAG, "FastInitiation metadata must be exactly 2 bytes")
            return false
        }
        stopFastInitiation()
        val data = AdvertiseData.Builder()
            .addServiceUuid(FAST_INITIATION_SERVICE_UUID)
            .addServiceData(FAST_INITIATION_SERVICE_UUID, serviceData)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.status(TAG, "FastInitiation beacon started (0xFE2C)")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.status(TAG, "FastInitiation beacon failed: $errorCode")
            }
        }
        fastInitAdvertiseCallback = callback
        advertiser = adv
        return try {
            adv.startAdvertising(lowLatencySettings(connectable = false), data, callback)
            true
        } catch (e: Exception) {
            Log.status(TAG, "FastInitiation startAdvertising threw", e)
            fastInitAdvertiseCallback = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val adv = advertiser ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        advertisingSetCallback?.let { cb ->
            try {
                adv.stopAdvertisingSet(cb)
            } catch (_: Exception) {
            }
            advertisingSetCallback = null
            Log.debug(TAG, "BLE extended advertising stopped")
        }
        advertiseCallback?.let { cb ->
            try {
                adv.stopAdvertising(cb)
            } catch (_: Exception) {
            }
            advertiseCallback = null
            Log.debug(TAG, "BLE legacy advertising stopped")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopFastInitiation() {
        val cb = fastInitAdvertiseCallback ?: return
        val adv = advertiser ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        try {
            adv.stopAdvertising(cb)
        } catch (_: Exception) {
        }
        fastInitAdvertiseCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun parseScanResult(result: ScanResult): NearbyDevice? {
        val record = result.scanRecord ?: return null
        val serviceData = record.getServiceData(NEARBY_CONNECTIONS_SERVICE_UUID)
            ?: return null
        val addr = result.device.address ?: return null
        val payload = decodeAdvertisedPayload(serviceData, addr) ?: return null
        // Key on the advertised endpoint id, which is also what the mDNS leg
        // reports, so one device does not appear twice.
        val endpointId = try {
            ShareNativeDiscovery.nativeParseBleEndpointId(payload.data)
        } catch (e: UnsatisfiedLinkError) {
            Log.error(TAG, "libshare_nearby unavailable — cannot parse endpoint id", e)
            null
        } ?: addr
        return NearbyDevice(
            endpointId = endpointId,
            // Both names come from bytes already in hand: the endpoint-info blob, then
            // the advertisement's own local name. `BluetoothDevice.getName()` would
            // report the same thing but needs BLUETOOTH_CONNECT, which this app does
            // not hold.
            endpointName = payload.fields.deviceName
                ?: record.deviceName
                ?: addr,
            host = null,
            port = null,
            source = DiscoverySource.Ble,
            extra = addr,
        )
    }

    // ------------------------------------------------------------------
    // Scanning (Send: discover peers)
    // ------------------------------------------------------------------

    /**
     * Scan for `0xFEF3` advertisers. Emits one [NearbyDevice] per scan result whose
     * service-data parses as a `BleAdvertisement` for `"NearbySharing"` carrying a valid
     * endpoint info — the same two checks a real device applies, so anything we skip is
     * something it would have skipped too.
     *
     * [NearbyDevice.endpointName] is the peer's advertised device name; a contact-only
     * peer publishes none, so those fall back to the Bluetooth name or the MAC address.
     * A BLE-only entry is not connectable: only the mDNS browse supplies host and port.
     */
    // Broad catch is deliberate: BLE scanning throws undocumented
    // RuntimeExceptions (not just SecurityException) on some stacks.
    @Suppress("TooGenericExceptionCaught")
    fun scan(): Flow<NearbyDevice> = callbackFlow {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.status(TAG, "scan denied: missing BLUETOOTH_SCAN")
            close()
            return@callbackFlow
        }
        val btAdapter = adapter ?: run {
            close()
            return@callbackFlow
        }
        if (!btAdapter.isEnabled) {
            close()
            return@callbackFlow
        }
        val scanner = btAdapter.bluetoothLeScanner ?: run {
            Log.status(TAG, "bluetoothLeScanner null")
            close()
            return@callbackFlow
        }
        // Two filters, as GMS uses (`p000\dsbl.java:870-876`): one on the service UUID and
        // one on the service *data*, since an advertisement that carries only service data
        // — which ours now does — does not match a UUID filter. The `{0}` data with a `{0}`
        // mask matches any payload for the UUID.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(NEARBY_CONNECTIONS_SERVICE_UUID).build(),
            ScanFilter.Builder()
                .setServiceData(NEARBY_CONNECTIONS_SERVICE_UUID, byteArrayOf(0), byteArrayOf(0))
                .build(),
        )
        // setLegacy(false) is what makes extended advertisements visible at all; a legacy
        // scan silently drops every peer that advertises a full-length endpoint info
        // (`p000\dsbl.java:884`).
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        val callback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                this@BleDiscoveryManager.parseScanResult(result)?.let { dev ->
                    _bleDevices.value = _bleDevices.value.toMutableMap().apply {
                        put(dev.endpointId, dev)
                    }
                    trySend(dev)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.status(TAG, "BLE scan failed: $errorCode")
                close()
            }
        }
        scanCallback = callback
        try {
            @SuppressLint("MissingPermission")
            fun doStart() = scanner.startScan(filters, settings, callback)
            doStart()
        } catch (e: Exception) {
            Log.status(TAG, "startScan threw", e)
            close(e)
            return@callbackFlow
        }
        awaitClose {
            @SuppressLint("MissingPermission")
            fun stopIfPermitted() {
                if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                    try {
                        scanner.stopScan(callback)
                    } catch (_: SecurityException) {
                    } catch (_: Exception) {
                    }
                }
            }
            stopIfPermitted()
            scanCallback = null
            Log.debug(TAG, "BLE scan stopped")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCallback ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(cb)
        } catch (_: Exception) {
        }
        scanCallback = null
    }

    fun release() {
        stopAdvertising()
        stopFastInitiation()
        stopScan()
        advertiser = null
    }
}

