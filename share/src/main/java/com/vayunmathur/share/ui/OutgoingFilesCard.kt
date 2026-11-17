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
fun OutgoingFilesCard(
    uris: List<Uri>,
    displayNames: List<String>,
    onPickFiles: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (uris.isEmpty()) stringResource(R.string.share_no_files_selected)
                    else pluralStringResource(R.plurals.share_files_selected, uris.size, uris.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickFiles) {
                        Text(
                            stringResource(
                                if (uris.isEmpty()) R.string.share_pick_files else R.string.share_add_files
                            )
                        )
                    }
                    if (uris.isNotEmpty()) {
                        OutlinedButton(onClick = onClear) { Text(stringResource(UiR.string.clear)) }
                    }
                }
            }
            if (uris.isNotEmpty()) {
                displayNames.forEach { name ->
                    Text(
                        "• $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
