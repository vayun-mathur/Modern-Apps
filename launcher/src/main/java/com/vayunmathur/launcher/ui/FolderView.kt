package com.vayunmathur.launcher.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.AppInfo
import com.vayunmathur.launcher.data.FolderInfo
import com.vayunmathur.launcher.data.HomeScreenItem

@Composable
fun FolderIcon(
    folder: FolderInfo,
    folderApps: List<HomeScreenItem>,
    getAppInfo: (String) -> AppInfo?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val previewApps = folderApps.take(4)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier.matchParentSize(),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                for (row in 0..1) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for (col in 0..1) {
                            val index = row * 2 + col
                            val app = previewApps.getOrNull(index)
                            val appInfo = app?.let { getAppInfo(it.packageName) }
                            if (appInfo != null) {
                                val bmp = remember(appInfo.icon) {
                                    val w = appInfo.icon.intrinsicWidth.coerceAtLeast(1)
                                    val h = appInfo.icon.intrinsicHeight.coerceAtLeast(1)
                                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(bitmap)
                                    appInfo.icon.setBounds(0, 0, w, h)
                                    appInfo.icon.draw(canvas)
                                    bitmap.asImageBitmap()
                                }
                                Image(
                                    bitmap = bmp,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            } else {
                                Box(Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = folder.title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
fun FolderDialog(
    folder: FolderInfo,
    folderApps: List<HomeScreenItem>,
    getAppInfo: (String) -> AppInfo?,
    onAppClick: (HomeScreenItem) -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(folder.title) }
    var isEditing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (isEditing) {
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true
                )
            } else {
                Text(
                    text = folder.title,
                    modifier = Modifier.clickable { isEditing = true }
                )
            }
        },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(folderApps) { item ->
                    val appInfo = getAppInfo(item.packageName)
                    if (appInfo != null) {
                        AppIcon(
                            name = appInfo.name,
                            icon = appInfo.icon,
                            onClick = { onAppClick(item) },
                            labelColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isEditing) {
                TextButton(onClick = {
                    onRename(title)
                    isEditing = false
                }) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
