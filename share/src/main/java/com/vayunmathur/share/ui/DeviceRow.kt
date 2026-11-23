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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
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
fun DeviceRow(device: NearbyDevice, onTap: () -> Unit) {
    val canConnect = device.host != null && device.port != null
    val subtitle = when {
        canConnect -> "${device.host}:${device.port}"
        device.source == DiscoverySource.Ble -> stringResource(R.string.share_ble_only)
        else -> device.extra ?: device.serviceName ?: ""
    }.ifBlank { device.endpointId }
    val sourceLabel = stringResource(
        when (device.source) {
            DiscoverySource.Nsd -> R.string.share_source_wifi
            DiscoverySource.Ble -> R.string.share_source_bluetooth
            DiscoverySource.Both -> R.string.share_source_both
            DiscoverySource.AirDrop -> R.string.share_source_airdrop
        }
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canConnect, onClick = onTap),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.endpointName, style = MaterialTheme.typography.titleSmall)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Text(sourceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (!canConnect) {
                Text(
                    stringResource(R.string.share_waiting_for_address),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
