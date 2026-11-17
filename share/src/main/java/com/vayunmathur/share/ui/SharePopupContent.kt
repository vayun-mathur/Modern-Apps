package com.vayunmathur.share.ui
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberModalBottomSheetState
import com.vayunmathur.library.ui.rememberMultiplePermissionRequest
import com.vayunmathur.share.R
import com.vayunmathur.share.platform.SendUiState
import com.vayunmathur.share.platform.ShareActions
import com.vayunmathur.share.platform.SharePermissions
import com.vayunmathur.share.platform.ShareViewModel

@Composable
fun SharePopupContent(
    uiState: SendUiState,
    actions: ShareActions,
    permissionsDenied: Boolean,
    onDismiss: () -> Unit,
) {
    // skipPartiallyExpanded: the sheet is already sized to half the screen, so a partial state
    // would only offer a second, smaller resting place nobody asked for.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .height(LocalWindowInfo.current.containerDpSize.height / 2)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.share_popup_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutgoingFilesSummary(uiState.outgoingDisplayNames, uiState.outgoingUris.size)
            val connection = uiState.activeConnection
            when {
                // A live transfer replaces the device list: the choice has been made, and the
                // sheet stays up until dismissed so the result is readable.
                connection != null -> TransferCardHost(
                    conn = connection,
                    onDisconnect = { actions.disconnect(connection) },
                )
                permissionsDenied -> Text(
                    stringResource(R.string.share_popup_permission_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    if (uiState.isScanning) ScanningRow()
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.discoveredDevices, key = { it.endpointId }) { device ->
                            DeviceRow(device = device, onTap = { actions.connectToDevice(device) })
                        }
                        if (uiState.discoveredDevices.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.share_popup_no_devices),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
