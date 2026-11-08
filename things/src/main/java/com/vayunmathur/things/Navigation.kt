package com.vayunmathur.things

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.things.platform.BleManager
import com.vayunmathur.things.platform.DeviceController
import com.vayunmathur.things.platform.ScaleBleManager
import com.vayunmathur.things.platform.recalcScaleMetrics
import com.vayunmathur.things.platform.requestScaleReset
import com.vayunmathur.things.ui.DevicesPage
import com.vayunmathur.things.ui.HomePage

/**
 * Device state is read inside each `entry` block rather than passed in from the Activity.
 *
 * [MainNavigation]'s `NavDisplay` caches a `NavEntry` per route, so its content lambda is captured
 * once and anything read in an enclosing scope and passed down is frozen at that first composition
 * — the screen then only refreshes when the entry is rebuilt by navigating. Reading
 * [DeviceController] here subscribes the entry's own recompose scope, so live BLE updates land.
 */
@Composable
fun Navigation(
    onScanClick: () -> Unit,
    onDeviceClick: (BleManager.BleDevice) -> Unit,
    onForgetBottle: () -> Unit,
    onScaleScanClick: () -> Unit,
    onScaleDeviceClick: (ScaleBleManager.ScaleBleDevice) -> Unit,
    onForgetScale: () -> Unit,
) {
    // Home is always the root. Devices are powered off most of the time, so gating the landing
    // screen on a live connection would strand the user on a device-picker almost every launch.
    val backStack = rememberNavBackStack<Route>(Route.Home)

    MainNavigation(backStack) {
        entry<Route.Home>(SiblingPage()) {
            HomeEntry(
                onForgetBottle = onForgetBottle,
                onForgetScale = onForgetScale,
                onOpenDevices = { backStack.add(Route.Devices) },
            )
        }
        entry<Route.Devices>(SiblingPage()) {
            DevicesEntry(
                onScanClick = onScanClick,
                onDeviceClick = onDeviceClick,
                onScaleScanClick = onScaleScanClick,
                onScaleDeviceClick = onScaleDeviceClick,
                onNavigateBack = if (backStack.backStack.size > 1) {
                    { backStack.pop() }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun HomeEntry(
    onForgetBottle: () -> Unit,
    onForgetScale: () -> Unit,
    onOpenDevices: () -> Unit,
) {
    HomePage(
        bottlePaired = DeviceController.bottlePaired.value,
        bottleLink = DeviceController.bottleLink.value,
        bottleConnectionState = DeviceController.connectionState.value,
        tempC = DeviceController.waterTempC.value,
        tds = DeviceController.tds.value,
        batteryPct = DeviceController.batteryPct.value,
        charging = DeviceController.charging.value,
        volumePct = DeviceController.bottleVolumePct.value,
        lastUpdatedMillis = DeviceController.bottleLastUpdated.value,
        scalePaired = DeviceController.scalePaired.value,
        scaleLink = DeviceController.scaleLink.value,
        scaleConnectionState = DeviceController.scaleConnectionState.value,
        scaleUserSlot = DeviceController.scaleUserSlot.value,
        scaleSex = DeviceController.scaleSex.value,
        scaleAge = DeviceController.scaleAge.value,
        scaleHeight = DeviceController.scaleHeight.value,
        scaleAthlete = DeviceController.scaleAthlete.value,
        onScaleSexChange = {
            DeviceController.scaleSex.value = it
            DeviceController.recalcScaleMetrics()
        },
        onScaleAgeChange = {
            DeviceController.scaleAge.value = it
            DeviceController.recalcScaleMetrics()
        },
        onScaleHeightChange = {
            DeviceController.scaleHeight.value = it
            DeviceController.recalcScaleMetrics()
        },
        onScaleAthleteChange = {
            DeviceController.scaleAthlete.value = it
            DeviceController.recalcScaleMetrics()
        },
        onForgetBottle = onForgetBottle,
        onForgetScale = onForgetScale,
        onResetScale = { DeviceController.requestScaleReset() },
        onOpenDevices = onOpenDevices,
    )
}

@Composable
private fun DevicesEntry(
    onScanClick: () -> Unit,
    onDeviceClick: (BleManager.BleDevice) -> Unit,
    onScaleScanClick: () -> Unit,
    onScaleDeviceClick: (ScaleBleManager.ScaleBleDevice) -> Unit,
    onNavigateBack: (() -> Unit)?,
) {
    DevicesPage(
        scanning = DeviceController.scanning.value,
        discoveredDevices = DeviceController.discoveredDevices,
        scaleScanning = DeviceController.scaleScanning.value,
        scaleDevices = DeviceController.scaleDevices,
        onScanClick = onScanClick,
        onDeviceClick = onDeviceClick,
        onScaleScanClick = onScaleScanClick,
        onScaleDeviceClick = onScaleDeviceClick,
        onNavigateBack = onNavigateBack,
    )
}
