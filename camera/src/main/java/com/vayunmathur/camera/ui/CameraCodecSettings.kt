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
import com.vayunmathur.camera.platform.camera2.AudioInputDeviceOption
import com.vayunmathur.camera.platform.camera2.AudioInputDeviceSelection
import com.vayunmathur.camera.platform.CodecSupport
import com.vayunmathur.camera.platform.VideoCodec
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text

/**
 * Codec and audio-device pickers owned by [SettingsPage]: the video codec
 * and audio input device dropdowns. The device picker lists physical
 * microphones (only real connected devices; no synthetic default row); there
 * is no AudioSource-type or audio-codec picker (recording audio is always
 * Opus from the CAMCORDER source). Stateless: the caller passes values
 * plus change callbacks.
 */
@Composable
fun CameraCodecSettings(
    videoCodec: VideoCodec,
    audioInputDevice: AudioInputDeviceSelection,
    audioInputDeviceOptions: List<AudioInputDeviceOption>,
    onVideoCodecChange: (VideoCodec) -> Unit,
    onAudioInputDeviceChange: (AudioInputDeviceSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        val availableCodecs = remember {
            buildList {
                add(VideoCodec.AVC)
                if (CodecSupport.isHevcEncoderAvailable) add(VideoCodec.HEVC)
                if (CodecSupport.isHardwareAv1EncoderAvailable) add(VideoCodec.AV1)
            }
        }
        if (availableCodecs.size > 1) {
            SettingsSection(title = stringResource(R.string.settings_video_codec)) {
                // Built from [OutlinedButton] + [DropdownMenu] rather than the library's
                // `ExposedDropdownMenu` wrapper, which currently recurses into itself.
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
                            "${stringResource(videoCodec.labelRes)} — ${stringResource(videoCodec.descriptionRes)}",
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
                        availableCodecs.forEach { codec ->
                            SelectableDropdownMenuItem(
                                selected = codec == videoCodec,
                                onClick = {
                                    onVideoCodecChange(codec)
                                    expanded = false
                                },
                                text = {
                                    Column {
                                        Text(stringResource(codec.labelRes))
                                        Text(
                                            stringResource(codec.descriptionRes),
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

        SettingsSection(title = stringResource(R.string.settings_audio_input_device)) {
            // Built from [OutlinedButton] + [DropdownMenu] rather than the library's
            // `ExposedDropdownMenu` wrapper, which currently recurses into itself.
            var audioExpanded by remember { mutableStateOf(false) }
            // Only real devices are listed; a chosen device that is no
            // longer connected (or no explicit choice yet) selects no row.
            val selectedOption = audioInputDeviceOptions.firstOrNull {
                it.selection == audioInputDevice
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                OutlinedButton(
                    onClick = { audioExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        selectedOption?.let {
                            listOfNotNull(it.label, it.description).joinToString(" — ")
                        } ?: stringResource(R.string.settings_audio_input_device_unselected),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconArrowDropDown()
                }
                DropdownMenu(
                    expanded = audioExpanded,
                    onDismissRequest = { audioExpanded = false }
                ) {
                    audioInputDeviceOptions.forEach { option ->
                        SelectableDropdownMenuItem(
                            selected = option.selection == selectedOption?.selection,
                            onClick = {
                                onAudioInputDeviceChange(option.selection)
                                audioExpanded = false
                            },
                            text = {
                                Column {
                                    Text(option.label)
                                    option.description?.let { description ->
                                        Text(
                                            description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            selectedLeadingIcon = { IconCheck() },
                        )
                    }
                }
            }
        }
    }
}
