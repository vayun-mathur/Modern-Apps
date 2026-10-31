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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.sdk.controller.ClientState
import com.vayunmathur.sdk.controller.ControllerButton
import com.vayunmathur.sdk.controller.ControllerClient
import com.vayunmathur.sdk.controller.ControllerContract
import com.vayunmathur.sdk.controller.ControllerDiscovery
import com.vayunmathur.sdk.controller.ControllerHostInfo
import com.vayunmathur.sdk.controller.ControllerPairCode
import com.vayunmathur.sdk.controller.LobbyState
import com.vayunmathur.sdk.controller.RumbleFeedback
import kotlinx.coroutines.delay
import kotlin.math.hypot

/**
 * Everything the pad screen needs, owned by the hosting app.
 *
 * Lives here (not in `:sdk:controller`) so the SDK stays a headless
 * contract: SDK consumers gain no `:library:ui` dependency — and no
 * permissions — from it (#884).
 *
 * The hosting app constructs a [ControllerDiscovery] and a [ControllerClient],
 * collects their flows into this, and wires the callbacks back to them:
 * `onConnect` → `client.connect(host)`, `onProveCode` → `client.proveCode(code)`,
 * `onJoin` → `client.join(name)`, `onInput` → `client.sendInput(...)`, `onLeave` → `client.leave()`,
 * `onRescan` → re-collect `discovery.discover()`. Rumble arrives via `client.onRumble` and is
 * copied into [lastRumble] for display. Kart itself hosts no phone UI.
 */
data class ControllerPadState(
    val clientState: ClientState = ClientState.IDLE,
    val hosts: List<ControllerHostInfo> = emptyList(),
    val localNetworkBlocked: Boolean = false,
    val lobby: LobbyState = LobbyState(),
    val lastRumble: RumbleFeedback? = null,
)

/**
 * The reusable phone-as-controller screen: find a TV, type the PIN, take a seat, drive.
 *
 * The left stick steers and throttles on the same axis slots the TV's `GameInputLoop` reads; the
 * curve matches the voxels `Joystick` feel (0.15 deadzone, then `0.2a + 0.8a³`), so a phone stick
 * and a TV gamepad with the same deflection drive the same line. Input emits at
 * [ControllerContract.INPUT_HZ] from a ticker coroutine — off-composition, so a 30 Hz sample rate
 * never recomposes — and each emit carries the client's next sequence number.
 */
@Composable
fun ControllerPad(
    state: ControllerPadState,
    onConnect: (ControllerHostInfo) -> Unit,
    onProveCode: (String) -> Unit,
    onJoin: (String) -> Unit,
    onInput: (buttons: Long, axes: List<Float>, hatX: Int, hatY: Int) -> Unit,
    onLeave: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        when (state.clientState) {
            ClientState.IDLE, ClientState.CONNECTING, ClientState.FAILED ->
                DiscoveryPane(state, onConnect, onRescan)
            ClientState.AWAITING_CODE -> PinPane(onProveCode)
            ClientState.PAIRED -> NamePane(state, onJoin)
            ClientState.JOINED -> PadPane(state, onInput, onLeave)
        }
    }
}

@Composable
private fun DiscoveryPane(
    state: ControllerPadState,
    onConnect: (ControllerHostInfo) -> Unit,
    onRescan: () -> Unit,
) {
    if (state.clientState == ClientState.FAILED) {
        Text(
            "Couldn't reach that TV. Check you're on the same Wi-Fi and try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (state.localNetworkBlocked) {
        Text(
            "Local-network access is off, so no TVs can be found. Allow it when asked, or enable it in system settings, then rescan.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (state.hosts.isEmpty()) {
        Text(
            "Looking for TVs on this Wi-Fi…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.hosts.forEach { host ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(host.friendlyName, style = MaterialTheme.typography.titleMedium)
                if (host.gameId.isNotEmpty()) {
                    Text(
                        "Hosting ${host.gameId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = { onConnect(host) }) { Text("Connect") }
        }
    }
    OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) { Text("Rescan") }
}

@Composable
private fun PinPane(onProveCode: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Text(
        "Type the 6 digits showing on the TV.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = code,
        onValueChange = { next -> code = next.filter { it in '0'..'9' }.take(ControllerPairCode.DIGITS) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    Button(
        onClick = { onProveCode(code) },
        enabled = ControllerPairCode.isWellFormed(code),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Pair") }
}

@Composable
private fun NamePane(state: ControllerPadState, onJoin: (String) -> Unit) {
    var name by remember { mutableStateOf("Player") }
    Text(
        "Paired. Pick the name the lobby shows — ${state.lobby.slots.size} seat(s) taken.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(24) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Button(
        onClick = { onJoin(name.ifBlank { "Player" }) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Join game") }
}

/** The latest raw sample, mutated by gestures and read by the emit ticker. Never a State. */
private class PadSnapshot {
    @Volatile var stickX = 0f
    @Volatile var stickY = 0f
    @Volatile var buttons = 0L
}

@Composable
private fun PadPane(
    state: ControllerPadState,
    onInput: (buttons: Long, axes: List<Float>, hatX: Int, hatY: Int) -> Unit,
    onLeave: () -> Unit,
) {
    val snapshot = remember { PadSnapshot() }
    val latestInput by rememberUpdatedState(onInput)
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
            "Lobby: ${seats.joinToString { it.displayName }}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.lobby.racing) {
        Text(
            "Race is live — joins are locked until it ends.",
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
        PadHoldButton("U", ControllerButton.DPAD_UP.bit, snapshot)
        PadHoldButton("L", ControllerButton.DPAD_LEFT.bit, snapshot)
        PadHoldButton("D", ControllerButton.DPAD_DOWN.bit, snapshot)
        PadHoldButton("R", ControllerButton.DPAD_RIGHT.bit, snapshot)
    }
    state.lastRumble?.let { rumble ->
        Text(
            "Rumble: ${rumble.durationMs}ms",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("Leave") }
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

/** A press-and-hold button: sets its bit while held, clears on release. */
@Composable
private fun PadHoldButton(label: String, bit: Long, snapshot: PadSnapshot) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(width = 64.dp, height = 52.dp)
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
