package com.vayunmathur.communicate.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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

/**
 * One thread from its cached pages (offline-safe). Opening it asks the
 * phone for the latest page and queues mark-read; "Load older" pages
 * backwards with the cache's cursor. Excluded (disappearing) content is
 * labelled, never shown.
 */
@Composable
fun WearThreadScreen(
    store: WearCommunicateStore,
    threadKey: String,
    onReply: (String) -> Unit,
    onBack: () -> Unit,
) {
    val snapshot by store.snapshot.collectAsState()
    val pages by store.pages.collectAsState()
    val thread = snapshot.threads.firstOrNull { it.key == threadKey }
    val cache = pages[threadKey]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(threadKey) {
        WearLinkBridge.requestThreadPage(context, threadKey)
    }

    ScalingLazyColumn(state = rememberScalingLazyListState()) {
        item {
            Text(
                thread?.displayName?.ifBlank { thread.address }.orEmpty()
                    .ifBlank { stringResource(R.string.app_name) },
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (thread == null) {
            item {
                Text(
                    stringResource(R.string.wear_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            if (thread.contentExcluded) {
                item {
                    Text(
                        stringResource(R.string.wear_content_excluded),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            if (cache?.hasMore == true) {
                item {
                    Chip(
                        onClick = {
                            scope.launch {
                                WearLinkBridge.requestThreadPage(
                                    context, threadKey, cache.oldestTimestampMillis,
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.wear_load_older), maxLines = 1) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }
            if (cache == null || cache.messages.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.wear_no_phone),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
            } else {
                items(cache.messages, key = { it.messageKey }) { message ->
                    val text = when {
                        message.contentExcluded ->
                            stringResource(R.string.wear_content_excluded)
                        message.mediaLabel != null && message.body.isBlank() ->
                            "${message.mediaLabel} · ${stringResource(R.string.wear_media_on_phone)}"
                        message.body.isBlank() -> "·"
                        else -> message.body +
                            if (message.bodyTruncated) {
                                " ${stringResource(R.string.wear_body_truncated)}"
                            } else {
                                ""
                            }
                    }
                    Text(
                        (if (message.outgoing) "You: " else "") + text,
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                Chip(
                    onClick = { onReply(thread.key) },
                    label = { Text(stringResource(R.string.wear_reply), maxLines = 1) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            if (thread.unreadCount > 0) {
                item {
                    Chip(
                        onClick = { store.queueMarkRead(thread) },
                        label = { Text(stringResource(R.string.wear_mark_read), maxLines = 1) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }
        }
        item {
            Chip(
                onClick = onBack,
                label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
