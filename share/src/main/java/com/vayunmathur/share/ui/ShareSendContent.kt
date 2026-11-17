package com.vayunmathur.share.ui
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.share.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.share.network.transport.Connection
import com.vayunmathur.share.platform.discovery.DiscoverySource
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.ShareViewModel
import com.vayunmathur.share.platform.ShareActions
import com.vayunmathur.share.platform.SendUiState

@Composable
fun ShareSendContent(
    uiState: SendUiState,
    actions: ShareActions,
    modifier: Modifier = Modifier,
) {
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) actions.setOutgoingUris(uris)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutgoingFilesCard(
                uris = uiState.outgoingUris,
                displayNames = uiState.outgoingDisplayNames,
                onPickFiles = { pickerLauncher.launch(arrayOf("*/*")) },
                onClear = actions::clearOutgoing,
            )
        }
        if (uiState.activeConnection != null) {
            item {
                val conn = uiState.activeConnection
                TransferCardHost(conn = conn, onDisconnect = { actions.disconnect(conn) })
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.share_nearby_devices),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (uiState.isScanning) {
                    Button(onClick = actions::stopScan) { Text(stringResource(UiR.string.stop)) }
                } else {
                    OutlinedButton(onClick = actions::startScan) { Text(stringResource(R.string.share_scan)) }
                }
            }
        }
        if (uiState.isScanning && uiState.discoveredDevices.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(stringResource(R.string.share_scanning), modifier = Modifier.weight(1f))
                    }
                }
            }
        } else if (uiState.discoveredDevices.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.share_no_devices),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            items(uiState.discoveredDevices, key = { it.endpointId }) { device ->
                DeviceRow(
                    device = device,
                    onTap = { actions.connectToDevice(device) },
                )
            }
        }
        if (uiState.activeConnection == null && uiState.outgoingUris.isNotEmpty() && uiState.discoveredDevices.isNotEmpty()) {
            item {
                Text(
                    pluralStringResource(R.plurals.share_select_device, uiState.outgoingUris.size, uiState.outgoingUris.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        if (uiState.activeConnection == null && uiState.outgoingUris.isEmpty() && uiState.discoveredDevices.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.share_send_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
