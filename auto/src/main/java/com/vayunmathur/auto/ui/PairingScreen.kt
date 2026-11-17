package com.vayunmathur.auto.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.auto.R
import com.vayunmathur.auto.network.UsbConnector
import com.vayunmathur.auto.network.WifiDirectConnector
import com.vayunmathur.auto.platform.PairingViewModel
import com.vayunmathur.auto.protocol.TransportKind
import com.vayunmathur.auto.protocol.UsbSessionState
import com.vayunmathur.auto.protocol.WirelessSessionState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMultiplePermissionRequest

/** How the phone attaches to a car: USB cable, wireless, and what the install allows. */
@Composable
fun PairingScreen(viewModel: PairingViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val usbState by viewModel.usbState.collectAsStateWithLifecycle()
    val usbLabel by viewModel.usbLabel.collectAsStateWithLifecycle()
    val wirelessState by viewModel.wirelessState.collectAsStateWithLifecycle()
    val roleHeld by viewModel.roleHeld.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()

    // The framework announces group formation on a broadcast; forward it so the
    // connector can resolve the group owner's address into the GAL socket.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                WifiDirectConnector.onConnectionChanged(intent)
            }
        }
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val requestNearby = rememberMultiplePermissionRequest(
        permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        },
        onResult = { granted ->
            if (granted) WifiDirectConnector.start(context)
        },
    )
    PairingScreen(
        usbState = usbState,
        usbLabel = usbLabel,
        wirelessState = wirelessState,
        roleHeld = roleHeld,
        selected = selected,
        onNavigateBack = onNavigateBack,
        onUsbRetry = remember(context) { { UsbConnector.retryPermission(context) } },
        onWirelessSearch = requestNearby,
        onWirelessCancel = remember { { WifiDirectConnector.cancel() } },
    )
}

/**
 * Stateless pairing screen: USB + wireless rows, transport and role rows. The
 * ViewModel overload collects the flows and wires the connector calls, then
 * delegates here; metadata previews render this directly with literals.
 */
@Composable
fun PairingScreen(
    usbState: UsbSessionState,
    usbLabel: String?,
    wirelessState: WirelessSessionState,
    roleHeld: Boolean,
    selected: TransportKind?,
    onNavigateBack: () -> Unit,
    onUsbRetry: () -> Unit,
    onWirelessSearch: () -> Unit,
    onWirelessCancel: () -> Unit,
) {
    val scrollBehavior = appBarScrollBehavior()
    AppScaffold(
        title = stringResource(R.string.pairing_title),
        onNavigateBack = onNavigateBack,
        scrollBehavior = scrollBehavior,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            UsbRow(
                state = usbState,
                label = usbLabel,
                onRetry = onUsbRetry,
                modifier = Modifier.padding(top = 16.dp),
            )
            WirelessRow(
                state = wirelessState,
                onSearch = onWirelessSearch,
                onCancel = onWirelessCancel,
                modifier = Modifier.padding(top = 16.dp),
            )
            TransportRow(selected = selected, modifier = Modifier.padding(top = 16.dp))
            RoleRow(held = roleHeld, modifier = Modifier.padding(top = 16.dp))
            Text(
                text = stringResource(R.string.pairing_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

/** USB cable state with a retry action after a denial. */
@Composable
private fun UsbRow(
    state: UsbSessionState,
    label: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val line = when (state) {
        UsbSessionState.DETACHED -> stringResource(R.string.pairing_usb_detached)
        UsbSessionState.AWAITING_PERMISSION ->
            stringResource(R.string.pairing_usb_permission, label ?: "")
        UsbSessionState.PERMISSION_DENIED -> stringResource(R.string.pairing_usb_denied)
        UsbSessionState.CONNECTED ->
            stringResource(R.string.pairing_usb_connected, label ?: "")
        UsbSessionState.DISCONNECTED -> stringResource(R.string.pairing_usb_lost)
    }
    Card(modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(stringResource(R.string.pairing_usb)) },
                supportingContent = { Text(line.trim()) },
            )
            if (state == UsbSessionState.PERMISSION_DENIED) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    Text(stringResource(R.string.pairing_usb_retry))
                }
            }
        }
    }
}

/** Wireless bring-up state with search / cancel actions. */
@Composable
private fun WirelessRow(
    state: WirelessSessionState,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val line = when (state) {
        WirelessSessionState.IDLE -> stringResource(R.string.pairing_wireless_idle)
        WirelessSessionState.NEGOTIATING_WIFI -> stringResource(R.string.pairing_wireless_searching)
        WirelessSessionState.WIFI_READY -> stringResource(R.string.pairing_wireless_ready)
        WirelessSessionState.CONNECTED -> stringResource(R.string.pairing_wireless_connected)
        WirelessSessionState.DISCONNECTED -> stringResource(R.string.pairing_wireless_lost)
    }
    Card(modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(stringResource(R.string.pairing_transport_wireless)) },
                supportingContent = { Text(line) },
            )
            when (state) {
                WirelessSessionState.IDLE, WirelessSessionState.DISCONNECTED -> Button(
                    onClick = onSearch,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    Text(stringResource(R.string.pairing_wireless_search))
                }
                WirelessSessionState.NEGOTIATING_WIFI, WirelessSessionState.WIFI_READY -> OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    Text(stringResource(R.string.pairing_wireless_cancel))
                }
                WirelessSessionState.CONNECTED -> Unit
            }
        }
    }
}

/** Which transport the session would run on; the loopback row only exists in dev. */
@Composable
private fun TransportRow(selected: TransportKind?, modifier: Modifier = Modifier) {
    val line = when (selected) {
        TransportKind.USB -> stringResource(R.string.pairing_transport_usb)
        TransportKind.WIRELESS -> stringResource(R.string.pairing_transport_wireless)
        TransportKind.TCP_LOOPBACK -> stringResource(R.string.pairing_transport_loopback)
        null -> stringResource(R.string.pairing_transport_none)
    }
    Card(modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.pairing_transport)) },
            supportingContent = { Text(line) },
        )
    }
}

/** Whether this install holds the projection role, and what that unlocks. */
@Composable
private fun RoleRow(held: Boolean, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.pairing_role)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (held) R.string.pairing_role_held
                        else R.string.pairing_role_missing,
                    ),
                )
            },
        )
    }
}
