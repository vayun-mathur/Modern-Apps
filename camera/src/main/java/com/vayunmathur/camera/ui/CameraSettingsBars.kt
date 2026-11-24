package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.library.ui.IconBlur
import com.vayunmathur.library.ui.IconContrast
import com.vayunmathur.library.ui.IconIso
import com.vayunmathur.library.ui.IconLightbulb
import com.vayunmathur.library.ui.IconSunny
import com.vayunmathur.library.ui.IconTimer
import com.vayunmathur.library.ui.IconZoomIn
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.SliderDefaults
import com.vayunmathur.library.ui.Text
import kotlin.math.roundToInt

@Composable
internal fun HorizontalSettingSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    icon: @Composable (Modifier, Color) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = -1f..1f,
    activeWhen: (Float) -> Boolean = { it != 0f },
    displayValue: (Float) -> String = { if (it == 0f) "0" else "%+.1f".format(it) }
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    if (activeWhen(value)) Color(0xFF3C3C3C) else Color.Transparent,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            icon(Modifier.size(20.dp), Color.White)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0xFF666666)
            )
        )
        Text(
            text = displayValue(value),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
internal fun SettingsButtonRow(
    activeSetting: CameraSetting?,
    cameraMode: CameraMode,
    onSelect: (CameraSetting?) -> Unit,
    modifier: Modifier = Modifier
) {
    // Offer only settings the capture path honours in this mode (see
    // settingsForMode): elsewhere they'd silently move a preview effect
    // that the capture path drops. Video gets brightness (exposure
    // compensation) and zoom, both CameraControl-driven. Zoom-locked
    // modes (PANORAMA/PHOTOSPHERE) offer nothing at all — in particular
    // no ZOOM magnifier for the locked 1x — so the row itself is hidden
    // rather than rendering an empty pill.
    val settings: List<Pair<CameraSetting, @Composable (Modifier, Color) -> Unit>> =
        settingsForMode(cameraMode).map { setting ->
            setting to when (setting) {
                CameraSetting.BRIGHTNESS -> { m: Modifier, c: Color -> IconSunny(m, c) }
                CameraSetting.SHADOWS -> { m: Modifier, c: Color -> IconContrast(m, c) }
                CameraSetting.WARMTH -> { m: Modifier, c: Color -> IconLightbulb(m, c) }
                CameraSetting.EXPOSURE_TIME -> { m: Modifier, c: Color -> IconTimer(m, c) }
                CameraSetting.ISO -> { m: Modifier, c: Color -> IconIso(m, c) }
                CameraSetting.PORTRAIT_BLUR -> { m: Modifier, c: Color -> IconBlur(m, c) }
                CameraSetting.ZOOM -> { m: Modifier, c: Color -> IconZoomIn(m, c) }
            }
        }
    if (settings.isEmpty()) return
    Row(
        modifier = modifier
            .background(Color(0x99000000), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        settings.forEach { (setting, icon) ->
            val isActive = activeSetting == setting
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .selectedPill(isActive, CircleShape)
                    .clip(CircleShape)
                    .clickable { onSelect(if (isActive) null else setting) },
                contentAlignment = Alignment.Center
            ) {
                icon(Modifier.size(20.dp), if (isActive) Color.White else Color(0xFFBBBBBB))
            }
        }
    }
}

@Composable
internal fun ExposureTimeBar(
    selectedIndex: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val stops = CameraViewModel.EXPOSURE_TIME_STOPS
    val isManual = selectedIndex > 0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    if (isManual) Color(0xFF3C3C3C) else Color.Transparent,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            IconTimer(Modifier.size(20.dp), Color.White)
        }
        Slider(
            value = selectedIndex.toFloat(),
            onValueChange = { onIndexChange(it.roundToInt()) },
            valueRange = 0f..(stops.size - 1).toFloat(),
            steps = stops.size - 2,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0xFF666666)
            )
        )
        Text(
            text = stops[selectedIndex].label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.End
        )
    }
}

/** Manual ISO: index 0 == Auto, 1..[isoStops].size select a sensitivity stop. */
@Composable
internal fun IsoBar(
    selectedIndex: Int,
    isoStops: List<Int>,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isManual = selectedIndex > 0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(if (isManual) Color(0xFF3C3C3C) else Color.Transparent, CircleShape)
                .clip(CircleShape)
                .clickable { onIndexChange(0) },
            contentAlignment = Alignment.Center
        ) {
            IconIso(Modifier.size(20.dp), Color.White)
        }
        if (isoStops.isEmpty()) {
            Text(
                text = stringResource(R.string.not_available),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textAlign = TextAlign.Center
            )
        } else {
            Slider(
                value = selectedIndex.toFloat(),
                onValueChange = { onIndexChange(it.roundToInt()) },
                valueRange = 0f..isoStops.size.toFloat(),
                steps = (isoStops.size - 1).coerceAtLeast(0),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color(0xFF666666)
                )
            )
        }
        Text(
            text = if (selectedIndex == 0) stringResource(R.string.auto) else "${isoStops.getOrNull(selectedIndex - 1) ?: ""}",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End
        )
    }
}
