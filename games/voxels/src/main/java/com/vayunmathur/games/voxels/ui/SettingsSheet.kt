package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.voxels.R
import com.vayunmathur.games.voxels.data.GameSettings
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import kotlin.math.roundToInt

/**
 * In-game settings, opened from the HUD's gear button: look sensitivity, invert Y, music and effects
 * volume, HUD size and the debug readout. Every change applies immediately via [onChange].
 */
@Composable
fun SettingsSheet(settings: GameSettings, onChange: (GameSettings) -> Unit, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(vertical = 8.dp).widthIn(max = 460.dp).fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xF01A1E1A))
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.settings), color = Color.White, style = MaterialTheme.typography.titleLarge)
            SliderRow(stringResource(R.string.settings_look_sensitivity), settings.lookSensitivity,
                GameSettings.MIN_SENSITIVITY..GameSettings.MAX_SENSITIVITY) { onChange(settings.copy(lookSensitivity = it)) }
            SwitchRow(stringResource(R.string.settings_invert_y), settings.invertY) { onChange(settings.copy(invertY = it)) }
            SwitchRow(stringResource(R.string.settings_stick_look), settings.stickLook) { onChange(settings.copy(stickLook = it)) }
            SliderRow(stringResource(R.string.settings_music_volume), settings.musicVolume, 0f..1f) { onChange(settings.copy(musicVolume = it)) }
            SliderRow(stringResource(R.string.settings_sfx_volume), settings.sfxVolume, 0f..1f) { onChange(settings.copy(sfxVolume = it)) }
            SliderRow(stringResource(R.string.settings_hud_scale), settings.hudScale,
                GameSettings.MIN_HUD_SCALE..GameSettings.MAX_HUD_SCALE) { onChange(settings.copy(hudScale = it)) }
            SwitchRow(stringResource(R.string.settings_show_debug), settings.showDebug) { onChange(settings.copy(showDebug = it)) }
            Button(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.settings_done)) }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Color.White, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", color = Color.White.copy(alpha = 0.7f))
        }
        Slider(value = value, onValueChange = onValue, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
