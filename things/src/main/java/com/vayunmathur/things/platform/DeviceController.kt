package com.vayunmathur.things.platform

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-scoped owner of the BLE managers, device state, and all the bottle/scale logic that
 * used to live on [com.vayunmathur.things.MainActivity].
 *
 * Both [DeviceService] (which keeps the process alive in the background) and the Compose UI read
 * and drive this singleton, so the GATT links survive the Activity being stopped or swiped away.
 *
 * State is held as Compose `mutableStateOf`/`mutableStateListOf` directly (rather than the repo's
 * usual `StateFlow` singletons) because the whole `things` UI already passes these value types as
 * params through `Navigation` → `HomePage`/`DevicesPage`, so this keeps the UI layer unchanged.
 */
object DeviceController {

    /**
     * Link state as the UI needs it. The detailed [connectionState]/[scaleConnectionState] strings
     * remain for diagnostics, but nothing branches on their contents any more.
     */
    enum class LinkState { Waiting, Connecting, Connected }

    private lateinit var appContextRef: Context
    val appContext: Context get() = appContextRef

    private lateinit var bleManager: BleManager
    private lateinit var scaleBleManager: ScaleBleManager

    private val mainHandler = Handler(Looper.getMainLooper())

    // Replaces the Activity's lifecycleScope for the async Health Connect writes.
    internal val deviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal val devicePrefs: SharedPreferences by lazy {
        appContextRef.getSharedPreferences("hydration", Context.MODE_PRIVATE)
    }

    private val prefs: SharedPreferences get() = devicePrefs

    private var initialized = false

    // Bottle state.
    val connectionState = mutableStateOf("Disconnected")
    val bottleLink = mutableStateOf(LinkState.Waiting)
    /** Whether an address is remembered, independent of whether the device is reachable now. */
    val bottlePaired = mutableStateOf(false)
    val scalePaired = mutableStateOf(false)
    val scaleLink = mutableStateOf(LinkState.Waiting)
    val scanning = mutableStateOf(false)
    val discoveredDevices = mutableStateListOf<BleManager.BleDevice>()
    val waterTempC = mutableStateOf<Int?>(null)
    val tds = mutableStateOf<Int?>(null)
    val batteryPct = mutableStateOf<Int?>(null)
    val charging = mutableStateOf(false)
    val bottleVolumePct = mutableStateOf<Int?>(null)
    val bottleLastUpdated = mutableStateOf<Long?>(null)

    // Scale (Renpho Elis 1 / Qingniu) — offline BLE only, no cloud.
    val scaleDevices = mutableStateListOf<ScaleBleManager.ScaleBleDevice>()
    /** Which of the scale's eight user slots is ours, or null until we have registered. */
    val scaleUserSlot = mutableStateOf<Int?>(null)
    val scaleScanning = mutableStateOf(false)
    val scaleConnectionState = mutableStateOf("Disconnected")
    val scaleRealtimeWeight = mutableStateOf<Double?>(null)
    val scaleWeight = mutableStateOf<Double?>(null)
    val scaleR50 = mutableStateOf<Int?>(null)
    val scaleR500 = mutableStateOf<Int?>(null)
    val scaleMetrics = mutableStateOf<BodyMetrics?>(null)
    val scaleProfile = mutableStateOf(ScaleProfile())
    val scaleSex = mutableStateOf(Sex.Male)
    val scaleAge = mutableStateOf("30")
    val scaleHeight = mutableStateOf("175")
    val scaleAthlete = mutableStateOf(false)

    /** Idempotent. Safe to call from both the service and the Activity. */
    fun init(context: Context) {
        if (initialized) return
        appContextRef = context.applicationContext
        bleManager = BleManager()
        scaleBleManager = ScaleBleManager()
        loadScaleProfile()
        loadBottleTelemetry()
        scaleUserSlot.value = scaleUserIndex()
        refreshPaired()
        initialized = true
    }

