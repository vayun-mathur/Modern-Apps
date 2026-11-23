package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.IconMyLocation
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R
import androidx.compose.ui.res.stringResource

/**
 * Direct port of WeatherMaster's `UseDeviceLocationCard`. Pill / extraLarge
 * Surface with a `surfaceBright` background. ListItem: 52 dp circle leading
 * (a my-location icon or a spinner while loading), "Use current
 * location" headline, descriptive supporting text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UseDeviceLocationCard(onClick: () -> Unit, isLoading: Boolean = false) {
    Surface(
        modifier = Modifier.clip(MaterialTheme.shapes.extraLarge),
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
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
                        Text("Loading\u2026", style = MaterialTheme.typography.labelSmall)
                    } else {
                        IconMyLocation(
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            content = {
                Text(stringResource(R.string.use_current_location), color = MaterialTheme.colorScheme.onSurface)
            },
            supportingContent = {
                Text(
                    stringResource(R.string.detect_your_device_s_current_location_au),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}
