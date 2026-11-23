package com.vayunmathur.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.cast.R
import com.vayunmathur.cast.platform.CastActions
import com.vayunmathur.cast.platform.CastConnection
import com.vayunmathur.cast.platform.CastUiState
import com.vayunmathur.cast.platform.MirrorPhase
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.TextButton

/**
 * The whole app: pick a TV, type the code it shows the first time, mirror.
 *
 * Stateless - state in, [CastActions] out - so the store-listing screenshots can render it from a
 * `@Preview`.
 */
@Composable
fun CastContent(
    state: CastUiState,
    actions: CastActions,
    modifier: Modifier = Modifier,
) {
    AppScaffold(
        title = stringResource(R.string.app_name),
        modifier = modifier,
        actions = {
            if (state.isScanning) {
                Text("Loading\u2026", modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                IconButton(onClick = actions::startScan) { IconRefresh() }
            }
        },
    ) { padding ->
        // Letterboxed on expanded windows so the device list keeps a readable measure.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            // The code comes first when it is wanted: nothing else on the screen can be acted on until
            // it has been typed, so anything above it would just be in the way.
            if (state.connection == CastConnection.AwaitingCode) {
                item { CastPairCodeCard(state, actions) }
            } else if (state.connection == CastConnection.Connected ||
                state.mirrorPhase != MirrorPhase.Idle
            ) {
                item { CastMirrorStatusCard(state, actions) }
            }
            item { SectionHeader(state, actions) }
            if (state.localNetworkBlocked) {
                item { LocalNetworkBlocked(actions) }
            } else if (state.devices.isEmpty()) {
                item { NoDevices(state) }
            } else {
                items(state.devices, key = { it.id }) { device ->
                    CastDeviceRow(
                        device = device,
                        isConnected = state.connectedDevice?.id == device.id,
                        onClick = { actions.connect(device) },
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun SectionHeader(state: CastUiState, actions: CastActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        val connected = state.connectedDevice
        when (state.connection) {
            CastConnection.Connecting -> Text(
                stringResource(R.string.cast_connecting_to, connected?.friendlyName ?: ""),
                style = MaterialTheme.typography.titleSmall,
            )
            // The card above already explains itself, so the header only names the device.
            CastConnection.AwaitingCode -> Text(
                stringResource(R.string.cast_pairing_with, connected?.friendlyName ?: ""),
                style = MaterialTheme.typography.titleSmall,
            )
            CastConnection.Connected -> Column {
                Text(
                    stringResource(R.string.cast_connected_to, connected?.friendlyName ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = actions::disconnect) {
                    Text(stringResource(R.string.cast_disconnect))
                }
            }
            CastConnection.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    state.failure ?: stringResource(R.string.cast_protocol_error),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                // Tapping the device row again also retries, but a refusal leaves the row looking
                // unchanged, so there has to be something obvious to press.
                if (connected != null) {
                    TextButton(onClick = { actions.connect(connected) }) {
                        Text(stringResource(R.string.cast_retry))
                    }
                }
            }
            CastConnection.Disconnected -> Text(
                stringResource(R.string.cast_devices_heading),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun NoDevices(state: CastUiState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(
                if (state.isScanning) R.string.cast_searching else R.string.cast_no_devices,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.cast_no_devices_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Android 16 refused the mDNS browse. Called out rather than folded into "no devices found", because
 * nothing about the network or the TV will fix it - only a permission will.
 */
@Composable
private fun LocalNetworkBlocked(actions: CastActions) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.cast_local_network_blocked),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(R.string.cast_local_network_blocked_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = actions::openLocalNetworkSettings) {
            Text(stringResource(R.string.cast_open_settings))
        }
    }
}
