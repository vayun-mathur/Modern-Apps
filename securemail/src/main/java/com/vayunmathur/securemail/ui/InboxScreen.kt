package com.vayunmathur.securemail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDeleteForever
import com.vayunmathur.library.ui.IconMarkRead
import com.vayunmathur.library.ui.IconMarkUnread
import com.vayunmathur.library.ui.IconRestore
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.platform.MailUiState

/** Row actions for a folder list; ids are always the affected messages. */
class FolderActions(
    val onOpen: (String) -> Unit,
    val onCompose: () -> Unit,
    val onSetRead: (List<String>, Boolean) -> Unit,
    val onTrash: (List<String>) -> Unit,
    val onMoveTo: (List<String>, MailFolder) -> Unit,
    val onRestore: (List<String>) -> Unit,
    val onDeleteForever: (List<String>) -> Unit,
    val onEmptyTrash: () -> Unit,
    val onConsumeMessage: () -> Unit,
)

/**
 * A vault folder (Inbox/Sent/Spam/Trash), laid out like the email app's list:
 * no app bar, unread stripe + bold subject, swipe right to toggle read, swipe
 * left to trash (undoable) or, in Trash, delete forever (confirmed). Long
 * press starts multi-select with a contextual action bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    state: MailUiState,
    folder: MailFolder,
    actions: FolderActions,
    messages: List<StoredMessage> = emptyList(),
) {
    val messenger = rememberMessenger()
    ConsumeMessage(state.message, messenger, actions.onConsumeMessage)
    var selected by remember(folder) { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    // Bumped when a delete is cancelled so the swiped-away row snaps back.
    var swipeGeneration by remember { mutableIntStateOf(0) }
    val inTrash = folder == MailFolder.TRASH

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    fun trashOrDelete(ids: List<String>) {
        if (inTrash) {
            confirmDelete = ids
        } else {
            actions.onTrash(ids)
            messenger.show(
                if (ids.size == 1) "Moved to Trash" else "${ids.size} moved to Trash",
                actionLabel = "Undo",
            ) { actions.onMoveTo(ids, folder) }
        }
        selected = emptySet()
    }

    AppScaffold(
        topBar = {
            if (selected.isNotEmpty()) {
                SelectionBar(
                    count = selected.size,
                    inTrash = inTrash,
                    onClose = { selected = emptySet() },
                    onSetRead = { read -> actions.onSetRead(selected.toList(), read); selected = emptySet() },
                    onRestore = { actions.onRestore(selected.toList()); selected = emptySet() },
                    onDelete = { trashOrDelete(selected.toList()) },
                )
            }
        },
        floatingActionButton = {
            if (selected.isEmpty()) FloatingActionButton(onClick = actions.onCompose) { IconAdd() }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (inTrash && messages.isNotEmpty() && selected.isEmpty()) {
                TextButton(
                    onClick = { confirmDelete = messages.map { it.id } },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("Empty trash") }
            }
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        emptyText(folder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(messages, key = { it.id }) { msg ->
                        key(swipeGeneration) {
                            MessageRow(
                                msg = msg,
                                showRecipient = folder == MailFolder.SENT,
                                isSelected = msg.id in selected,
                                onClick = {
                                    if (selected.isNotEmpty()) selected = selected.toggle(msg.id) else actions.onOpen(msg.id)
                                },
                                onLongClick = { selected = selected.toggle(msg.id) },
                                onToggleRead = { actions.onSetRead(listOf(msg.id), !msg.isRead) },
                                onSwipeDelete = { trashOrDelete(listOf(msg.id)) },
                                inTrash = inTrash,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    confirmDelete?.let { ids ->
        val everything = ids.size == messages.size
        AlertDialog(
            onDismissRequest = { confirmDelete = null; swipeGeneration++ },
            title = { Text(if (everything) "Empty trash?" else "Delete forever?") },
            text = {
                Text(
                    if (ids.size == 1) "This message will be permanently deleted from this device."
                    else "${ids.size} messages will be permanently deleted from this device.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (everything) actions.onEmptyTrash() else actions.onDeleteForever(ids)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null; swipeGeneration++ }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    inTrash: Boolean,
    onClose: () -> Unit,
    onSetRead: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = { IconButton(onClick = onClose) { IconClose() } },
        actions = {
            IconButton(onClick = { onSetRead(true) }) { IconMarkRead() }
            IconButton(onClick = { onSetRead(false) }) { IconMarkUnread() }
            if (inTrash) {
                IconButton(onClick = onRestore) { IconRestore() }
                IconButton(onClick = onDelete) { IconDeleteForever() }
            } else {
                IconButton(onClick = onDelete) { IconDelete() }
            }
        },
    )
}

private fun emptyText(folder: MailFolder): String = when (folder) {
    MailFolder.INBOX -> "No mail yet. Share your address to receive encrypted mail."
    MailFolder.SENT -> "Nothing sent yet."
    MailFolder.SPAM -> "No spam. Verification failures land here."
    MailFolder.TRASH -> "Trash is empty."
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id
