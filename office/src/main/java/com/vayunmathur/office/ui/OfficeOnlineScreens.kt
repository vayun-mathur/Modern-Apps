package com.vayunmathur.office.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.OfflineBanner
import com.vayunmathur.library.util.rememberIsOnline
import com.vayunmathur.office.platform.OfficeDocMeta
import com.vayunmathur.office.platform.OfficeMember
import com.vayunmathur.office.platform.OfficeRoles
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.approveJoinRequest
import com.vayunmathur.office.platform.deleteOnlineDocument
import com.vayunmathur.office.platform.denyRequest
import com.vayunmathur.office.platform.enableOnlineSharing
import com.vayunmathur.office.platform.initSync
import com.vayunmathur.office.platform.shareLinkFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import com.vayunmathur.office.sync.OfficeSync
import com.vayunmathur.office.R
import com.vayunmathur.office.platform.refreshOnline
internal val SpacingXs = 4.dp
internal val SpacingSm = 8.dp
private const val PADDING_SCREEN = 24
private val SpacingScreen = PADDING_SCREEN.dp
internal val SpacingContent = 16.dp

/** Copies [deviceId] to the clipboard; no-op when blank. */
@Composable
internal fun CopyDeviceIdButton(
    deviceId: String,
    scope: CoroutineScope,
    clipboard: androidx.compose.ui.platform.Clipboard,
) {
    TextButton(
        onClick = {
            if (deviceId.isNotEmpty()) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("device id", deviceId)))
                }
            }
        },
    ) {
        Text(stringResource(UiR.string.copy))
    }
}

/** Initializes online sync once when the Online tab first appears. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnlineInit(viewModel: OfficeViewModel) {
    LaunchedEffect(Unit) { viewModel.initSync() }
}

/** Shown on the Online tab before the user opts in. No keys or device id exist yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnlineDisabledScreen(onEnable: () -> Unit) {
    AppScaffold(topBar = {}) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(SpacingScreen),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(SpacingScreen))
            Text(stringResource(R.string.online_sharing_is_off), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(SpacingSm))
            Text(stringResource(R.string.turn_on_online_sharing_to_collaborate_on),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(SpacingScreen))
            Button(
                onClick = onEnable,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.enable_online_sharing_1)) }
        }
    }
}


/** Mutable share-dialog state. */
internal class ShareDialogState(
    initialName: String,
) {
    var recipient by mutableStateOf("")
    var docName by mutableStateOf(initialName)
    var addRole by mutableStateOf(OfficeRoles.EDITOR)
    var roleMenu by mutableStateOf(false)
    var code by mutableStateOf<String?>(null)
    var computing by mutableStateOf(false)
    var sharing by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var memberMenu by mutableStateOf<String?>(null)
}










/** Lists documents shared by you or with you; tap to pull + open. */

/** Device-id header for the online tab. */
@Composable
internal fun OnlineDeviceHeader(
    deviceId: String,
    scope: CoroutineScope,
    clipboard: androidx.compose.ui.platform.Clipboard,
) {
    Text(stringResource(R.string.your_device_id_share_this_so_others_can),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            deviceId.ifEmpty { "…" },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
        CopyDeviceIdButton(deviceId, scope, clipboard)
    }
}

/** Empty notice vs requests + docs lists. */
@Composable
internal fun OnlineDocLists(
    viewModel: OfficeViewModel,
    docs: List<OfficeDocMeta>,
    requests: List<com.vayunmathur.office.sync.OfficeSync.JoinRequest>,
    context: android.content.Context,
    onOpenDoc: (OfficeDocMeta) -> Unit,
) {
    if (docs.isEmpty() && requests.isEmpty()) {
        Text(stringResource(R.string.no_online_documents_yet_open_a_document),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(SpacingXs)) {
        // Inbound join requests (from tapped share links) for docs you own.
        if (requests.isNotEmpty()) {
            item {
                Text(stringResource(R.string.requests_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            items(requests, key = { "req-${it.docId}-${it.requesterId}" }) { req ->
                JoinRequestCard(viewModel, docs, req)
            }
        }
        items(docs, key = { it.docId }) { meta ->
            OnlineDocCard(viewModel, meta, context, onOpenDoc)
        }
    }
}

/** One inbound join request. */
@Composable
private fun JoinRequestCard(
    viewModel: OfficeViewModel,
    docs: List<OfficeDocMeta>,
    req: com.vayunmathur.office.sync.OfficeSync.JoinRequest,
) {
    val docTitle = docs.firstOrNull { it.docId == req.docId }?.title ?: req.docId.take(8)
    val requester = req.name.ifBlank { req.requesterId.take(8) }
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            content = { Text(stringResource(
                R.string.request_wants_to_join,
                requester,
                docTitle)) },
            trailingContent = {
                Row {
                    TextButton(onClick = { viewModel.approveJoinRequest(
                        req.docId,
                        req.requesterId,
                        req.name) }) { Text(stringResource(R.string.approve)) }
                    TextButton(onClick = { viewModel.denyRequest(
                        req.docId,
                        req.requesterId) }) { Text(stringResource(R.string.deny)) }
                }
            }
        )
    }
}

/** One online document row. */
@Composable
private fun OnlineDocCard(
    viewModel: OfficeViewModel,
    meta: OfficeDocMeta,
    context: android.content.Context,
    onOpenDoc: (OfficeDocMeta) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { onOpenDoc(meta) }) {
        ListItem(
            content = { Text(meta.title) },
            supportingContent = {
                Text(
                    if (meta.owner) {
                        stringResource(R.string.shared_by_you)
                    } else {
                        stringResource(R.string.shared_with_you)
                    },
                )
            },
            trailingContent = {
                Row {
                    // Owners can share a tappable invite link (public ids only).
                    if (meta.owner) IconButton(onClick = {
                        ExternalIntents.shareText(
                            context,
                            viewModel.shareLinkFor(meta.docId),
                            context.getString(R.string.share_link_chooser))
                    }) { IconShare() }
                    IconButton(onClick = { confirmDelete = true }) { IconDelete() }
                }
            }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_document)) },
            text = {
                Text(
                    stringResource(
                        if (meta.owner) R.string.delete_document_message
                        else R.string.remove_document_message,
                        meta.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteOnlineDocument(meta.docId)
                }) { Text(stringResource(R.string.delete_document)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            },
        )
    }
}
