package com.vayunmathur.astronomy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.vayunmathur.astronomy.domain.projection.ViewState
import com.vayunmathur.astronomy.domain.sensor.DeviceOrientation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlin.math.roundToInt

@Composable
fun CompassOverlay(viewState: ViewState, deviceOrientation: DeviceOrientation?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Az ${viewState.centerAzRad.toDegLabel()}", style = MaterialTheme.typography.labelMedium)
        Text("Alt ${viewState.centerAltRad.toDegLabel()}", style = MaterialTheme.typography.labelMedium)
        Text("FOV ${viewState.fovDeg.toInt()}°", style = MaterialTheme.typography.labelMedium)
        if (deviceOrientation != null) {
            // Show both compass bearing and screen-normal pointing + altitude (full 3-axis)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Compass ${deviceOrientation.azimuthTrueDeg.roundToInt()}° true", style = MaterialTheme.typography.labelSmall)
                Text("Point Az ${deviceOrientation.pointingAzTrueDeg.roundToInt()}° Alt ${deviceOrientation.pointingAltDeg.roundToInt()}°", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun Double.toDegLabel(): String = "${Math.toDegrees(this).roundToInt()}°"
