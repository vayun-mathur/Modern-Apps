package com.vayunmathur.games.hub.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.hub.R
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.sdk.controller.ControllerButton
import com.vayunmathur.sdk.controller.ControllerContract
import kotlinx.coroutines.delay
import kotlin.math.hypot

/** The latest raw sample, mutated by gestures and read by the emit ticker. Never a State. */
private class PadSnapshot {
    @Volatile var stickX = 0f
    @Volatile var stickY = 0f
    @Volatile var buttons = 0L
}

/**
 * The seated pad ([com.vayunmathur.sdk.controller.ClientState.JOINED]): stick, face and D-pad
 * buttons, lobby status. Lives beside [ControllerPad] rather than in it so both files stay under
 * the ui file-length cap.
 */
@Composable
internal fun PadPane(
    state: ControllerPadState,
    onInput: (buttons: Long, axes: List<Float>, hatX: Int, hatY: Int) -> Unit,
    onLeave: () -> Unit,
) {
    val snapshot = remember { PadSnapshot() }
    val latestInput by rememberUpdatedState(onInput)
    // Driving with the phone means long stretches with no touch on the screen outside the pad
    // areas: keep it awake while seated, and hand the setting back when the pad goes away.
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    // Off-composition: gestures write the snapshot, this loop reads it. No recomposition per sample.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L / ControllerContract.INPUT_HZ)
            val pressed = snapshot.buttons
            val hatX = when {
                pressed and ControllerButton.DPAD_LEFT.bit != 0L -> -1
                pressed and ControllerButton.DPAD_RIGHT.bit != 0L -> 1
                else -> 0
            }
            val hatY = when {
                pressed and ControllerButton.DPAD_UP.bit != 0L -> -1
                pressed and ControllerButton.DPAD_DOWN.bit != 0L -> 1
                else -> 0
            }
            latestInput(
                pressed,
                listOf(snapshot.stickX, snapshot.stickY, 0f, 0f, 0f, 0f),
                hatX,
                hatY,
            )
        }
    }

    val seats = state.lobby.slots
    if (seats.isNotEmpty()) {
        Text(
            stringResource(R.string.pad_lobby, seats.joinToString { it.displayName }),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.lobby.racing) {
        Text(
            stringResource(R.string.pad_race_live),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PadStick { x, y -> snapshot.stickX = x; snapshot.stickY = y }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PadHoldButton("X", ControllerButton.X.bit, snapshot)
                PadHoldButton("Y", ControllerButton.Y.bit, snapshot)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PadHoldButton("A", ControllerButton.A.bit, snapshot)
                PadHoldButton("B", ControllerButton.B.bit, snapshot)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PadHoldButton("LB", ControllerButton.LEFT_SHOULDER.bit, snapshot)
                PadHoldButton("RB", ControllerButton.RIGHT_SHOULDER.bit, snapshot)
            }
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Arrow glyphs instead of English initials (U/L/D/R); TalkBack gets the direction words.
        PadHoldButton("▲", ControllerButton.DPAD_UP.bit, snapshot, stringResource(R.string.pad_dpad_up))
        PadHoldButton("◀", ControllerButton.DPAD_LEFT.bit, snapshot, stringResource(R.string.pad_dpad_left))
        PadHoldButton("▼", ControllerButton.DPAD_DOWN.bit, snapshot, stringResource(R.string.pad_dpad_down))
        PadHoldButton("▶", ControllerButton.DPAD_RIGHT.bit, snapshot, stringResource(R.string.pad_dpad_right))
    }
    state.lastRumble?.let { rumble ->
        Text(
            stringResource(R.string.pad_rumble, rumble.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pad_leave)) }
}

/**
 * The left stick: drag to deflect, release to recenter.
 *
 * Same feel as the voxels move stick — 0.15 deadzone, raw deflection otherwise — because the TV
 * applies the cubic curve itself and both ends must agree on what "half throw" means.
 */
@Composable
private fun PadStick(onMove: (x: Float, y: Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val base = MaterialTheme.colorScheme.surfaceVariant
    val knobColor = MaterialTheme.colorScheme.primary
    Box(Modifier.size(130.dp)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            val raw = offset - Offset(size.width / 2f, size.height / 2f)
                            val max = 60.dp.toPx()
                            val clamped = clampKnob(raw, max)
                            knob = clamped
                            emitStick(clamped, max, onMove)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val max = 60.dp.toPx()
                            knob = clampKnob(knob + Offset(dragAmount.x, dragAmount.y), max)
                            emitStick(knob, max, onMove)
                        },
                        onDragEnd = { knob = Offset.Zero; dragging = false; onMove(0f, 0f) },
                        onDragCancel = { knob = Offset.Zero; dragging = false; onMove(0f, 0f) },
                    )
                },
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = base, radius = 65.dp.toPx(), center = center)
            drawCircle(
                color = knobColor.copy(alpha = if (dragging) 0.8f else 0.6f),
                radius = 27.dp.toPx(),
                center = center + knob,
            )
        }
    }
}

private fun clampKnob(raw: Offset, max: Float): Offset {
    val dist = hypot(raw.x, raw.y)
    return if (dist > max && dist > 0.001f) raw * (max / dist) else raw
}

private fun emitStick(clamped: Offset, max: Float, onMove: (Float, Float) -> Unit) {
    val nx = clamped.x / max
    val ny = clamped.y / max
    // Deadzone only — the cubic curve lives TV-side, so both ends shape the same raw sample.
    if (hypot(nx, ny) < 0.15f) onMove(0f, 0f) else onMove(nx, -ny)
}

/**
 * A press-and-hold button: sets its bit while held, clears on release. [label] is the face glyph
 * (A/B/X/Y/LB/RB are the hardware names and stay as-is); [spokenLabel] overrides it for TalkBack.
 */
@Composable
private fun PadHoldButton(label: String, bit: Long, snapshot: PadSnapshot, spokenLabel: String? = null) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(width = 64.dp, height = 52.dp)
            .then(if (spokenLabel != null) Modifier.semantics { contentDescription = spokenLabel } else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (pressed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .pointerInput(bit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        snapshot.buttons = snapshot.buttons or bit
                        tryAwaitRelease()
                        pressed = false
                        snapshot.buttons = snapshot.buttons and bit.inv()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (pressed) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}
