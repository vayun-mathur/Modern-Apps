package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconPersonAdd
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateCallLogEntry
import com.vayunmathur.communicate.data.CommunicateCallType
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.deleteCallLog
import com.vayunmathur.communicate.data.loadCallLogsMerged
import com.vayunmathur.communicate.data.placeCall
import com.vayunmathur.communicate.domain.callDurationParts
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Call-log list, stateless: rows plus the delete-confirm dialog. Split out so
 * screenshot previews can render entries without the call-log provider, the
 * polling tick, or the permission gates above.
 */
@Composable
fun CallLogsContent(
    entries: List<CommunicateCallLogEntry>,
    modifier: Modifier = Modifier,
    onCall: (CommunicateCallLogEntry) -> Unit,
    onDelete: (CommunicateCallLogEntry) -> Unit,
) {
    var pendingDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<CommunicateCallLogEntry?>(null) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(entries, key = { it.id }) { entry ->
            CallLogRow(
                entry = entry,
                onClick = { onCall(entry) },
                onDelete = { pendingDelete = entry },
            )
        }
    }
    val toDelete = pendingDelete
    if (toDelete != null) {
        com.vayunmathur.library.ui.ConfirmDialog(
            title = stringResource(R.string.delete_call_log_title),
            message = stringResource(R.string.delete_call_log_message),
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
private fun CallLogRow(entry: CommunicateCallLogEntry, onClick: () -> Unit, onDelete: () -> Unit = {}) {
    val context = LocalContext.current
    val title = entry.displayName ?: entry.phoneNumber
    ListItem(
        content = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (entry.type == CommunicateCallType.Missed) FontWeight.Bold else FontWeight.Medium,
                )
                LineBadge(entry.line, modifier = Modifier.padding(start = 6.dp), subscriptionId = entry.subscriptionId)
            }
        },
        supportingContent = {
            Column {
                Text(entry.phoneNumber, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${entry.type.label()} · ${formatDateTime(context, entry.timestampMillis)} · " +
                        callDurationLabel(entry.durationSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.type == CommunicateCallType.Missed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        leadingContent = { IconForCallType(entry.type) },
        trailingContent = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                com.vayunmathur.library.ui.IconButton(onClick = onClick) { IconCall() }
                com.vayunmathur.library.ui.OverflowMenu(icon = { IconMoreVert() }) {
                    // The provider already resolved a cached name for anyone saved, and withheld calls
                    // arrive as a placeholder with no digits — neither can be turned into a contact.
                    if (entry.displayName == null && entry.phoneNumber.any { it.isDigit() }) {
                        Item(
                            text = stringResource(R.string.contact_create_new),
                            leadingIcon = { IconPersonAdd() },
                            onClick = { ContactIntents.createNew(context, entry.phoneNumber) },
                        )
                        Item(
                            text = stringResource(R.string.contact_add_to_existing),
                            leadingIcon = { IconPerson() },
                            onClick = { ContactIntents.addToExisting(context, entry.phoneNumber) },
                        )
                    }
                    Item(
                        text = stringResource(com.vayunmathur.library.ui.R.string.delete),
                        leadingIcon = { IconDelete() },
                        onClick = onDelete,
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun IconForCallType(type: CommunicateCallType) {
    val tint = if (type == CommunicateCallType.Missed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    IconCall(tint = tint)
}

@Composable
private fun callDurationLabel(durationSeconds: Long): String {
    val parts = callDurationParts(durationSeconds)
    val shown = listOfNotNull(
        parts.hours?.let { pluralStringResource(R.plurals.duration_hours, it, it) },
        parts.minutes?.let { pluralStringResource(R.plurals.duration_minutes, it, it) },
        parts.seconds?.let { pluralStringResource(R.plurals.duration_seconds, it, it) },
    )
    return if (shown.size == 2) {
        stringResource(R.string.duration_two_units, shown[0], shown[1])
    } else {
        shown.first()
    }
}

@Composable
private fun CommunicateCallType.label(): String = when (this) {
    CommunicateCallType.Incoming -> stringResource(R.string.incoming_call)
    CommunicateCallType.Outgoing -> stringResource(R.string.outgoing_call)
    CommunicateCallType.Missed -> stringResource(R.string.missed_call)
    CommunicateCallType.Rejected -> stringResource(R.string.rejected_call)
    CommunicateCallType.Blocked -> stringResource(R.string.blocked_call)
    CommunicateCallType.Voicemail -> stringResource(R.string.voicemail_call)
    CommunicateCallType.Unknown -> stringResource(R.string.unknown_call)
}
