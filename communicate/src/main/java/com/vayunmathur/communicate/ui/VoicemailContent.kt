package com.vayunmathur.communicate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.VoicemailEntry
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconVoicemail
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OverflowMenu
import com.vayunmathur.library.ui.Text

/**
 * Voicemail inbox list, stateless: rows plus the delete-confirm dialog. Split
 * out so screenshot previews can render entries without the provider, the
 * permission gates, or the player above — same split as [CallLogsContent].
 *
 * [expandedId] is hoisted so only one row's player is composed at a time.
 * [onToggleExpand] also serves as the mark-read hook: opening a row marks it.
 * [onPlayed] fires when audio starts, so playback also marks the row read
 * (covers rows opened while already read-flagged stale).
 */
@Composable
fun VoicemailContent(
    entries: List<VoicemailEntry>,
    expandedId: Long?,
    onToggleExpand: (VoicemailEntry) -> Unit,
    onDelete: (VoicemailEntry) -> Unit,
    onPlayed: (VoicemailEntry) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<VoicemailEntry?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(entries, key = { it.id }) { entry ->
            VoicemailRow(
                entry = entry,
                expanded = entry.id == expandedId,
                onToggleExpand = { onToggleExpand(entry) },
                onDelete = { pendingDelete = entry },
                onPlayed = { onPlayed(entry) },
            )
        }
    }
    val toDelete = pendingDelete
    if (toDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.delete_voicemail_title),
            message = stringResource(R.string.delete_voicemail_message),
            confirmLabel = stringResource(com.vayunmathur.library.ui.R.string.delete),
            dismissLabel = stringResource(com.vayunmathur.library.ui.R.string.cancel),
            destructive = true,
            onConfirm = {
                pendingDelete = null
                onDelete(toDelete)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun VoicemailRow(
    entry: VoicemailEntry,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDelete: () -> Unit,
    onPlayed: () -> Unit,
) {
    ListItem(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.phoneNumber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (entry.isRead) FontWeight.Medium else FontWeight.Bold,
                )
                LineBadge(entry.line, entry.subscriptionId, modifier = Modifier.padding(start = 6.dp))
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${formatDateTime(LocalContext.current, entry.timestampMillis)} · " +
                        stringResource(R.string.voicemail_duration, entry.durationSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (expanded && entry.transcript != null) {
                    Text(
                        entry.transcript,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        leadingContent = { IconVoicemail() },
        trailingContent = {
            OverflowMenu(icon = { IconMoreVert() }) {
                Item(
                    text = stringResource(com.vayunmathur.library.ui.R.string.delete),
                    leadingIcon = { IconDelete() },
                    onClick = onDelete,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onToggleExpand),
    )
    if (expanded) {
        if (entry.hasAudio) {
            VoicemailPlayer(
                audioUri = entry.contentUri,
                onPlayed = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
            )
        } else {
            Text(
                stringResource(R.string.voicemail_no_audio),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}
