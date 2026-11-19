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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.platform.MotionPhotoDuration
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text

/**
 * Post-processing settings rows owned by [SettingsPage]: the noise-reduction and
 * edge-sharpening toggles (issue #662) plus the Motion-Photo clip-length picker
 * (issue #712). Stateless: the caller passes values plus change callbacks.
 */
@Composable
fun CameraPostProcessingSettings(
    noiseReductionEnabled: Boolean,
    edgeSharpeningEnabled: Boolean,
    motionPhotoDuration: MotionPhotoDuration,
    onNoiseReductionChange: (Boolean) -> Unit,
    onEdgeSharpeningChange: (Boolean) -> Unit,
    onMotionDurationChange: (MotionPhotoDuration) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SettingsSection(title = stringResource(R.string.settings_noise_reduction)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_noise_reduction_description),
                checked = noiseReductionEnabled,
                onCheckedChange = onNoiseReductionChange,
            )
        }

        SettingsSection(title = stringResource(R.string.settings_edge_sharpening)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_edge_sharpening_description),
                checked = edgeSharpeningEnabled,
                onCheckedChange = onEdgeSharpeningChange,
            )
        }

        SettingsSection(title = stringResource(R.string.settings_motion_duration)) {
            // Built from [OutlinedButton] + [DropdownMenu] like the codec/audio pickers
            // in SettingsPage, rather than the library's `ExposedDropdownMenu` wrapper,
            // which currently recurses into itself.
            var motionExpanded by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                OutlinedButton(
                    onClick = { motionExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        motionDurationLabel(motionPhotoDuration.seconds),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconArrowDropDown()
                }
                DropdownMenu(
                    expanded = motionExpanded,
                    onDismissRequest = { motionExpanded = false }
                ) {
                    MotionPhotoDuration.entries.forEach { duration ->
                        SelectableDropdownMenuItem(
                            selected = duration == motionPhotoDuration,
                            onClick = {
                                onMotionDurationChange(duration)
                                motionExpanded = false
                            },
                            text = {
                                Text(motionDurationLabel(duration.seconds))
                            },
                            selectedLeadingIcon = { IconCheck() },
                        )
                    }
                }
            }
        }
    }
}

/** Localized label for a Motion-Photo clip length ("1 second" / "3 seconds"). */
@Composable
private fun motionDurationLabel(seconds: Int): String =
    pluralStringResource(R.plurals.motion_duration, seconds, seconds)
