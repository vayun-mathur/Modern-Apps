package com.vayunmathur.launcher.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.data.DockItem

@Composable
fun DockBar(
    dockItems: List<DockItem>,
    getIcon: (String) -> Drawable?,
    onAppClick: (DockItem) -> Unit,
    onAppLongClick: (DockItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(24.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        dockItems.forEach { item ->
            val icon = getIcon(item.packageName)
            if (icon != null) {
                AppIcon(
                    name = "",
                    icon = icon,
                    onClick = { onAppClick(item) },
                    onLongClick = { onAppLongClick(item) },
                    labelColor = Color.Transparent,
                    modifier = Modifier.width(56.dp)
                )
            }
        }
    }
}
