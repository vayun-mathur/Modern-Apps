package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.platform.AspectRatioOption
import com.vayunmathur.camera.platform.FlashMode
import com.vayunmathur.camera.platform.TimerDuration
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconFlashAuto
import com.vayunmathur.library.ui.IconFlashOff
import com.vayunmathur.library.ui.IconFlashOn
import com.vayunmathur.library.ui.IconFlashlight
import com.vayunmathur.library.ui.IconGrid
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconMicOff
import com.vayunmathur.library.ui.IconTimer
import com.vayunmathur.library.ui.IconToolsLevel
import com.vayunmathur.library.ui.Text

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CameraTopBar(
    flashMode: FlashMode,
    torchEnabled: Boolean,
    hasFlashUnit: Boolean,
    gridEnabled: Boolean,
    levelEnabled: Boolean,
    aspectRatio: AspectRatioOption,
    stillCropEnabled: Boolean,
    isPhotoType: Boolean,
    isVideoType: Boolean,
    micMuted: Boolean,
    timerDuration: TimerDuration,
    onFlashToggle: () -> Unit,
    onTorchToggle: () -> Unit,
    onGridToggle: () -> Unit,
    onLevelToggle: () -> Unit,
    onAspectCycle: () -> Unit,
    onMicToggle: () -> Unit,
    onTimerCycle: () -> Unit,
    iconRotation: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        // Centred rather than packed left: the control count changes with the mode (level in photo,
        // mic in video), so a left-aligned row shifts its contents around as you switch modes.
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flash/torch share the same LED: without a flash unit (some front lenses) the
        // control is dead, so hide it instead of showing a button that does nothing.
        if (hasFlashUnit) {
            val flashBg = if (torchEnabled || flashMode != FlashMode.OFF) Color(0xFF3C3C3C) else Color.Transparent
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(flashBg, CircleShape)
                    .combinedClickable(
                        onClick = onFlashToggle,
                        onLongClick = onTorchToggle
                    ),
                contentAlignment = Alignment.Center
            ) {
                val flashIconModifier = Modifier.size(22.dp).rotate(iconRotation)
                if (torchEnabled) {
                    IconFlashlight(flashIconModifier, Color.White)
                } else {
                    when (flashMode) {
                        FlashMode.ON -> IconFlashOn(flashIconModifier, Color.White)
                        FlashMode.OFF -> IconFlashOff(flashIconModifier, Color.White)
                        FlashMode.AUTO -> IconFlashAuto(flashIconModifier, Color.White)
                    }
                }
            }
        }

        val gridBg = if (gridEnabled) Color(0xFF3C3C3C) else Color.Transparent
        IconButton(
            onClick = onGridToggle,
            modifier = Modifier
                .size(40.dp)
                .background(gridBg, CircleShape)
        ) {
            IconGrid(Modifier.size(22.dp).rotate(iconRotation), Color.White)
        }

        // The still crop only binds on the photo sessions (PHOTO/PORTRAIT ImageCapture):
        // hide the picker elsewhere so it can't promise a 1:1 video or pano it won't deliver.
        if (stillCropEnabled) {
            val aspectLabel = stringResource(R.string.settings_aspect_ratio)
            IconButton(
                onClick = onAspectCycle,
                modifier = Modifier.height(40.dp).widthIn(min = 40.dp)
            ) {
                Text(
                    aspectRatio.label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .rotate(iconRotation)
                        .semantics { contentDescription = aspectLabel },
                )
            }
        }

        if (isPhotoType) {
            val levelBg = if (levelEnabled) Color(0xFF3C3C3C) else Color.Transparent
            IconButton(
                onClick = onLevelToggle,
                modifier = Modifier
                    .size(40.dp)
                    .background(levelBg, CircleShape)
            ) {
                IconToolsLevel(
                    Modifier.size(22.dp).rotate(iconRotation),
                    Color.White
                )
            }
        }

        val timerBg = if (timerDuration != TimerDuration.NONE) Color(0xFF3C3C3C) else Color.Transparent
        IconButton(
            onClick = onTimerCycle,
            modifier = Modifier
                .size(40.dp)
                .background(timerBg, CircleShape)
        ) {
            if (timerDuration == TimerDuration.NONE) {
                IconTimer(Modifier.size(22.dp).rotate(iconRotation), Color.White)
            } else {
                val timerLabel = when (timerDuration) {
                    TimerDuration.NONE -> ""
                    TimerDuration.ONE -> "1"
                    TimerDuration.THREE -> "3"
                    TimerDuration.FIVE -> "5"
                    TimerDuration.TEN -> "10"
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconTimer(Modifier.size(14.dp).rotate(iconRotation), Color.White)
                    Text(timerLabel, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
                }
            }
        }

        if (isVideoType) {
            val micBg = if (micMuted) Color(0xFF3C3C3C) else Color.Transparent
            IconButton(
                onClick = onMicToggle,
                modifier = Modifier
                    .size(40.dp)
                    .background(micBg, CircleShape)
            ) {
                if (micMuted) IconMicOff(Modifier.size(22.dp).rotate(iconRotation), Color.White)
                else IconMic(Modifier.size(22.dp).rotate(iconRotation), Color.White)
            }
        }
    }
}
