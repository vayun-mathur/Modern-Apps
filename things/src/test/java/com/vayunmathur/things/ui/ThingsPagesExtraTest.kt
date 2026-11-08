package com.vayunmathur.things.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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

/** Interaction coverage the first page pass did not reach: dialogs, inputs, blocked states. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThingsPagesExtraTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg a: Any): String = context.getString(id, *a)

    private fun home(
        bottlePaired: Boolean = false,
        scalePaired: Boolean = false,
        charging: Boolean = false,
        withData: Boolean = true,
        lastUpdatedMillis: Long? = null,
        scaleUserSlot: Int? = 1,
        onScaleSexChange: (Sex) -> Unit = {},
        onScaleAgeChange: (String) -> Unit = {},
        onScaleHeightChange: (String) -> Unit = {},
        onScaleAthleteChange: (Boolean) -> Unit = {},
        onForgetBottle: () -> Unit = {},
        onForgetScale: () -> Unit = {},
        onResetScale: () -> Unit = {},
        onHealthConnectClick: () -> Unit = {},
        onOpenDevices: () -> Unit = {},
    ) {
        composeRule.setContent {
            HomePage(
                bottlePaired = bottlePaired, bottleLink = LinkState.Connected,
                bottleConnectionState = "Connected",
                tempC = if (withData) 22 else null,
                tds = if (withData) 45 else null,
                batteryPct = if (withData) 80 else null,
                charging = charging,
                volumePct = if (withData) 65 else null,
                lastUpdatedMillis = lastUpdatedMillis,
                scalePaired = scalePaired, scaleLink = LinkState.Waiting,
                scaleConnectionState = "Waiting", scaleUserSlot = scaleUserSlot,
                scaleSex = Sex.Male, scaleAge = "30", scaleHeight = "175", scaleAthlete = false,
                onScaleSexChange = onScaleSexChange, onScaleAgeChange = onScaleAgeChange,
                onScaleHeightChange = onScaleHeightChange,
                onScaleAthleteChange = onScaleAthleteChange,
                onForgetBottle = onForgetBottle, onForgetScale = onForgetScale,
                onResetScale = onResetScale, onHealthConnectClick = onHealthConnectClick,
                onOpenDevices = onOpenDevices,
            )
        }
    }

    @Test
    fun bottleCardWaitingForDataWhenNoTelemetry() {
        home(bottlePaired = true, withData = false)
        composeRule.onNodeWithText(str(R.string.waiting_for_data)).assertIsDisplayed()
    }

    @Test
    fun bottleCardChargingAndLastUpdatedRender() {
        home(bottlePaired = true, charging = true, lastUpdatedMillis = 1_700_000_000_000L)
        composeRule.onNodeWithText(str(R.string.battery_charging, 80)).assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("Updated", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun healthConnectButtonFires() {
        var clicked = false
        home(onHealthConnectClick = { clicked = true })
        composeRule.onNodeWithText(str(R.string.permission_health_connect)).performClick()
        assertTrue(clicked)
    }

    @Test
    fun scaleCardProfileInteractions() {
        var sex: Sex? = null
        var age: String? = null
        var height: String? = null
        var athlete: Boolean? = null
        var forgotScale = false
        home(
            scalePaired = true,
            onScaleSexChange = { sex = it },
            onScaleAgeChange = { age = it },
            onScaleHeightChange = { height = it },
            onScaleAthleteChange = { athlete = it },
            onForgetScale = { forgotScale = true },
        )
        composeRule.onNodeWithText(str(R.string.scale_user_slot, 1)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.sex_female)).performClick()
        assertEquals(Sex.Female, sex)
        composeRule.onNodeWithText("30").performTextReplacement("41")
        assertEquals("41", age)
        composeRule.onNodeWithText("175").performTextReplacement("182")
        assertEquals("182", height)
        // The label text is not itself clickable; the checkbox next to it is.
        composeRule.onNode(androidx.compose.ui.test.isToggleable())
            .performScrollTo().performClick()
        assertEquals(true, athlete)
        composeRule.onNodeWithText(str(R.string.forget_device)).performScrollTo().performClick()
        assertTrue(forgotScale)
    }

    @Test
    fun scaleCardUnregisteredSlotLabel() {
        home(scalePaired = true, scaleUserSlot = null)
        composeRule.onNodeWithText(str(R.string.scale_user_unregistered)).assertIsDisplayed()
    }

    @Test
    fun scaleResetDialogConfirmAndDismiss() {
        var resets = 0
        home(scalePaired = true, onResetScale = { resets++ })
        composeRule.onNodeWithText(str(R.string.scale_reset_users)).performScrollTo().performClick()
        composeRule.onNodeWithText(str(R.string.scale_reset_title)).assertIsDisplayed()
        // Dismiss first: no reset.
        composeRule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertEquals(0, resets)
        // Reopen and confirm.
        composeRule.onNodeWithText(str(R.string.scale_reset_users)).performScrollTo().performClick()
        composeRule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.reset)).performClick()
        assertEquals(1, resets)
    }

    @Test
    fun devicesPickerListsAndPicksBottle() {
        var picked: BleManager.BleDevice? = null
        var scanned = false
        val device = BleManager.BleDevice("WaterH Bottle", "00:11:22:33:44:55")
        composeRule.setContent {
            DevicesPage(
                scanning = true, discoveredDevices = listOf(device),
                scaleScanning = false, scaleDevices = emptyList(),
                onScanClick = { scanned = true }, onDeviceClick = { picked = it },
                onScaleScanClick = {}, onScaleDeviceClick = {},
                onNavigateBack = null,
            )
        }
        composeRule.onNodeWithText(str(R.string.connect_new_bottle)).performClick()
        assertTrue(scanned)
        composeRule.onNodeWithText(str(R.string.select_device)).assertIsDisplayed()
        composeRule.onNodeWithText("WaterH Bottle").assertIsDisplayed()
        composeRule.onNodeWithText("00:11:22:33:44:55").performClick()
        assertEquals(device, picked)
    }

    @Test
    fun devicesPickerListsAndPicksScaleAndDismisses() {
        var picked: ScaleBleManager.ScaleBleDevice? = null
        val device = ScaleBleManager.ScaleBleDevice("QN-Scale", "00:11:22:33:44:66")
        composeRule.setContent {
            DevicesPage(
                scanning = false, discoveredDevices = emptyList(),
                scaleScanning = false, scaleDevices = listOf(device),
                onScanClick = {}, onDeviceClick = {},
                onScaleScanClick = {}, onScaleDeviceClick = { picked = it },
                onNavigateBack = {},
            )
        }
        composeRule.onNodeWithText(str(R.string.connect_new_scale)).performClick()
        composeRule.onNodeWithText("QN-Scale").performClick()
        assertEquals(device, picked)
        // Reopen and dismiss with Cancel: nothing more is picked.
        composeRule.onNodeWithText(str(R.string.connect_new_scale)).performClick()
        composeRule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        composeRule.onNodeWithText(str(R.string.connect_new_scale)).assertIsDisplayed()
    }

    @Test
    fun devicesPickerSearchingAndEmptyStates() {
        composeRule.setContent {
            DevicesPage(
                scanning = true, discoveredDevices = emptyList(),
                scaleScanning = false, scaleDevices = emptyList(),
                onScanClick = {}, onDeviceClick = {},
                onScaleScanClick = {}, onScaleDeviceClick = {},
                onNavigateBack = {},
            )
        }
        composeRule.onNodeWithText(str(R.string.connect_new_bottle)).performClick()
        composeRule.onNodeWithText(str(R.string.searching)).assertIsDisplayed()
        composeRule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
    }

    @Test
    fun permissionsBlockedShowsSettingsActions() {
        var btResolve = false
        var hcResolve = false
        composeRule.setContent {
            PermissionsPage(
                bluetooth = PermissionState.Blocked, healthConnect = PermissionState.Blocked,
                onRequestBluetooth = {}, onRequestHealthConnect = {},
                onResolveBluetooth = { btResolve = true },
                onResolveHealthConnect = { hcResolve = true },
            )
        }
        assertEquals(
            2,
            composeRule.onAllNodesWithText(str(R.string.permission_blocked))
                .fetchSemanticsNodes().size,
        )
        val settings = composeRule.onAllNodesWithText(str(R.string.permission_open_settings))
        assertEquals(2, settings.fetchSemanticsNodes().size)
        settings[0].performClick()
        assertTrue(btResolve)
        settings[1].performScrollTo().performClick()
        assertTrue(hcResolve)
    }

    @Test
    fun permissionsNeededGrantButtonsFire() {
        var bt = false
        var hc = false
        composeRule.setContent {
            PermissionsPage(
                bluetooth = PermissionState.Needed, healthConnect = PermissionState.Granted,
                onRequestBluetooth = { bt = true }, onRequestHealthConnect = { hc = true },
                onResolveBluetooth = {}, onResolveHealthConnect = {},
            )
        }
        composeRule.onNodeWithText(str(R.string.permission_grant)).performClick()
        assertTrue(bt)
        assertTrue(!hc, "the granted card has no grant button")
        composeRule.onNodeWithText(str(R.string.permission_granted)).assertIsDisplayed()
    }
}
