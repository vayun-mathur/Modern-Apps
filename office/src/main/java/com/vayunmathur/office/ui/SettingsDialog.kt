package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.office.R

import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.save
@Composable
fun SettingsDialog(
    autoSave: Boolean,
    autoSaveInterval: Int,
    defaultFontSize: Float,
    documentThemeMode: com.vayunmathur.office.platform.OfficeViewModel.DocumentThemeMode =
        com.vayunmathur.office.platform.OfficeViewModel.DocumentThemeMode.UNCHANGED,
    onSave: (
        autoSave: Boolean,
        interval: Int,
        fontSize: Float,
        documentThemeMode: com.vayunmathur.office.platform.OfficeViewModel.DocumentThemeMode) -> Unit = { _, _, _, _ -> },
    onDismiss: () -> Unit
) {
    var autoSaveEnabled by remember { mutableStateOf(autoSave) }
    var interval by remember { mutableStateOf(autoSaveInterval.toString()) }
    var fontSize by remember { mutableFloatStateOf(defaultFontSize) }
    var themeMode by remember { mutableStateOf(documentThemeMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiR.string.settings)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.auto_save), modifier = Modifier.weight(1f))
                    TextButton(onClick = { autoSaveEnabled = !autoSaveEnabled }) {
                        Text(if (autoSaveEnabled) stringResource(R.string.on) else stringResource(UiR.string.off),
                            color = if (autoSaveEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (autoSaveEnabled) {
                    TextField(value = interval, onValueChange = { interval = it },
                        label = { Text(stringResource(R.string.interval_seconds)) }, singleLine = true)
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.default_font_size_pt, fontSize.toInt()))
                com.vayunmathur.library.ui.Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    valueRange = 8f..48f)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.document_theme), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.document_theme_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.document_theme_unchanged),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.RadioButton(selected = themeMode == com.vayunmathur.office.platform.OfficeViewModel.DocumentThemeMode.UNCHANGED, onClick = { themeMode = com.vayunmathur.office.platform.OfficeViewModel.DocumentThemeMode.UNCHANGED })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.document_theme_follow_system),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.RadioButton(selected = themeMode == com.vayunmathur.office.platform.OfficeViewModel.DocumentThemeMode.FOLLOW_SYSTEM, onClick = { themeMode = com.vayunmathur.office.platform.OfficeViewModel.DocumentThemeMode.FOLLOW_SYSTEM })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(autoSaveEnabled, interval.toIntOrNull() ?: 60, fontSize, themeMode)
                onDismiss()
            }) { Text(stringResource(UiR.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } }
    )
}

// Backwards-compatible overload for callers that haven't migrated to documentThemeMode yet
@Composable
fun SettingsDialog(
    autoSave: Boolean,
    autoSaveInterval: Int,
    defaultFontSize: Float,
    onSave: (autoSave: Boolean, interval: Int, fontSize: Float) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsDialog(
        autoSave = autoSave,
        autoSaveInterval = autoSaveInterval,
        defaultFontSize = defaultFontSize,
        documentThemeMode = com.vayunmathur.office.platform.OfficeViewModel.DocumentThemeMode.UNCHANGED,
        onSave = { a, i, f, _ -> onSave(a, i, f) },
        onDismiss = onDismiss
    )
}
