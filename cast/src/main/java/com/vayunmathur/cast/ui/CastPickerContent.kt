package com.vayunmathur.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.cast.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.cast.platform.CastActions
import com.vayunmathur.cast.platform.CastConnection
import com.vayunmathur.cast.platform.CastUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * Pick a TV on behalf of another app.
 *
 * The same device list and the same pair-code card as [CastContent], with everything about mirroring
 * removed: the caller has its own content to send, and offering to mirror the screen here would be
 * offering to do the opposite of what it asked for.
 *
 * [appName] is the framework's answer for who is asking - resolved from `callingPackage`, not
 * self-reported - so naming it in the title is a claim the calling app could not have made up.
 */
@Composable
fun CastPickerContent(
    state: CastUiState,
    actions: CastActions,
    appName: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppScaffold(
        title = if (appName.isBlank()) {
            stringResource(R.string.cast_picker_title_generic)
        } else {
            stringResource(R.string.cast_picker_title, appName)
        },
        modifier = modifier,
        actions = {
            if (state.isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 16.dp).size(24.dp),
                )
            } else {
                IconButton(onClick = actions::startScan) { IconRefresh() }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Nothing else on the screen can be acted on until the code has been typed, so anything
            // above it would only be in the way.
            if (state.connection == CastConnection.AwaitingCode) {
                item { CastPairCodeCard(state, actions) }
            }
            item { PickerHeader(state) }
            items(state.devices, key = { it.id }) { device ->
                CastDeviceRow(
                    device = device,
                    isConnected = state.connectedDevice?.id == device.id,
                    onClick = { actions.connect(device) },
                )
            }
            item {
                TextButton(onClick = onCancel) {
                    Text(stringResource(UiR.string.cancel))
                }
            }
        }
    }
}

/**
 * Why the list is empty, or what is happening to the TV that was tapped.
 *
 * Deliberately not [CastContent]'s `SectionHeader`: this one has no disconnect button, because a
 * connected TV finishes the picker rather than becoming a state to manage.
 */
@Composable
private fun PickerHeader(state: CastUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        val name = state.connectedDevice?.friendlyName ?: ""
        val text = when {
            state.connection == CastConnection.Connecting ->
                stringResource(R.string.cast_connecting_to, name)
            state.connection == CastConnection.AwaitingCode ->
                stringResource(R.string.cast_pairing_with, name)
            state.connection == CastConnection.Failed ->
                state.failure ?: stringResource(R.string.cast_protocol_error)
            state.localNetworkBlocked -> stringResource(R.string.cast_local_network_blocked)
            state.devices.isNotEmpty() -> stringResource(R.string.cast_devices_heading)
            state.isScanning -> stringResource(R.string.cast_searching)
            else -> stringResource(R.string.cast_no_devices)
        }
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (state.connection == CastConnection.Failed || state.localNetworkBlocked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (state.devices.isEmpty() && !state.isScanning) {
            Text(
                stringResource(R.string.cast_no_devices_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
