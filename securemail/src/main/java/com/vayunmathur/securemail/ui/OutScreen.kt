package com.vayunmathur.securemail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SwipeActionSide
import com.vayunmathur.library.ui.SwipeActionsBox
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.securemail.data.OutboxMessage
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.platform.Draft
import com.vayunmathur.securemail.platform.DraftsController

/** Callbacks for the Out page. */
class OutActions(
    val onCompose: () -> Unit,
    val onOpenDraft: (Draft) -> Unit,
    val onDeleteDraft: (String) -> Unit,
    val onRestoreDraft: (Draft) -> Unit,
    val onRetryOutbox: () -> Unit,
    val onOpenSent: (String) -> Unit,
    val onTrashSent: (List<String>) -> Unit,
    val onUntrashSent: (List<String>) -> Unit,
    val onSetRead: (List<String>, Boolean) -> Unit,
)

/**
 * Everything outgoing on one page, no app bar: Drafts, then the Outbox queue,
 * then Sent, each under a title and separated by dividers.
 */
@Composable
fun OutScreen(
    drafts: List<StoredMessage>,
    outbox: List<OutboxMessage>,
    sent: List<StoredMessage>,
    actions: OutActions,
) {
    val messenger = rememberMessenger()
    AppScaffold(
        topBar = {},
        floatingActionButton = { FloatingActionButton(onClick = actions.onCompose) { IconAdd() } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            section("Drafts", drafts.isEmpty(), "No drafts.")
            itemsIndexed(drafts, key = { _, row -> "d-${row.id}" }) { i, row ->
                val draft = DraftsController.decode(row)
                DraftRow(
                    draft = draft,
                    onOpen = { actions.onOpenDraft(draft) },
                    onDelete = {
                        actions.onDeleteDraft(draft.id)
                        messenger.show("Draft deleted", actionLabel = "Undo") { actions.onRestoreDraft(draft) }
                    },
                )
                if (i < drafts.lastIndex) HorizontalDivider()
            }
            item(key = "div-outbox") { SectionDivider() }
            section("Outbox", outbox.isEmpty(), "Nothing waiting to send.")
            if (outbox.isNotEmpty()) {
                item(key = "retry") {
                    TextButton(onClick = actions.onRetryOutbox, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Retry all")
                    }
                }
            }
            itemsIndexed(outbox, key = { _, entry -> "o-${entry.rowId}" }) { i, entry ->
                OutboxRow(entry)
                if (i < outbox.lastIndex) HorizontalDivider()
            }
            item(key = "div-sent") { SectionDivider() }
            section("Sent", sent.isEmpty(), "Nothing sent yet.")
            items(sent, key = { "s-${it.id}" }) { msg ->
                MessageRow(
                    msg = msg,
                    showRecipient = true,
                    isSelected = false,
                    onClick = { actions.onOpenSent(msg.id) },
                    onLongClick = {},
                    onToggleRead = { actions.onSetRead(listOf(msg.id), !msg.isRead) },
                    onSwipeDelete = {
                        actions.onTrashSent(listOf(msg.id))
                        messenger.show("Moved to Trash", actionLabel = "Undo") { actions.onUntrashSent(listOf(msg.id)) }
                    },
                    inTrash = false,
                )
                HorizontalDivider()
            }
        }
    }
}

private fun LazyListScope.section(title: String, empty: Boolean, emptyText: String) {
    item(key = "h-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
    }
    if (empty) {
        item(key = "e-$title") {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp), thickness = 4.dp)
}

@Composable
private fun DraftRow(draft: Draft, onOpen: () -> Unit, onDelete: () -> Unit) {
    SwipeActionsBox(
        enableStartToEnd = false,
        onEndToStart = onDelete,
        background = { side ->
            if (side == SwipeActionSide.EndToStart) {
                Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                    IconDelete(Modifier.size(24.dp))
                }
            }
        },
    ) {
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 4.dp),
            supportingContent = {
                Column {
                    val recipients = (draft.to + draft.cc + draft.bcc).joinToString(", ")
                    Text(
                        if (recipients.isEmpty()) "No recipients" else "To: $recipients",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        draft.body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        ) {
            Text(
                draft.subject.ifBlank { "(no subject)" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OutboxRow(entry: OutboxMessage) {
    ListItem(
        modifier = Modifier.padding(start = 4.dp),
        supportingContent = {
            Column {
                Text(
                    "To: ${entry.toJson}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.lastError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingContent = {
            Text(
                "${entry.attemptCount} ${if (entry.attemptCount == 1) "try" else "tries"}",
                style = MaterialTheme.typography.labelSmall,
            )
        },
    ) {
        Text(
            entry.subject.ifBlank { "(no subject)" },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
