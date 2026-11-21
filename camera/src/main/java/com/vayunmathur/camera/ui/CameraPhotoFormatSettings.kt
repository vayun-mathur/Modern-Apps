package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.platform.PhotoFormat
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text

/**
 * Photo-format picker owned by [SettingsPage]: JPEG vs AVIF stills,
 * following the dropdown pattern of [CameraCodecSettings]. Stateless:
 * the caller passes the value plus the change callback.
 */
@Composable
fun CameraPhotoFormatSettings(
    photoFormat: PhotoFormat,
    onPhotoFormatChange: (PhotoFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSection(title = stringResource(R.string.settings_photo_format), modifier = modifier) {
        var expanded by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "${stringResource(photoFormat.labelRes)} — ${stringResource(photoFormat.descriptionRes)}",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconArrowDropDown()
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                PhotoFormat.entries.forEach { format ->
                    SelectableDropdownMenuItem(
                        selected = format == photoFormat,
                        onClick = {
                            onPhotoFormatChange(format)
                            expanded = false
                        },
                        text = {
                            Column {
                                Text(stringResource(format.labelRes))
                                Text(
                                    stringResource(format.descriptionRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        selectedLeadingIcon = { IconCheck() },
                    )
                }
            }
        }
    }
}
