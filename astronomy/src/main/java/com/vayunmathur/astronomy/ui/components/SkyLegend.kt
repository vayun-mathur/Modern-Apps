package com.vayunmathur.astronomy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.vayunmathur.astronomy.ui.VisibleSky
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun SkyLegend(visibleSky: VisibleSky, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Stars: ${visibleSky.stars.size} Planets: ${visibleSky.planets.size} DSO: ${visibleSky.deepSky.size}", style = MaterialTheme.typography.labelSmall)
    }
}
