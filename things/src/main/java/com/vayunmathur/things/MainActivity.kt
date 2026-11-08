package com.vayunmathur.things

import android.Manifest
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.IconBluetooth
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.things.platform.DeviceController
import com.vayunmathur.things.platform.DeviceService
import com.vayunmathur.things.platform.HealthConnectHelper

class MainActivity : MaActivity() {

    override fun onCreateBeforeContent() {
        DeviceController.init(applicationContext)
        // The service keeps the link alive in the background and drives auto-connect; in the
        // foreground reconnect still works because init() already built the managers.
        if (DeviceController.hasRememberedDevice()) DeviceService.start(this)
    }

    @Composable
    override fun Content() {
        // First-run gate: without Bluetooth the app cannot read anything
        // and without Health Connect it has nowhere to put what it reads.
        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = stringResource(R.string.permissions_title),
                subtitle = stringResource(R.string.permissions_rationale),
                icon = { IconBluetooth() },
                requirements = bleRequirements() +
                    PermissionRequirement.HealthConnect(HealthConnectHelper.requiredPermissions)
            )
        ) {
            Navigation(
                onScanClick = { DeviceController.startBottleScan() },
                onDeviceClick = {
                    DeviceService.start(this@MainActivity)
                    DeviceController.connectBottle(it.address)
                },
                onForgetBottle = {
                    DeviceController.disconnectBottle()
                    if (!DeviceController.hasRememberedDevice()) DeviceService.stop(this@MainActivity)
                },
                onScaleScanClick = { DeviceController.startScaleScan() },
                onScaleDeviceClick = {
                    DeviceService.start(this@MainActivity)
                    DeviceController.connectScale(it.address)
                },
                onForgetScale = {
                    DeviceController.disconnectScale()
                    if (!DeviceController.hasRememberedDevice()) DeviceService.stop(this@MainActivity)
                },
            )
        }
    }

    private companion object {
        fun bleRequirements(): List<PermissionRequirement> = buildList {
            add(
                PermissionRequirement.Runtime(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    )
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionRequirement.notifications())
            }
        }
    }
}
