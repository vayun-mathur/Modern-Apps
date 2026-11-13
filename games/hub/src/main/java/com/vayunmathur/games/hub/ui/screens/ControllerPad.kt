package com.vayunmathur.games.hub.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.vayunmathur.games.hub.R
import com.vayunmathur.games.hub.domain.KnownGames
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.sdk.controller.ClientState
import com.vayunmathur.sdk.controller.ControllerClient
import com.vayunmathur.sdk.controller.ControllerContract
import com.vayunmathur.sdk.controller.ControllerDiscovery
import com.vayunmathur.sdk.controller.ControllerHostInfo
import com.vayunmathur.sdk.controller.ControllerPairCode
import com.vayunmathur.sdk.controller.LobbyState
import com.vayunmathur.sdk.controller.RumbleFeedback

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
            stringResource(R.string.pad_connect_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (state.localNetworkBlocked) {
        Text(
            stringResource(R.string.pad_local_network_blocked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (state.hosts.isEmpty()) {
        Text(
            stringResource(R.string.pad_looking),
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
                        stringResource(R.string.pad_hosting, KnownGames.displayName(host.gameId)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = { onConnect(host) }) { Text(stringResource(R.string.pad_connect)) }
        }
    }
    OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pad_rescan)) }
}

@Composable
private fun PinPane(onProveCode: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Text(
        pluralStringResource(R.plurals.pad_pin_prompt, ControllerPairCode.DIGITS, ControllerPairCode.DIGITS),
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
    ) { Text(stringResource(R.string.pad_pair)) }
}

@Composable
private fun NamePane(state: ControllerPadState, onJoin: (String) -> Unit) {
    val defaultName = stringResource(R.string.player)
    var name by remember { mutableStateOf(defaultName) }
    val seats = state.lobby.slots.size
    Text(
        pluralStringResource(R.plurals.pad_paired_seats_taken, seats, seats),
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(24) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Button(
        onClick = { onJoin(name.ifBlank { defaultName }) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.pad_join)) }
}
