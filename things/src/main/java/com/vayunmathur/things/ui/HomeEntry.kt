package com.vayunmathur.things.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.things.platform.DeviceController
import com.vayunmathur.things.platform.recalcScaleMetrics
import com.vayunmathur.things.platform.requestScaleReset

@Composable
internal fun HomeEntry(
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

