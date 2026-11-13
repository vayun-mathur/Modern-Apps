package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.voxels.R
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vayunmathur.games.voxels.data.WorldInfo
import com.vayunmathur.games.voxels.network.VoxelsRoles
import com.vayunmathur.games.voxels.network.VoxelsSync
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text

@Composable
fun MenuScreen(
    worlds: List<WorldInfo>,
    onlineWorlds: List<WorldInfo>,
    deviceId: String,
    isOnline: Boolean,
    onPlay: (WorldInfo) -> Unit,
    onDelete: (WorldInfo) -> Unit,
    onCreate: () -> Unit,
    onHostOnline: () -> Unit,
    onRefresh: () -> Unit,
    onCopyDeviceId: () -> Unit,
    onShare: (WorldInfo) -> Unit,
    requests: List<VoxelsSync.JoinRequest> = emptyList(),
    onApprove: (VoxelsSync.JoinRequest) -> Unit = {},
    onDeny: (VoxelsSync.JoinRequest) -> Unit = {},
) {
    // Deleting a world is permanent, so the trash button only asks; the dialog names the world.
    var pendingDelete by remember { mutableStateOf<WorldInfo?>(null) }
    pendingDelete?.let { doomed ->
        ConfirmDialog(
            title = stringResource(R.string.delete_world_title, doomed.meta.name),
            message = stringResource(R.string.delete_world_message),
            confirmLabel = stringResource(UiR.string.delete),
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = { onDelete(doomed) },
            onDismiss = { pendingDelete = null },
        )
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.select_a_world), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(20.dp))

            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (worlds.isEmpty()) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.no_worlds_yet_create_one_to_start),
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                        )
                    }
                } else {
                    items(worlds, key = { it.id }) { world ->
                        WorldRow(world = world, enabled = true, requiresInternet = false,
                            onPlay = { onPlay(world) }, onDelete = { pendingDelete = world }, onShare = null)
                    }
                }

                // --- Online worlds section ---
                item {
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.online_worlds), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        if (!isOnline) {
                            Text(stringResource(R.string.requires_internet), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error)
                        }
                        OutlinedButton(onClick = onRefresh, enabled = isOnline) { Text(stringResource(R.string.refresh)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    DeviceIdRow(deviceId = deviceId, onCopy = onCopyDeviceId)
                    Spacer(Modifier.height(8.dp))
                }

                if (onlineWorlds.isEmpty()) {
                    item {
                        Text(stringResource(R.string.no_online_worlds), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                } else {
                    items(onlineWorlds, key = { it.id }) { world ->
                        val owner = world.meta.role == VoxelsRoles.OWNER
                        WorldRow(world = world, enabled = isOnline, requiresInternet = !isOnline,
                            subtitle = if (owner) stringResource(R.string.hosted_by_you) else stringResource(R.string.shared_with_you),
                            onPlay = { if (isOnline) onPlay(world) },
                            onDelete = { pendingDelete = world },
                            onShare = if (owner) ({ onShare(world) }) else null)
                    }
                }

                // --- Inbound join requests (owners only) ---
                if (requests.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.requests_header), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                    }
                    items(requests, key = { "req-${it.worldId}-${it.requesterId}" }) { req ->
                        val worldName = onlineWorlds.firstOrNull { it.meta.worldId == req.worldId }?.meta?.name ?: req.worldId.take(8)
                        val requester = req.name.ifBlank { req.requesterId.take(8) }
                        RequestRow(
                            text = stringResource(R.string.request_wants_to_join, requester, worldName),
                            onApprove = { onApprove(req) },
                            onDeny = { onDeny(req) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCreate, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.create_new_world))
                }
                OutlinedButton(onClick = onHostOnline, enabled = isOnline, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.host_online_world))
                }
            }
        }
    }
}

@Composable
private fun DeviceIdRow(deviceId: String, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.your_device_id), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(deviceId.ifEmpty { "…" }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onCopy, enabled = deviceId.isNotEmpty()) { Text(stringResource(UiR.string.copy)) }
        }
    }
}

@Composable
private fun WorldRow(
    world: WorldInfo,
    enabled: Boolean = true,
    requiresInternet: Boolean = false,
    subtitle: String? = null,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onShare: (() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.5f
    val cardModifier = Modifier.fillMaxWidth().let { if (enabled) it.clickable { onPlay() } else it }
    Card(modifier = cardModifier) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(world.meta.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                val last = DateUtils.getRelativeTimeSpanString(world.meta.lastPlayed).toString()
                Text(stringResource(R.string.seed_2, world.meta.seed, last), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f * alpha))
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                }
                if (requiresInternet) {
                    Text(stringResource(R.string.requires_internet), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            if (onShare != null) IconButton(onClick = onShare) { IconShare(tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onDelete) { IconDelete(tint = MaterialTheme.colorScheme.error) }
            IconButton(onClick = onPlay) { IconPlay(tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)) }
        }
    }
}

@Composable
private fun RequestRow(text: String, onApprove: () -> Unit, onDeny: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onDeny) { Text(stringResource(R.string.deny)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onApprove) { Text(stringResource(R.string.approve)) }
        }
    }
}

// Owner-only dialog: invite a device by id. Voxels worlds have no viewers — everyone invited can
// build — so there's no role choice. Mirrors office's ShareOnlineDialog.
@Composable
fun ShareOnlineDialog(
    world: WorldInfo,
    onDismiss: () -> Unit,
    onSend: (recipient: String) -> Unit,
) {
    var recipient by remember { mutableStateOf("") }
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth().padding(8.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.share_world), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Text(world.meta.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                // Share sheet: sends an https link that bounces to voxels://join/<worldId>?owner=<ownerId>
                // (public ids only) so the invitee can request access without you typing their device id.
                // The ids ride in the fragment, which browsers never send to the server.
                OutlinedButton(
                    onClick = {
                        val link = "https://maproj.org/vx/share#id=${world.meta.worldId}&owner=${world.meta.ownerDeviceId}"
                        ExternalIntents.shareText(context, link, context.getString(R.string.share_link_chooser))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconShare()
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share_link))
                }
                Text(stringResource(R.string.or_add_by_device_id), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it },
                    label = { Text(stringResource(R.string.recipient_device_id)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(UiR.string.cancel)) }
                    Button(onClick = { onSend(recipient.trim()) }, enabled = recipient.isNotBlank(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.send_invite)) }
                }
            }
        }
    }
}

@Composable
fun WorldCreatorScreen(
    onBack: () -> Unit,
    onCreate: (name: String, seedText: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.create_world), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.world_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.PersonName),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = seed,
                onValueChange = { seed = it },
                label = { Text(stringResource(R.string.seed)) },
                placeholder = { Text(stringResource(R.string.leave_blank_for_random)) },
                supportingText = { Text(stringResource(R.string.a_number_or_any_text_hashed_to_a_seed)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(stringResource(UiR.string.cancel)) }
                Button(onClick = { onCreate(name, seed) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.create_play)) }
            }
        }
    }
}