    /**
     * The bottle only reports while connected, and it is out of range most of the time, so its
     * last readings are kept across process restarts rather than resetting the card to empty.
     */
    internal fun persistBottleTelemetry() {
        try {
            prefs.edit {
                waterTempC.value?.let { putInt(BOTTLE_TEMP_KEY, it) }
                tds.value?.let { putInt(BOTTLE_TDS_KEY, it) }
                batteryPct.value?.let { putInt(BOTTLE_BATTERY_KEY, it) }
                bottleVolumePct.value?.let { putInt(BOTTLE_VOLUME_KEY, it) }
                putBoolean(BOTTLE_CHARGING_KEY, charging.value)
                bottleLastUpdated.value?.let { putLong(BOTTLE_UPDATED_KEY, it) }
            }
        } catch (_: Exception) {}
    }

    private fun loadBottleTelemetry() {
        try {
            if (prefs.contains(BOTTLE_TEMP_KEY)) waterTempC.value = prefs.getInt(BOTTLE_TEMP_KEY, 0)
            if (prefs.contains(BOTTLE_TDS_KEY)) tds.value = prefs.getInt(BOTTLE_TDS_KEY, 0)
            if (prefs.contains(BOTTLE_BATTERY_KEY)) batteryPct.value = prefs.getInt(BOTTLE_BATTERY_KEY, 0)
            if (prefs.contains(BOTTLE_VOLUME_KEY)) bottleVolumePct.value = prefs.getInt(BOTTLE_VOLUME_KEY, 0)
            charging.value = prefs.getBoolean(BOTTLE_CHARGING_KEY, false)
            if (prefs.contains(BOTTLE_UPDATED_KEY)) bottleLastUpdated.value = prefs.getLong(BOTTLE_UPDATED_KEY, 0)
        } catch (_: Exception) {}
    }

    /** Drop cached readings so a newly paired bottle doesn't inherit the old one's numbers. */
    private fun clearBottleTelemetry() {
        waterTempC.value = null
        tds.value = null
        batteryPct.value = null
        bottleVolumePct.value = null
        charging.value = false
        bottleLastUpdated.value = null
        try {
            prefs.edit {
                remove(BOTTLE_TEMP_KEY)
                remove(BOTTLE_TDS_KEY)
                remove(BOTTLE_BATTERY_KEY)
                remove(BOTTLE_VOLUME_KEY)
                remove(BOTTLE_CHARGING_KEY)
                remove(BOTTLE_UPDATED_KEY)
            }
        } catch (_: Exception) {}
    }

    /** A persisted value stored under the wrong type throws ClassCastException. */
    internal fun savedAddress(key: String): String? =
        try {
            prefs.getString(key, null)
        } catch (_: ClassCastException) {
            null
        }

    private fun refreshPaired() {
        bottlePaired.value = savedAddress(BOTTLE_ADDRESS_KEY) != null
        scalePaired.value = savedAddress(SCALE_ADDRESS_KEY) != null
    }

    /** Marshal onto the main thread; replaces the Activity's `runOnUiThread`. */
    fun runOnMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    // --- Callbacks invoked by the BLE managers ---
    // (see DeviceSessions.kt — extension functions on this object)

    // --- Actions used by the UI / service ---

    fun startBottleScan() = bleManager.startScan()

    fun connectBottle(address: String) {
        // First-ever connect to this bottle = setup: run the registration handshake (blue LED +
        // button press). Reconnects to the already-remembered bottle skip straight to data sync.
        val isNewDevice = prefs.getString(BOTTLE_ADDRESS_KEY, null) != address
        saveDeviceAddress(BOTTLE_ADDRESS_KEY, address)
        bleManager.connect(address, register = isNewDevice)
    }

    fun disconnectBottle() {
        clearDeviceAddress(BOTTLE_ADDRESS_KEY)
        clearBottleTelemetry()
        bleManager.disconnect()
    }

    fun startScaleScan() = scaleBleManager.startScan()

