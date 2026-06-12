package com.vayunmathur.launcher.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun AppContextMenu(
    expanded: Boolean,
    packageName: String,
    onDismiss: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("App Info") },
            onClick = {
                onDismiss()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                context.startActivity(intent)
            }
        )
        DropdownMenuItem(
            text = { Text("Uninstall") },
            onClick = {
                onDismiss()
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$packageName")
                }
                context.startActivity(intent)
            }
        )
        DropdownMenuItem(
            text = { Text("Remove") },
            onClick = {
                onDismiss()
                onRemove()
            }
        )
    }
}
