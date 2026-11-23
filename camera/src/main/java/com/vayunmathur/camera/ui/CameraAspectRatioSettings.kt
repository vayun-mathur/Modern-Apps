package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.Box
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
import com.vayunmathur.camera.platform.AspectRatioOption
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text

/**
 * Photo aspect-ratio picker owned by [SettingsPage]: the explicit
 * choice list for the same selection the camera top bar cycles
 * (4:3 / 3:2 / 16:9 / 1:1 — the historical set; there was never a
 * "Full" entry, the sensor-native framing is the 4:3 choice on this
 * hardware). The selection drives both the preview box
 * (`previewAspectRatio` / `previewRatioForWindow`) and the saved
 * still's centre crop, so what the user frames is what lands.
 * Stateless: the caller passes the value plus the change callback,
 * following [CameraPhotoFormatSettings].
 */
@Composable
fun CameraAspectRatioSettings(
    aspectRatio: AspectRatioOption,
    onAspectRatioChange: (AspectRatioOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSection(title = stringResource(R.string.settings_aspect_ratio), modifier = modifier) {
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
                    aspectRatio.label,
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
                // Historical cycle order (4:3 -> 3:2 -> 16:9 -> 1:1).
                listOf(
                    AspectRatioOption.RATIO_4_3,
                    AspectRatioOption.RATIO_3_2,
                    AspectRatioOption.RATIO_16_9,
                    AspectRatioOption.RATIO_1_1,
                ).forEach { option ->
                    SelectableDropdownMenuItem(
                        selected = option == aspectRatio,
                        onClick = {
                            onAspectRatioChange(option)
                            expanded = false
                        },
                        text = { Text(option.label) },
                        selectedLeadingIcon = { IconCheck() },
                    )
                }
            }
        }
    }
}
