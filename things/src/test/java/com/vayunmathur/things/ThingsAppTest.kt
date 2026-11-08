package com.vayunmathur.things

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.things.platform.DeviceController
import com.vayunmathur.things.platform.DeviceService
import com.vayunmathur.things.ui.PermissionsRationaleActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** MainActivity, Navigation, DeviceService and the rationale activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThingsAppTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun str(id: Int): String = context.getString(id)

    @Test
    fun navigationShowsHomeThenDevices() {
        DeviceController.init(context)
        DeviceController.bottlePaired.value = false
        DeviceController.scalePaired.value = false
        var bottleScans = 0
        var scaleScans = 0
        composeRule.setContent {
            Navigation(
                onScanClick = { bottleScans++ },
                onDeviceClick = {},
                onForgetBottle = {},
                onScaleScanClick = { scaleScans++ },
                onScaleDeviceClick = {},
                onForgetScale = {},
            )
        }
        composeRule.onNodeWithText(str(R.string.no_devices)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.add_device)).performClick()
        composeRule.onNodeWithText(str(R.string.connect_new_bottle)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.connect_new_bottle)).performClick()
        assertEquals(1, bottleScans)
        assertEquals(0, scaleScans)
        // Dismiss the picker the click opened.
        composeRule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        composeRule.onNodeWithText(str(R.string.connect_new_scale)).performClick()
        assertEquals(1, scaleScans)
    }

    @Test
    fun navigationHomeReflectsControllerState() {
        DeviceController.init(context)
        DeviceController.bottlePaired.value = true
        DeviceController.scalePaired.value = true
        DeviceController.waterTempC.value = 22
        DeviceController.tds.value = 45
        DeviceController.batteryPct.value = 80
        DeviceController.bottleVolumePct.value = 65
        DeviceController.connectionState.value = "Connected"
        var forgotBottle = false
        composeRule.setContent {
            Navigation(
                onScanClick = {},
                onDeviceClick = {},
                onForgetBottle = { forgotBottle = true },
                onScaleScanClick = {},
                onScaleDeviceClick = {},
                onForgetScale = {},
            )
        }
        composeRule.onNodeWithText(str(R.string.bottle_status)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scale_title)).assertIsDisplayed()
        // Profile edits route back through the controller.
        composeRule.onNodeWithText(str(R.string.sex_female)).performScrollTo().performClick()
        assertEquals(com.vayunmathur.things.platform.Sex.Female, DeviceController.scaleSex.value)
        DeviceController.bottlePaired.value = false
        DeviceController.scalePaired.value = false
        assertTrue(!forgotBottle)
    }

    @Test
    fun mainActivityCreatesWithThePermissionGate() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun rationaleActivityCreates() {
        val controller = Robolectric.buildActivity(PermissionsRationaleActivity::class.java).create()
        assertNotNull(controller.get())
        controller.destroy()
    }

    @Test
    fun deviceServiceStartWithoutPermissionStopsItself() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val controller = Robolectric.buildService(DeviceService::class.java).create()
        val result = controller.get().onStartCommand(Intent(), 0, 1)
        assertEquals(Service.START_NOT_STICKY, result)
        controller.destroy()
    }

    @Test
    fun deviceServiceStartWithPermissionStaysSticky() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val controller = Robolectric.buildService(DeviceService::class.java).create()
        val result = controller.get().onStartCommand(Intent(), 0, 1)
        assertEquals(Service.START_STICKY, result)
        assertEquals(null, controller.get().onBind(Intent()))
        controller.destroy()
    }

    @Test
    fun deviceServiceCompanionStartsAndStopsTheService() {
        DeviceService.start(context)
        val started = shadowOf(context as Application).nextStartedService
        assertNotNull(started)
        assertEquals(DeviceService::class.java.name, started.component?.className)
        DeviceService.stop(context)
        val stopped = shadowOf(context as Application).nextStoppedService
        assertNotNull(stopped)
        assertEquals(DeviceService::class.java.name, stopped.component?.className)
    }

    @Test
    fun routesSerializeThroughTheirGeneratedSerializer() {
        val json = kotlinx.serialization.json.Json
        val devices = json.encodeToString(Route.serializer(), Route.Devices)
        val homeRoute = json.encodeToString(Route.serializer(), Route.Home)
        assertTrue(devices != homeRoute)
        assertEquals(Route.Devices, json.decodeFromString(Route.serializer(), devices))
        assertEquals(Route.Home, json.decodeFromString(Route.serializer(), homeRoute))
        assertTrue(Route.Devices != Route.Home)
    }
}
