package com.vayunmathur.messages.ui

import androidx.compose.foundation.background as foundationBackground
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.R
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.data.Conversation
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.data.MessagesDatabase
import com.vayunmathur.messages.util.MessagesSessionManager
import com.vayunmathur.messages.util.MessagesViewModel
import com.vayunmathur.messages.util.SourceConnectionState
import com.vayunmathur.messages.util.displayTitle
import com.vayunmathur.messages.util.isMessageRequest
import java.text.DateFormat
import java.util.Date

/**
 * Unified inbox over conversations from both sources. Sorted by recency.
 *
 * Source is surfaced as a small chip on each row so it's always obvious
 * whether a reply will go via the user's SIM (Messages) or their Voice
 * number. We deliberately don't merge by phone number — same peer on
 * two different lines is still two threads.
 *
 * When a source isn't connected (no pairing / no login), a "setup" card
 * shows above the list inviting the user to complete the flow.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun InboxScreen(
    backStack: NavBackStack<Route>,
    vm: MessagesViewModel,
    db: MessagesDatabase,
) {
    val conversations by db.conversationDao().observeAll().collectAsState(initial = emptyList())
    val connectionStates by vm.connectionStates.collectAsState()
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var showSourcePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                actions = {
                    IconButton(onClick = { backStack.add(Route.Settings) }) {
                        IconSettings()
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showSourcePicker = true }) {
                com.vayunmathur.library.ui.IconAdd()
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (conversations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.inbox_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    // Room for the FAB so it doesn't cover the last row.
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(conversations, key = { it.conversation.id }) { conv ->
                        ConversationRow(
                            conversation = conv.conversation,
                            lastMessageTimestamp = conv.lastMessageTimestamp,
                            onClick = { backStack.add(Route.Conversation(conv.conversation.id)) },
                            onLongPress = { pendingDelete = conv.conversation },
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }

    if (showSourcePicker) {
        com.vayunmathur.library.ui.AlertDialog(
            onDismissRequest = { showSourcePicker = false },
            title = { Text("New conversation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Only offer services that are actually connected.
                    val connected = connectionStates
                        .filterValues { it == SourceConnectionState.Connected }
                        .keys
                        .toList()
                    if (connected.isEmpty()) {
                        Text("No connected services. Set one up in Settings first.")
                    } else {
                        Text("Choose which service to send from:")
                        connected.forEach { source ->
                            com.vayunmathur.library.ui.TextButton(onClick = {
                                showSourcePicker = false
                                backStack.add(Route.Compose(initialSource = source.name))
                            }, modifier = Modifier.fillMaxWidth()) { Text(sourceLabel(source)) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                com.vayunmathur.library.ui.TextButton(onClick = {
                    showSourcePicker = false
                }) { Text("Cancel") }
            },
        )
    }

    pendingDelete?.let { conv ->
        com.vayunmathur.library.ui.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = {
                Text(
                    "This deletes the chat for ${conv.peerName ?: "this contact"} on your phone too. " +
                        "Existing messages can't be recovered.",
                )
            },
            confirmButton = {
                com.vayunmathur.library.ui.TextButton(onClick = {
                    vm.deleteConversation(conv.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                com.vayunmathur.library.ui.TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/** Shows source-specific setup cards at the top of the inbox when
 *  any source needs setup. */
@Composable
private fun SetupPrompts(
    states: Map<MessageSource, SourceConnectionState>,
    onPairMessages: () -> Unit,
    onSetupVoice: () -> Unit,
    onSetupTelegram: () -> Unit,
    onSetupSignal: () -> Unit,
    onSetupWhatsApp: () -> Unit,
    onSetupMessenger: () -> Unit,
    onSetupInstagram: () -> Unit,
) {
    val msgsState = states[MessageSource.MESSAGES_WEB]
    if (msgsState is SourceConnectionState.NeedsSetup || msgsState is SourceConnectionState.Disconnected) {
        SetupCard(
            title = stringResource(R.string.inbox_setup_messages_title),
            description = stringResource(R.string.inbox_setup_messages_desc),
            actionLabel = stringResource(R.string.inbox_setup_action),
            onAction = onPairMessages,
        )
    }
    val voiceState = states[MessageSource.VOICE]
    if (voiceState is SourceConnectionState.NeedsSetup || voiceState is SourceConnectionState.Disconnected) {
        SetupCard(
            title = stringResource(R.string.inbox_setup_voice_title),
            description = stringResource(R.string.inbox_setup_voice_desc),
            actionLabel = stringResource(R.string.inbox_setup_voice_action),
            onAction = onSetupVoice,
        )
    }
    val telegramState = states[MessageSource.TELEGRAM]
    if (telegramState is SourceConnectionState.NeedsSetup || telegramState is SourceConnectionState.Disconnected) {
        SetupCard(
            title = stringResource(R.string.inbox_setup_telegram_title),
            description = stringResource(R.string.inbox_setup_telegram_desc),
            actionLabel = stringResource(R.string.inbox_setup_telegram_action),
            onAction = onSetupTelegram,
        )
    }
    val signalState = states[MessageSource.SIGNAL]
    if (signalState is SourceConnectionState.NeedsSetup || signalState is SourceConnectionState.Disconnected) {
        SetupCard(
            title = stringResource(R.string.inbox_setup_signal_title),
            description = stringResource(R.string.inbox_setup_signal_desc),
            actionLabel = stringResource(R.string.inbox_setup_signal_action),
            onAction = onSetupSignal,
        )
    }
    val whatsappState = states[MessageSource.WHATSAPP]
    if (whatsappState is SourceConnectionState.NeedsSetup || whatsappState is SourceConnectionState.Disconnected) {
        SetupCard(
            title = stringResource(R.string.inbox_setup_whatsapp_title),
            description = stringResource(R.string.inbox_setup_whatsapp_desc),
            actionLabel = stringResource(R.string.inbox_setup_whatsapp_action),
            onAction = onSetupWhatsApp,
        )
    }
    val messengerState = states[MessageSource.MESSENGER]
    if (messengerState is SourceConnectionState.NeedsSetup || messengerState is SourceConnectionState.Disconnected) {
        SetupCard(
            title = stringResource(R.string.inbox_setup_messenger_title),
            description = stringResource(R.string.inbox_setup_messenger_desc),
            actionLabel = stringResource(R.string.inbox_setup_messenger_action),
            onAction = onSetupMessenger,
        )
    }
    val instagramState = states[MessageSource.INSTAGRAM]
    if (instagramState is SourceConnectionState.NeedsSetup || instagramState is SourceConnectionState.Disconnected) {
        SetupCard(
            title = stringResource(R.string.inbox_setup_instagram_title),
            description = stringResource(R.string.inbox_setup_instagram_desc),
            actionLabel = stringResource(R.string.inbox_setup_instagram_action),
            onAction = onSetupInstagram,
        )
    }
}

