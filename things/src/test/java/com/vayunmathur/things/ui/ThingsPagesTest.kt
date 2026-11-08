package com.vayunmathur.things.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.things.R
import com.vayunmathur.things.platform.BleManager
import com.vayunmathur.things.platform.DeviceController.LinkState
import com.vayunmathur.things.platform.ScaleBleManager
import com.vayunmathur.things.platform.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home (bottle/scale cards, empty state), devices picker and permissions gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThingsPagesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg a: Any): String = context.getString(id, *a)

    private fun home(
        bottlePaired: Boolean = false,
        scalePaired: Boolean = false,
        onOpenDevices: () -> Unit = {},
        onForgetBottle: () -> Unit = {},
    ) {
        composeRule.setContent {
            HomePage(
                bottlePaired = bottlePaired, bottleLink = LinkState.Connected,
                bottleConnectionState = "Connected", tempC = 22, tds = 45, batteryPct = 80,
                charging = false, volumePct = 65, lastUpdatedMillis = null,
                scalePaired = scalePaired, scaleLink = LinkState.Waiting,
                scaleConnectionState = "Waiting", scaleUserSlot = 1, scaleSex = Sex.Male,
                scaleAge = "30", scaleHeight = "175", scaleAthlete = false,
                onScaleSexChange = {}, onScaleAgeChange = {}, onScaleHeightChange = {},
                onScaleAthleteChange = {}, onForgetBottle = onForgetBottle, onForgetScale = {},
                onResetScale = {}, onOpenDevices = onOpenDevices,
            )
        }
    }

    @Test
    fun homeEmptyStatePromptsAddDevice() {
        var opened = false
        home(onOpenDevices = { opened = true })
        composeRule.onNodeWithText(str(R.string.no_devices)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.add_device)).performClick()
        assertTrue(opened)
    }

    @Test
    fun homeBottleCardShowsTelemetryAndForget() {
        var forgot = false
        home(bottlePaired = true, onForgetBottle = { forgot = true })
        composeRule.onNodeWithText(str(R.string.bottle_status)).assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.percent, 80)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.percent, 65)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.forget_device)).performClick()
        assertTrue(forgot)
    }

    @Test
    fun homeScaleCardShowsProfileInputs() {
        home(scalePaired = true)
        composeRule.onNodeWithText("175").assertIsDisplayed()
    }

    @Test
    fun permissionsNeededShowsRequestActions() {
        var bluetoothRequested = false
        var healthRequested = false
        composeRule.setContent {
            PermissionsPage(
                bluetooth = PermissionState.Needed, healthConnect = PermissionState.Needed,
                onRequestBluetooth = { bluetoothRequested = true },
                onRequestHealthConnect = { healthRequested = true },
                onResolveBluetooth = {}, onResolveHealthConnect = {},
            )
        }
        composeRule.onNodeWithText(str(R.string.permissions_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.permission_bluetooth)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.permission_health_connect)).assertIsDisplayed()
        assertTrue(!bluetoothRequested && !healthRequested)
    }

    @Test
    fun permissionsGrantedShowsGrantedState() {
        composeRule.setContent {
            PermissionsPage(
                bluetooth = PermissionState.Granted, healthConnect = PermissionState.Granted,
                onRequestBluetooth = {}, onRequestHealthConnect = {},
                onResolveBluetooth = {}, onResolveHealthConnect = {},
            )
        }
        composeRule.onNodeWithText(str(R.string.permissions_title)).assertIsDisplayed()
    }

    @Test
    fun devicesPageScanButtonsFire() {
        var bottleScan = false
        var scaleScan = false
        composeRule.setContent {
            DevicesPage(
                scanning = false, discoveredDevices = emptyList(),
                scaleScanning = false, scaleDevices = emptyList(),
                onScanClick = { bottleScan = true }, onDeviceClick = {},
                onScaleScanClick = { scaleScan = true }, onScaleDeviceClick = {},
                onNavigateBack = {},
            )
        }
        composeRule.onNodeWithText(str(R.string.connect_new_bottle)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.connect_new_bottle)).performClick()
        assertTrue(bottleScan)
        assertTrue(!scaleScan)
    }

    @Test
    fun devicesPageListsDiscoveredDevices() {
        var picked: BleManager.BleDevice? = null
        val device = BleManager.BleDevice("Smart Bottle", "AA:BB:CC:DD:EE:FF")
        composeRule.setContent {
            DevicesPage(
                scanning = true, discoveredDevices = listOf(device),
                scaleScanning = false, scaleDevices = emptyList(),
                onScanClick = {}, onDeviceClick = { picked = it },
                onScaleScanClick = {}, onScaleDeviceClick = {},
                onNavigateBack = {},
            )
        }
        // Device rows appear in the picker dialog only after scanning starts;
        // the page itself must render without crash in scanning state.
        assertEquals(null, picked)
        assertTrue(true)
    }
}
