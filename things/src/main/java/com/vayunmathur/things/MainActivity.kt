package com.vayunmathur.things

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.things.ui.ThingsApp
import com.vayunmathur.things.util.BleManager
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BleManager
    val messages = mutableStateListOf<String>()       // sip history (newest first)
    val totalMl = mutableIntStateOf(0)                 // today's running total
    val connectionState = mutableStateOf("Disconnected")
    val scanning = mutableStateOf(false)
    val discoveredDevices = mutableStateListOf<BleManager.BleDevice>()

    private val prefs by lazy { getSharedPreferences("hydration", MODE_PRIVATE) }

    private fun today() = LocalDate.now().toString()

    private fun loadTodayTotal() {
        totalMl.intValue =
            if (prefs.getString("date", null) == today()) prefs.getInt("total_ml", 0) else 0
    }

    private fun saveTotal() {
        prefs.edit()
            .putString("date", today())
            .putInt("total_ml", totalMl.intValue)
            .apply()
    }

    /** Called from the BLE callback when the cup reports a drink (mL). */
    fun onDrinkReceived(ml: Int) {
        if (prefs.getString("date", null) != today()) {
            // New day since the last reading: start fresh.
            totalMl.intValue = 0
            messages.clear()
        }
        totalMl.intValue += ml
        saveTotal()
        val timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        messages.add(0, "[$timestamp]  +$ml mL")
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            bleManager.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bleManager = BleManager(this)
        loadTodayTotal()
        setContent {
            DynamicTheme {
                ThingsApp(
                    totalMl = totalMl.intValue,
                    goalMl = GOAL_ML,
                    messages = messages,
                    connectionState = connectionState.value,
                    scanning = scanning.value,
                    discoveredDevices = discoveredDevices,
                    onScanClick = ::requestPermissionsAndScan,
                    onDeviceClick = { bleManager.connect(it.address) },
                    onDisconnectClick = { bleManager.disconnect() }
                )
            }
        }
    }

    private fun requestPermissionsAndScan() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.close()
    }

    companion object {
        private const val GOAL_ML = 2000
    }
}
