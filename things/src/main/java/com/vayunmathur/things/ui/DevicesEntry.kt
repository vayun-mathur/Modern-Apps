package com.vayunmathur.things.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.things.platform.BleManager
import com.vayunmathur.things.platform.DeviceController
import com.vayunmathur.things.platform.ScaleBleManager

@Composable
internal fun DevicesEntry(
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
