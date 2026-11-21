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
import com.vayunmathur.camera.platform.AudioCodec
import com.vayunmathur.camera.platform.AudioInputSource
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
 * Codec and audio-source pickers owned by [SettingsPage]: the video codec,
 * audio codec and audio input source dropdowns. Stateless: the caller passes
 * values plus change callbacks.
 */
@Composable
fun CameraCodecSettings(
    videoCodec: VideoCodec,
    audioCodec: AudioCodec,
    audioInputSource: AudioInputSource,
    onVideoCodecChange: (VideoCodec) -> Unit,
    onAudioCodecChange: (AudioCodec) -> Unit,
    onAudioInputSourceChange: (AudioInputSource) -> Unit,
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

        val availableAudioCodecs = remember {
            buildList {
                add(AudioCodec.AAC)
                if (CodecSupport.isOpusAudioEncoderAvailable) add(AudioCodec.OPUS)
            }
        }
        // A stored Opus choice on a device with no Opus encoder falls back to AAC in
        // the video session (CameraVideoSession); keep the row visible in that case so
        // the summary can name the effective codec instead of silently showing Opus.
        val opusUnavailable = audioCodec == AudioCodec.OPUS &&
            !CodecSupport.isOpusAudioEncoderAvailable
        val effectiveAudioCodec = if (opusUnavailable) AudioCodec.AAC else audioCodec
        if (availableAudioCodecs.size > 1 || opusUnavailable) {
            SettingsSection(title = stringResource(R.string.settings_audio_codec)) {
                // Built from [OutlinedButton] + [DropdownMenu] rather than the library's
                // `ExposedDropdownMenu` wrapper, which currently recurses into itself.
                var audioCodecExpanded by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    OutlinedButton(
                        onClick = { audioCodecExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (opusUnavailable) {
                                stringResource(
                                    R.string.audio_codec_fallback_summary,
                                    stringResource(effectiveAudioCodec.labelRes),
                                    stringResource(effectiveAudioCodec.descriptionRes),
                                    stringResource(audioCodec.labelRes),
                                )
                            } else {
                                "${stringResource(audioCodec.labelRes)} — ${stringResource(audioCodec.descriptionRes)}"
                            },
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconArrowDropDown()
                    }
                    DropdownMenu(
                        expanded = audioCodecExpanded,
                        onDismissRequest = { audioCodecExpanded = false }
                    ) {
                        availableAudioCodecs.forEach { codec ->
                            SelectableDropdownMenuItem(
                                selected = codec == effectiveAudioCodec,
                                onClick = {
                                    onAudioCodecChange(codec)
                                    audioCodecExpanded = false
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

        SettingsSection(title = stringResource(R.string.settings_audio_source)) {
            // Built from [OutlinedButton] + [DropdownMenu] rather than the library's
            // `ExposedDropdownMenu` wrapper, which currently recurses into itself.
            var audioExpanded by remember { mutableStateOf(false) }
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
                        "${stringResource(audioInputSource.labelRes)} — ${stringResource(audioInputSource.descriptionRes)}",
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
                    AudioInputSource.entries.forEach { source ->
                        SelectableDropdownMenuItem(
                            selected = source == audioInputSource,
                            onClick = {
                                onAudioInputSourceChange(source)
                                audioExpanded = false
                            },
                            text = {
                                Column {
                                    Text(stringResource(source.labelRes))
                                    Text(
                                        stringResource(source.descriptionRes),
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
}
