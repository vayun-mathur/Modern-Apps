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
 * edge-sharpening toggles (issue #662) plus the single Motion Photo dropdown
 * (Off or a clip length, issue #712). Stateless: the caller passes values plus
 * change callbacks.
 *
 * [motionPhotoBlockedByAvif] is an effective AVIF selection: a Motion Photo is
 * a JPEG with an MP4 trailer, so the dropdown renders disabled showing the
 * forced Off state with that reason (its stored value is kept) and captures
 * stay plain stills.
 */
@Composable
fun CameraPostProcessingSettings(
    noiseReductionEnabled: Boolean,
    edgeSharpeningEnabled: Boolean,
    motionPhotoDuration: MotionPhotoDuration,
    motionPhotoBlockedByAvif: Boolean,
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

        SettingsSection(title = stringResource(R.string.settings_motion_photo)) {
            // Built from [OutlinedButton] + [DropdownMenu] like the codec/audio pickers
            // in SettingsPage, rather than the library's `ExposedDropdownMenu` wrapper,
            // which currently recurses into itself.
            // AVIF forces Off honestly: the button shows Off while blocked
            // (the stored choice is kept and returns when JPEG is selected).
            val displayed = if (motionPhotoBlockedByAvif) {
                MotionPhotoDuration.OFF
            } else {
                motionPhotoDuration
            }
            var motionExpanded by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                OutlinedButton(
                    onClick = { motionExpanded = true },
                    enabled = !motionPhotoBlockedByAvif,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        motionSettingLabel(displayed),
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
                            selected = duration == displayed,
                            onClick = {
                                onMotionDurationChange(duration)
                                motionExpanded = false
                            },
                            text = {
                                Text(motionSettingLabel(duration))
                            },
                            selectedLeadingIcon = { IconCheck() },
                        )
                    }
                }
            }
            if (motionPhotoBlockedByAvif) {
                Text(
                    stringResource(R.string.settings_motion_photo_requires_jpeg),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Localized label for one Motion Photo setting ("Off" / "1 second" / "3 seconds"). */
@Composable
private fun motionSettingLabel(setting: MotionPhotoDuration): String =
    if (setting.isEnabled) {
        pluralStringResource(R.plurals.motion_duration, setting.seconds, setting.seconds)
    } else {
        stringResource(R.string.settings_motion_photo_off)
    }
