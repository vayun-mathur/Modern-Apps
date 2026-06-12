package com.vayunmathur.launcher.ui

import android.app.WallpaperManager
import android.content.Intent
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun HomeContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onWidgets: () -> Unit
) {
    val context = LocalContext.current

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Wallpapers") },
            onClick = {
                onDismiss()
                try {
                    context.startActivity(Intent(WallpaperManager.ACTION_CROP_AND_SET_WALLPAPER))
                } catch (_: Exception) {
                    context.startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER))
                }
            }
        )
        DropdownMenuItem(
            text = { Text("Widgets") },
            onClick = {
                onDismiss()
                onWidgets()
            }
        )
    }
}
