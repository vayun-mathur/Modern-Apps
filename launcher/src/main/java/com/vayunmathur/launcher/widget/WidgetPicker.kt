package com.vayunmathur.launcher.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.Drawable
import com.vayunmathur.launcher.util.toImageBitmap
import android.os.Process
import android.os.UserManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

data class WidgetOption(
    val info: AppWidgetProviderInfo,
    val label: String,
    val icon: Drawable?,
    val appName: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetPicker(
    onDismiss: () -> Unit,
    onWidgetSelected: (AppWidgetProviderInfo) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    var filterQuery by remember { mutableStateOf("") }

    val densityDpi = context.resources.displayMetrics.densityDpi

    val allWidgets = remember {
        val manager = AppWidgetManager.getInstance(context)
        val pm = context.packageManager
        val userManager = context.getSystemService(UserManager::class.java)
        val profiles = userManager?.userProfiles ?: listOf(Process.myUserHandle())

        profiles.flatMap { profile ->
            manager.getInstalledProvidersForProfile(profile)
        }
            .distinctBy { it.provider.flattenToString() }
            .map { info ->
                WidgetOption(
                    info = info,
                    label = info.loadLabel(pm),
                    icon = info.loadPreviewImage(context, densityDpi)
                        ?: info.loadIcon(context, densityDpi),
                    appName = info.provider.packageName.let { pkg ->
                        try {
                            pm.getApplicationLabel(
                                pm.getApplicationInfo(pkg, 0)
                            ).toString()
                        } catch (_: Exception) { pkg }
                    }
                )
            }
            .sortedBy { it.appName }
    }

    val filteredWidgets = remember(allWidgets, filterQuery) {
        val q = filterQuery.lowercase()
        allWidgets
            .filter { q.isBlank() || it.appName.lowercase().contains(q) || it.label.lowercase().contains(q) }
            .groupBy { it.appName }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Text(
            "Add Widget",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )

        OutlinedTextField(
            value = filterQuery,
            onValueChange = { filterQuery = it },
            placeholder = { Text("Search widgets…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp)
        )

        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            filteredWidgets.forEach { (appName, options) ->
                item {
                    Text(
                        appName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
                items(options) { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onWidgetSelected(option.info) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        option.icon?.let { drawable ->
                            val bmp = remember(drawable) { drawable.toImageBitmap() }
                            Image(
                                bitmap = bmp,
                                contentDescription = option.label,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                        }
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${option.info.minWidth / 80} × ${option.info.minHeight / 80} cells",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
