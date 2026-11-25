package com.vayunmathur.communicate.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.communicate.wear.R
import com.vayunmathur.communicate.wear.data.WearCommunicateStore
import com.vayunmathur.communicate.wear.sync.WearLinkBridge
import kotlinx.coroutines.launch

/** Merged conversation list (all lines), most recent first, from the local snapshot (offline-safe). */
@Composable
fun WearConversationListScreen(
    store: WearCommunicateStore,
    onOpenThread: (String) -> Unit,
    onOpenCalls: () -> Unit,
    onOpenVoicemail: () -> Unit,
    onOpenPhoneOnly: () -> Unit,
) {
    val snapshot by store.snapshot.collectAsState()
    val pending by store.pendingOps.collectAsState()
    val alert by store.lastAlert.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var syncNote by remember { mutableStateOf<String?>(null) }
    val requestedText = stringResource(R.string.wear_sync_requested)
    val noPhoneText = stringResource(R.string.wear_no_phone)

    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        alert?.let { newMessage ->
            item {
                Chip(
                    onClick = {
                        store.clearAlert()
                        onOpenThread(newMessage.threadKey)
                    },
                    label = {
                        Text(
                            "${stringResource(R.string.wear_new_message)}: ${newMessage.displayName}",
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = {
                        if (newMessage.snippet.isNotBlank()) {
                            Text(newMessage.snippet, maxLines = 1)
                        }
                    },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        if (pending.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_pending, pending.size),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        syncNote?.let { note ->
            item {
                Text(
                    note,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        item {
            Chip(
                onClick = onOpenCalls,
                label = { Text(stringResource(R.string.wear_calls), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = onOpenVoicemail,
                label = { Text(stringResource(R.string.wear_voicemail), maxLines = 1) },
                secondaryLabel = {
                    if (snapshot.voicemailUnreadCount > 0) {
                        Text(
                            stringResource(R.string.wear_unread, snapshot.voicemailUnreadCount),
                            maxLines = 1,
                        )
                    }
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        if (snapshot.threads.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            items(snapshot.threads, key = { it.key }) { thread ->
                Chip(
                    onClick = { onOpenThread(thread.key) },
                    label = {
                        Text(
                            thread.displayName.ifBlank { thread.address },
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = {
                        val detail = when {
                            thread.contentExcluded ->
                                stringResource(R.string.wear_content_excluded)
                            thread.unreadCount > 0 -> "${thread.line} · " +
                                stringResource(R.string.wear_unread, thread.unreadCount) +
                                " · ${thread.snippet}"
                            else -> "${thread.line} · ${thread.snippet}"
                        }
                        Text(detail, maxLines = 1)
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        if (snapshot.truncated) {
            item {
                Text(
                    stringResource(R.string.wear_truncated),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        item {
            Chip(
                onClick = onOpenPhoneOnly,
                label = { Text(stringResource(R.string.wear_phone_only), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = {
                    scope.launch {
                        syncNote = if (WearLinkBridge.requestSync(context)) {
                            requestedText
                        } else {
                            noPhoneText
                        }
                    }
                },
                label = { Text(stringResource(R.string.wear_request_sync), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