    fun connectScale(address: String) {
        saveDeviceAddress(SCALE_ADDRESS_KEY, address)
        scaleBleManager.connect(address)
    }

    fun disconnectScale() {
        clearDeviceAddress(SCALE_ADDRESS_KEY)
        // The slot belongs to this pairing; keeping it would strand a slot on the scale that we
        // could no longer visit.
        clearScaleUserSlot()
        scaleBleManager.disconnect()
    }

    // --- Scale session state (see DeviceSessions.kt — extensions on this object) ---

    /** Reconnect to any remembered devices. No-op without permission or a powered-on adapter. */
    fun autoConnectSavedDevices() {
        if (!hasBluetoothConnectPermission() || !bluetoothEnabled()) return
        savedAddress(BOTTLE_ADDRESS_KEY)?.let { bleManager.connect(it) }
        // The scale is powered off between weigh-ins, so wait for it passively rather than
        // burning a doomed active attempt on every launch.
        savedAddress(SCALE_ADDRESS_KEY)?.let { scaleBleManager.connect(it, passive = true) }
    }

    /** Whether at least one device is remembered (drives the service lifecycle). */
    fun hasRememberedDevice(): Boolean =
        savedAddress(BOTTLE_ADDRESS_KEY) != null ||
            savedAddress(SCALE_ADDRESS_KEY) != null

    /** Last-resort cleanup when the service is intentionally stopped. */
    fun closeManagers() {
        bleManager.close()
        scaleBleManager.close()
    }

    fun hasBluetoothConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun bluetoothEnabled(): Boolean =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /**
     * True iff Health Connect is available; otherwise surfaces a message. The actual permission
     * *request* stays in the Activity because it needs an Activity result launcher.
     */
    fun isHealthConnectAvailable(): Boolean {
        val status = HealthConnectHelper.availabilityStatus(appContext)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            AppMessages.show("Health Connect not available")
            return false
        }
        return true
    }

    // Remembered device addresses so the app silently reconnects to both devices on launch
    // instead of making the user scan and tap every time it is reopened.
    private fun saveDeviceAddress(key: String, address: String) {
        prefs.edit { putString(key, address) }
        refreshPaired()
    }

    private fun clearDeviceAddress(key: String) {
        prefs.edit { remove(key) }
        refreshPaired()
    }

    internal const val BOTTLE_ADDRESS_KEY = "bottle_address"
    internal const val BOTTLE_TEMP_KEY = "bottle_temp"
    internal const val BOTTLE_TDS_KEY = "bottle_tds"
    internal const val BOTTLE_BATTERY_KEY = "bottle_battery"
    internal const val BOTTLE_VOLUME_KEY = "bottle_volume"
    internal const val BOTTLE_CHARGING_KEY = "bottle_charging"
    internal const val BOTTLE_UPDATED_KEY = "bottle_updated"
    internal const val SCALE_ADDRESS_KEY = "scale_address"
    internal const val SCALE_CATEGORY_KEY = "scale_category"
    internal const val SCALE_ENCRYPT_RES_KEY = "scale_encrypt_resistance"
    internal const val SCALE_USER_INDEX_KEY = "scale_user_index"
    internal const val SCALE_USER_KEY_KEY = "scale_user_key"
    internal const val SCALE_PENDING_RESET_KEY = "scale_pending_reset"
    internal const val MIN_SCALE_USER_KEY = 1
    internal const val MAX_SCALE_USER_KEY = 9999
    internal const val SCALE_USER_KEY_BOUND = 10000
    internal const val MIN_SCALE_AGE = 3
    internal const val MAX_SCALE_AGE = 80
    internal const val MIN_HEIGHT_CM = 40.0
    internal const val MAX_HEIGHT_CM = 240.0
    internal const val DEFAULT_SCALE_AGE = 30
    internal const val DEFAULT_HEIGHT_CM = 175.0
    internal const val ML_PER_LITER = 1000.0
}
