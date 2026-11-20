package com.vayunmathur.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.R
import com.vayunmathur.email.data.EmailPreview
import com.vayunmathur.email.data.accountColor
import com.vayunmathur.email.data.senderDisplayName
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.MessageListActions
import com.vayunmathur.email.platform.MessageListUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconAttachment
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconMarkRead
import com.vayunmathur.library.ui.IconMarkUnread
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageListScreen(
    state: MessageListUiState,
    actions: MessageListActions,
    onMessageClick: (EmailPreview) -> Unit = {},
    onComposeClick: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    initialSearching: Boolean = false,
) {
    var isSearching by remember { mutableStateOf(initialSearching) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<EmailPreview?>(null) }

    LaunchedEffect(pendingDelete) {
        val msg = pendingDelete ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(message = "Message deleted", actionLabel = "Undo", duration = com.vayunmathur.library.ui.SnackbarDuration.Short)
        if (result != com.vayunmathur.library.ui.SnackbarResult.ActionPerformed) {
            actions.deleteMessage(msg.accountEmail, msg.folderName, msg.id)
        }
        pendingDelete = null
    }

    androidx.activity.compose.BackHandler(enabled = isSearching || state.selectedUids.isNotEmpty()) {
        if (state.selectedUids.isNotEmpty()) actions.clearSelection()
        else if (state.searchQuery.isNotEmpty()) actions.setSearchQuery("")
        else isSearching = false
    }

    LaunchedEffect(state.searchQuery) {
        if (state.searchQuery.isNotEmpty()) {
            kotlinx.coroutines.delay(1500)
            if (state.messages.isNotEmpty()) actions.requestAiSummary(state.messages)
        }
    }

    val selectionActive = state.selectedUids.isNotEmpty()
    AppScaffold(
        title = {
            when {
                selectionActive -> Text(pluralStringResource(R.plurals.selected_count, state.selectedUids.size, state.selectedUids.size))
                isSearching -> CommonSearchBar(value = state.searchQuery, onValueChange = { actions.setSearchQuery(it) }, padding = PaddingValues(0.dp))
                else -> Text(if (state.selectedAccountEmail == null) stringResource(R.string.unified_inbox_label) else state.selectedFolderName)
            }
        },
        navigationIcon = {
            when {
                selectionActive -> IconButton(onClick = { actions.clearSelection() }) { IconClose() }
                isSearching -> IconNavigation { isSearching = false; actions.setSearchQuery("") }
                else -> IconButton(onClick = onOpenDrawer) { IconMenu() }
            }
        },
        actions = {
            when {
                selectionActive -> {
                    IconButton(onClick = {
                        val account = state.selectedAccountEmail ?: state.messages.firstOrNull { it.id in state.selectedUids }?.accountEmail ?: return@IconButton
                        actions.bulkMarkAsRead(account, state.selectedUids.toList(), true)
                    }) { IconMarkRead() }
                    IconButton(onClick = {
                        val account = state.selectedAccountEmail ?: state.messages.firstOrNull { it.id in state.selectedUids }?.accountEmail ?: return@IconButton
                        actions.bulkMarkAsRead(account, state.selectedUids.toList(), false)
                    }) { IconMarkUnread() }
                }
                isSearching -> {}
                else -> IconButton(onClick = { isSearching = true }) { IconSearch() }
            }
        },
        floatingActionButton = {
            if (!selectionActive) {
                FloatingActionButton(onClick = onComposeClick) { IconAdd() }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                if (state.isSyncing) {
                    LinearProgressIndicator(progress = { state.syncProgress }, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
            var msgFilter by remember { mutableIntStateOf(0) }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.vayunmathur.library.ui.FilterChip(selected = msgFilter == 0, onClick = { msgFilter = 0 }, label = { Text(stringResource(R.string.all)) })
                com.vayunmathur.library.ui.FilterChip(selected = msgFilter == 1, onClick = { msgFilter = 1 }, label = { Text(stringResource(R.string.unread)) })
                com.vayunmathur.library.ui.FilterChip(selected = msgFilter == 2, onClick = { msgFilter = 2 }, label = { Text(stringResource(R.string.attachments)) })
            }
            val filteredMessages = remember(state.messages, msgFilter) {
                when (msgFilter) {
                    1 -> state.messages.filter { !it.isRead }
                    2 -> state.messages.filter { it.hasAttachments }
                    else -> state.messages
                }
            }
            // The thread screen opens on a thread, not a message, and a folder can hold several
            // messages of the same thread - so only the newest row of each thread carries the key.
            // Two rows holding one key leave the morph no single origin to travel from.
            val subjectMorphKeys = remember(filteredMessages) {
                filteredMessages
                    .distinctBy { "${it.accountEmail}|${it.threadId ?: it.id}" }
                    .associate { msg ->
                        "${msg.accountEmail}|${msg.folderName}|${msg.id}" to
                            "email-subject-${msg.accountEmail}-${msg.threadId ?: msg.id}"
                    }
            }
            com.vayunmathur.library.ui.AppPullToRefreshBox(
                isRefreshing = state.isSyncing,
                onRefresh = { actions.refresh(context) },
                modifier = Modifier.fillMaxSize(),
                // The box sits below the top bar, sync bar, and filter-chip row,
                // so only a small clearance is needed; the slot stays centered.
                topClearance = 8.dp,
            ) {
                if (state.messages.isEmpty() && state.searchQuery.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.no_messages_found_pull_down_to_refresh),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (state.searchQuery.isNotEmpty() && (state.aiSummaryLoading || state.aiSummary != null)) {
                            item(key = "ai_summary") {
                                Card(modifier = Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(stringResource(R.string.ai_summary), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Spacer(Modifier.height(8.dp))
                                        if (state.aiSummaryLoading) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                Spacer(Modifier.width(8.dp))
                                                Text(stringResource(R.string.generating_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                            }
                                        } else {
                                            Text(state.aiSummary ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                        }
                                    }
                                }
                            }
                        }
                        items(filteredMessages, key = { "${it.accountEmail}|${it.folderName}|${it.id}" }) { message ->
                            val isPending = pendingDelete?.let { it.id == message.id && it.accountEmail == message.accountEmail && it.folderName == message.folderName } == true
                            if (!isPending) {
                                val subjectKey = subjectMorphKeys["${message.accountEmail}|${message.folderName}|${message.id}"]
                                val accountBandColor = Color(accountColor(message.accountEmail))
                                val isSelected = message.id in state.selectedUids
                                val currentMessage by rememberUpdatedState(message)
                                com.vayunmathur.library.ui.SwipeActionsBox(
                                    onEndToStart = { pendingDelete = currentMessage },
                                    onStartToEnd = {
                                        actions.markAsRead(currentMessage.accountEmail, currentMessage.folderName, currentMessage.id, !currentMessage.isRead)
                                    },
                                    background = { side ->
                                        when (side) {
                                            com.vayunmathur.library.ui.SwipeActionSide.EndToStart ->
                                                Box(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) { com.vayunmathur.library.ui.IconDelete() }
                                            com.vayunmathur.library.ui.SwipeActionSide.StartToEnd ->
                                                Box(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) { IconMarkRead() }
                                            else -> {}
                                        }
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .combinedClickable(onClick = { if (state.selectedUids.isNotEmpty()) actions.toggleMessageSelection(message.id) else onMessageClick(message) }, onLongClick = { actions.toggleMessageSelection(message.id) })
                                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                                            .height(IntrinsicSize.Min)
                                    ) {
                                        if (state.selectedAccountEmail == null) {
                                            Surface(modifier = Modifier.width(4.dp).fillMaxHeight(), color = accountBandColor) {}
                                        }
                                        Surface(modifier = Modifier.width(4.dp).fillMaxHeight(), color = if (message.isRead) Color.Transparent else MaterialTheme.colorScheme.primary) {}
                                        ListItem(
                                            leadingContent = null,
                                            content = { Text(text = message.subject, style = MaterialTheme.typography.bodyLarge, fontWeight = if (message.isRead) androidx.compose.ui.text.font.FontWeight.Normal else androidx.compose.ui.text.font.FontWeight.Bold, modifier = if (subjectKey == null) Modifier else Modifier.sharedText(subjectKey)) },
                                            supportingContent = {
                                                Column {
                                                    Text(text = senderDisplayName(message.from), style = MaterialTheme.typography.titleSmall)
                                                    val preview = remember(message.peekContent) { message.peekContent.take(100) }
                                                    Text(text = preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, overflow = TextOverflow.Ellipsis)
                                                }
                                            },
                                            trailingContent = {
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(text = message.date.substringBefore(" "), style = MaterialTheme.typography.labelSmall)
                                                    if (message.hasAttachments) IconAttachment(modifier = Modifier.size(16.dp))
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
