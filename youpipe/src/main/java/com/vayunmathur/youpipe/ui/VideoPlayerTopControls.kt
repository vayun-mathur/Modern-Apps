package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCast
import com.vayunmathur.library.ui.IconCastConnected
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.youpipe.R
import com.vayunmathur.library.ui.R as UiR
private fun Modifier.chipModifier(): Modifier =
    background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).size(32.dp)

/**
 * The row of picker chips drawn top-right over the video: quality, audio language,
 * captions, cast, chapters and the tempo/pitch menu.
 */
@Composable
internal fun VideoPlayerTopControls(
    hasAudio: Boolean,
    languages: List<String>,
    languageEntries: List<Pair<String, String>>,
    language: String,
    onLanguageChange: (String) -> Unit,
    isVideoMenuExpanded: Boolean,
    onVideoMenuExpandedChange: (Boolean) -> Unit,
    isLanguageMenuExpanded: Boolean,
    onLanguageMenuExpandedChange: (Boolean) -> Unit,
    isCaptionMenuExpanded: Boolean,
    onCaptionMenuExpandedChange: (Boolean) -> Unit,
    isSpeedMenuExpanded: Boolean,
    onSpeedMenuExpandedChange: (Boolean) -> Unit,
    currentVideoStream: VideoStream,
    onVideoStreamChange: (VideoStream) -> Unit,
    videoStreams: List<VideoStream>,
    subtitles: List<SubtitleTrack>,
    selectedSubtitle: SubtitleTrack?,
    onSubtitleChange: (SubtitleTrack?) -> Unit,
    segments: List<VideoChapter>,
    onChapterMenuVisibleChange: (Boolean) -> Unit,
    playbackSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    playbackPitch: Float,
    onPitchChange: (Float) -> Unit,
    unhookPitch: Boolean,
    onUnhookPitchChange: (Boolean) -> Unit,
    isCasting: Boolean,
    onCastClick: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Surface(
                onClick = { onVideoMenuExpandedChange(true) },
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Per user request: button shows only quality, codec only in dropdown
                    Text(text = currentVideoStream.quality, color = Color.White, style = MaterialTheme.typography.labelMedium)
                    IconArrowDropDown(tint = Color.White)
                }
            }
            DropdownMenu(expanded = isVideoMenuExpanded, onDismissRequest = { onVideoMenuExpandedChange(false) }) {
                videoStreams.forEach { stream ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.video_quality_fps_codec, stream.quality, stream.fps, getVideoCodecName(stream.codec))) },
                        onClick = { onVideoStreamChange(stream); onVideoMenuExpandedChange(false) }
                    )
                }
            }
        }

        if (hasAudio) {
            if (languages.size > 1) {
                Box {
                    Surface(
                        onClick = { onLanguageMenuExpandedChange(true) },
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            val displayLabel = languageEntries.find { it.first == language }?.second ?: language
                            Text(text = displayLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
                            IconArrowDropDown(tint = Color.White)
                        }
                    }
                    DropdownMenu(expanded = isLanguageMenuExpanded, onDismissRequest = { onLanguageMenuExpandedChange(false) }) {
                        languageEntries.forEach { (code, display) ->
                            DropdownMenuItem(
                                text = { Text(display) },
                                onClick = { onLanguageChange(code); onLanguageMenuExpandedChange(false) }
                            )
                        }
                    }
                }
            }
        }

        if (subtitles.isNotEmpty()) {
            Box {
                Surface(
                    onClick = { onCaptionMenuExpandedChange(true) },
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = selectedSubtitle?.languageTag?.ifEmpty { stringResource(R.string.cc) } ?: stringResource(R.string.cc), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        IconArrowDropDown(tint = Color.White)
                    }
                }
                DropdownMenu(expanded = isCaptionMenuExpanded, onDismissRequest = { onCaptionMenuExpandedChange(false) }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(UiR.string.off)) },
                        onClick = { onSubtitleChange(null); onCaptionMenuExpandedChange(false) }
                    )
                    subtitles.forEach { sub ->
                        DropdownMenuItem(
                            text = { Text(sub.displayName) },
                            onClick = { onSubtitleChange(sub); onCaptionMenuExpandedChange(false) }
                        )
                    }
                }
            }
        }

        // Always visible, whether or not Cast is installed: an icon that appears only when
        // it would work is an icon nobody discovers.
        IconButton(
            onClick = onCastClick,
            modifier = Modifier.chipModifier()
        ) {
            if (isCasting) {
                IconCastConnected(tint = Color.White, modifier = Modifier.size(20.dp))
            } else {
                IconCast(tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        if (segments.isNotEmpty()) {
            IconButton(
                onClick = { onChapterMenuVisibleChange(true) },
                modifier = Modifier.chipModifier()
            ) {
                IconList(tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Box {
            Surface(
                onClick = { onSpeedMenuExpandedChange(true) },
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.playback_tempo_value, formatTempo(playbackSpeed)),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                    IconArrowDropDown(tint = Color.White)
                }
            }
            DropdownMenu(expanded = isSpeedMenuExpanded, onDismissRequest = { onSpeedMenuExpandedChange(false) }) {
                Column(modifier = Modifier.fillMaxWidth(0.85f).widthIn(max = 320.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = stringResource(R.string.playback_tempo), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = stringResource(R.string.playback_tempo_value, formatTempo(playbackSpeed)),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Slider(
                        value = playbackSpeed,
                        onValueChange = onSpeedChange,
                        valueRange = 0.25f..2f,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = stringResource(R.string.playback_pitch), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = stringResource(R.string.playback_pitch_value, (playbackPitch * 100).toInt()),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Slider(
                        value = playbackPitch,
                        onValueChange = onPitchChange,
                        valueRange = 0.5f..2f,
                        enabled = unhookPitch,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onUnhookPitchChange(!unhookPitch)
                                if (unhookPitch) onPitchChange(playbackSpeed)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = unhookPitch,
                            onCheckedChange = {
                                onUnhookPitchChange(it)
                                if (!it) onPitchChange(playbackSpeed)
                            },
                        )
                        Text(text = stringResource(R.string.playback_unhook_pitch), style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = {
                            onSpeedChange(1f)
                            onUnhookPitchChange(false)
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text(stringResource(UiR.string.reset)) }
                }
            }
        }
    }
}
