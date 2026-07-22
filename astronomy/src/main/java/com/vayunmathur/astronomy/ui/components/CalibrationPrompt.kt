package com.vayunmathur.astronomy.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.hardware.SensorManager
import com.vayunmathur.astronomy.domain.sensor.DeviceOrientation
import com.vayunmathur.library.ui.*

/**
 * Small banner that prompts figure-8 calibration when magnetometer accuracy is low.
 * Plan Phase 3 polish: geomagnetic declination varies ~±15°, compass needs true north.
 */
@Composable
fun CalibrationPrompt(
    orientation: DeviceOrientation?,
    modifier: Modifier = Modifier
) {
    val accuracy = orientation?.accuracy ?: SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    val isLow = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
        accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW

    if (isLow && orientation != null) {
        Card(
            modifier = modifier.padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Row(
                Modifier.padding(12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconInfo()
                Column(Modifier.weight(1f)) {
                    Text("Compass needs calibration", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Move your device in a figure-8 to improve true-north alignment (declination ${orientation.declinationDeg.format(1)}°).",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun Double.format(d: Int) = "%.${d}f".format(this)
