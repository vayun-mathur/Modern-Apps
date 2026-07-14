package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.util.WeatherCondition
import com.vayunmathur.weather.util.weatherConditionForCode

/**
 * Direct port of WeatherMaster's `LocationItem`. Pill or extraLarge
 * `Surface` (selected = extraLarge + primaryContainer; otherwise =
 * CircleShape + surfaceBright). `combinedClickable` on the surface for
 * tap (select) + long-press (open delete sheet). Leading: 52 dp
 * `surfaceContainer` circle with a weather icon. Trailing: small device
 * marker icon when this row is the device-location row.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LocationItem(
    location: SavedLocation,
    description: String,
    currentWeatherCode: Int?,
    isDay: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val containerColor =
        if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceBright
    val contentColor =
        if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    val shape = if (isSelected) RoundedCornerShape(28.dp) else CircleShape

    val condition = currentWeatherCode?.let { weatherConditionForCode(it) } ?: WeatherCondition.Unknown

    Surface(
        modifier = modifier
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        color = containerColor,
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = containerColor),
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    WeatherIconBox(
                        iconRes = condition.iconRes(isDay),
                        size = 34.dp,
                        tint = contentColor,
                    )
                }
            },
            content = { Text(location.name, color = contentColor) },
            supportingContent = {
                Text(
                    description,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                if (dragHandle != null) {
                    dragHandle()
                } else if (location.isCurrent) {
                    Icon(
                        painter = painterResource(R.drawable.outline_my_location_24),
                        contentDescription = "Current device location",
                        tint = contentColor,
                    )
                }
            },
        )
    }
}
