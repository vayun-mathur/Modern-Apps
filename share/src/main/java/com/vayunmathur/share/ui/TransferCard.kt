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
fun TransferCard(
    endpoint: String,
    state: ShareState,
    bytesSent: Long,
    error: String?,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.share_sending_to, endpoint),
                style = MaterialTheme.typography.titleSmall,
            )
            when (state) {
                ShareState.Handshaking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.share_connecting))
                    }
                }
                ShareState.AwaitingAccept -> {
                    Text(stringResource(R.string.share_waiting_for_accept))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                ShareState.Transferring -> {
                    Text(stringResource(R.string.share_sending_kb, bytesSent / 1024))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                ShareState.Completed -> {
                    Text(stringResource(R.string.share_sent), color = MaterialTheme.colorScheme.primary)
                    Button(onClick = onDisconnect) { Text(stringResource(UiR.string.done)) }
                }
                ShareState.Failed -> {
                    Text(
                        // The native failure reason, which names the phase that broke, rather
                        // than a bare return code.
                        error ?: stringResource(R.string.share_send_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onDisconnect) { Text(stringResource(UiR.string.dismiss)) }
                }
                ShareState.Unknown -> {
                    Text(stringResource(R.string.share_unknown_state))
                }
            }
            if (error != null && state != ShareState.Failed) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