@Composable
private fun SetupCard(
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onAction),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                actionLabel,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun sourceLabel(source: MessageSource): String = when (source) {
    MessageSource.MESSAGES_WEB -> "Google Messages"
    MessageSource.VOICE -> "Google Voice"
    MessageSource.TELEGRAM -> "Telegram"
    MessageSource.SIGNAL -> "Signal"
    MessageSource.WHATSAPP -> "WhatsApp"
    MessageSource.MESSENGER -> "Messenger"
    MessageSource.INSTAGRAM -> "Instagram"
    MessageSource.RCS -> "RCS"
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    lastMessageTimestamp: Long,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    ListItem(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.displayTitle(),
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // SMS / RCS chip (per-conversation transport). Hidden when
                // the relay didn't tell us — most rows will have one.
                conversation.conversationType?.let { TypeChip(it) }
            }
        },
        supportingContent = {
            if (conversation.isMessageRequest()) {
                MessageRequestChip()
            } else {
                conversation.lastMessagePreview?.let {
                    Text(it, maxLines = 1)
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                if (lastMessageTimestamp > 0L) {
                    Text(
                        formatTimestamp(lastMessageTimestamp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (conversation.unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    UnreadBadge(conversation.unreadCount)
                }
            }
        },
        leadingContent = { Avatar(conversation.peerName, conversation.avatarUrl, conversation.isGroup) },
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongPress,
        ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun MessageRequestChip() {
    val color = MaterialTheme.colorScheme.error
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        contentColor = color,
    ) {
        Text(
            "Message request",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TypeChip(label: String) {
    val color = when (label) {
        "RCS" -> MaterialTheme.colorScheme.primary
        "SMS" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        contentColor = color,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SourceChip(source: MessageSource) {
    val (label, color) = when (source) {
        MessageSource.MESSAGES_WEB -> "Phone" to MaterialTheme.colorScheme.tertiary
        MessageSource.VOICE -> "Voice" to MaterialTheme.colorScheme.primary
        MessageSource.TELEGRAM -> "Telegram" to MaterialTheme.colorScheme.secondary
        MessageSource.SIGNAL -> "Signal" to MaterialTheme.colorScheme.secondary
        MessageSource.WHATSAPP -> "WhatsApp" to MaterialTheme.colorScheme.secondary
        MessageSource.MESSENGER -> "Messenger" to MaterialTheme.colorScheme.secondary
        MessageSource.INSTAGRAM -> "Instagram" to MaterialTheme.colorScheme.secondary
        MessageSource.RCS -> "RCS" to MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        contentColor = color,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Avatar(name: String?, photoUrl: String?, isGroup: Boolean) {
    val color = MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        when {
            photoUrl != null -> coil.compose.AsyncImage(
                model = photoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            isGroup -> Text(
                "…",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            else -> {
                val initials = (name ?: "?").trim().take(2).uppercase()
                Text(
                    initials,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            count.toString(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatTimestamp(ts: Long): String {
    val now = System.currentTimeMillis()
    val daysAgo = (now - ts) / (24L * 60 * 60 * 1000)
    val date = Date(ts)
    return when {
        daysAgo < 1L -> DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
        daysAgo < 7L -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(date)
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(date)
    }
}

// Small helper so we can call Modifier.background() at the call sites
// without adding a separate import everywhere it's used in this file.
private fun Modifier.background(color: Color): Modifier = this then foundationBackground(color)
