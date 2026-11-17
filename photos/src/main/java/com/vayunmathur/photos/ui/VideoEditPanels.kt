package com.vayunmathur.photos.ui
import androidx.annotation.OptIn

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconContentCut
import com.vayunmathur.library.ui.IconCrop
import com.vayunmathur.library.ui.IconFlip
import com.vayunmathur.library.ui.IconRotateLeft
import com.vayunmathur.library.ui.IconRotateRight
import com.vayunmathur.library.ui.IconTune
import com.vayunmathur.library.ui.IconVolumeOff
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.RangeSlider
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.data.VideoFilterPreset
import com.vayunmathur.photos.data.VideoTool
import com.vayunmathur.photos.platform.VideoEditViewModel
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
@Composable
internal fun ToolPanel(vm: VideoEditViewModel, state: VideoEditState, tool: VideoTool) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF121212))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        when (tool) {
            VideoTool.Trim -> TrimPanel(vm, state)
            VideoTool.CropRotate -> CropRotatePanel(vm, state)
            VideoTool.Audio -> AudioPanel(vm, state)
            VideoTool.Filters -> FiltersPanel(vm, state)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TrimPanel(vm: VideoEditViewModel, state: VideoEditState) {
    val duration = state.durationMs.coerceAtLeast(1L)
    var range by remember(state.durationMs) {
        mutableStateOf(
            state.trimStartMs.toFloat()..(if (state.trimEndMs > 0L) state.trimEndMs else duration).toFloat()
        )
    }
    Text(stringResource(R.string.video_trim), color = Color.White, style = MaterialTheme.typography.titleSmall)
    RangeSlider(
        value = range,
        onValueChange = { range = it },
        onValueChangeFinished = { vm.setTrim(range.start.toLong(), range.endInclusive.toLong()) },
        valueRange = 0f..duration.toFloat(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatVideoTime(range.start.toLong()), color = Color.White, style = MaterialTheme.typography.labelMedium)
        Text(formatVideoTime(range.endInclusive.toLong()), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun CropRotatePanel(vm: VideoEditViewModel, state: VideoEditState) {
    Text(stringResource(R.string.tool_crop_rotate), color = Color.White, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.rotateLeft() }) { IconRotateLeft(tint = Color.White) }
        IconButton(onClick = { vm.rotateRight() }) { IconRotateRight(tint = Color.White) }
        IconButton(onClick = { vm.toggleFlip() }) {
            IconFlip(tint = if (state.flipHorizontal) MaterialTheme.colorScheme.primary else Color.White)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { vm.clearCrop() }) { Text(stringResource(UiR.string.reset)) }
    }
    Text(
        stringResource(R.string.video_crop_hint),
        color = Color.LightGray,
        style = MaterialTheme.typography.labelSmall,
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun AudioPanel(vm: VideoEditViewModel, state: VideoEditState) {
    Text(stringResource(R.string.video_audio), color = Color.White, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(onClick = { vm.setMuted(!state.muted) }) {
        if (state.muted) IconVolumeOff() else IconVolumeUp()
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (state.muted) R.string.video_muted else R.string.video_audio_on))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun FiltersPanel(vm: VideoEditViewModel, state: VideoEditState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VideoFilterPreset.entries.forEach { preset ->
            val selected = state.filterPreset == preset
            if (selected) {
                Button(onClick = { vm.setFilterPreset(preset) }) { Text(presetLabel(preset)) }
            } else {
                OutlinedButton(onClick = { vm.setFilterPreset(preset) }) { Text(presetLabel(preset)) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    LabeledSlider(stringResource(R.string.adj_brightness), state.brightness) { vm.setBrightness(it) }
    LabeledSlider(stringResource(R.string.adj_contrast), state.contrast) { vm.setContrast(it) }
    LabeledSlider(stringResource(R.string.adj_saturation), state.saturation) { vm.setSaturation(it) }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = -1f..1f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun presetLabel(preset: VideoFilterPreset): String = when (preset) {
    VideoFilterPreset.None -> stringResource(R.string.video_filter_none)
    VideoFilterPreset.Mono -> stringResource(R.string.video_filter_mono)
    VideoFilterPreset.Warm -> stringResource(R.string.video_filter_warm)
    VideoFilterPreset.Cool -> stringResource(R.string.video_filter_cool)
    VideoFilterPreset.Vivid -> stringResource(R.string.video_filter_vivid)
}

@Composable
internal fun ToolTabs(selected: VideoTool, onSelect: (VideoTool) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ToolTab(VideoTool.Trim, selected, onSelect, stringResource(R.string.video_trim)) {
            IconContentCut(tint = it)
        }
        ToolTab(VideoTool.CropRotate, selected, onSelect, stringResource(R.string.tool_crop_rotate)) {
            IconCrop(tint = it)
        }
        ToolTab(VideoTool.Audio, selected, onSelect, stringResource(R.string.video_audio)) {
            IconVolumeUp(tint = it)
        }
        ToolTab(VideoTool.Filters, selected, onSelect, stringResource(R.string.tool_filters)) {
            IconTune(tint = it)
        }
    }
}

@Composable
private fun ToolTab(
    tool: VideoTool,
    selected: VideoTool,
    onSelect: (VideoTool) -> Unit,
    label: String,
    icon: @Composable (Color) -> Unit,
) {
    val tint = if (tool == selected) MaterialTheme.colorScheme.primary else Color.White
    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = { onSelect(tool) }) { icon(tint) }
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}
