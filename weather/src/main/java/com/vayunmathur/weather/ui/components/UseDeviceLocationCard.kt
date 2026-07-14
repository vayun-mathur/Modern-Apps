package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R

/**
 * Direct port of WeatherMaster's `UseDeviceLocationCard`. Pill / extraLarge
 * Surface with a `surfaceBright` background. ListItem: 52 dp circle leading
 * (`outline_my_location_24` or a spinner while loading), "Use current
 * location" headline, descriptive supporting text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UseDeviceLocationCard(onClick: () -> Unit, isLoading: Boolean = false) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(28.dp)),
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.outline_my_location_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            content = {
                Text("Use current location", color = MaterialTheme.colorScheme.onSurface)
            },
            supportingContent = {
                Text(
                    "Detect your device's current location automatically and add it to your list",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}
