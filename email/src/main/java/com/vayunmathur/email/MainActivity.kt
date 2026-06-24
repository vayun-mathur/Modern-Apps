package com.vayunmathur.email

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)
        // Wake the outbox sender on every cold start: if the process was killed
        // between scheduled retries, this is what gets it going again.
        com.vayunmathur.email.data.OutboxSendWorker.runNow(this)
        // Kick the IMAP IDLE service so we get push notifications even when the
        // app is in the background. The service is a no-op if there are no
        // accounts yet (it'll just stopSelf).
        com.vayunmathur.email.data.ImapIdleService.start(this)
        // One-shot backfill for dateMillis on rows persisted before that column.
        com.vayunmathur.email.data.DateMillisBackfill.runIfNeeded(this)
        // Android 13+: request POST_NOTIFICATIONS so new-mail alerts can be shown.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7331)
            }
        }
        enableEdgeToEdge()
        setContent {
            val viewModel: EmailViewModel = viewModel()
            DynamicTheme {
                MainContent(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.vayunmathur.email.util.AppLifecycleTracker.isAppInForeground = true
    }

    override fun onStop() {
        super.onStop()
        com.vayunmathur.email.util.AppLifecycleTracker.isAppInForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val accountEmail = intent.getStringExtra("accountEmail")
        val threadId = intent.getStringExtra("threadId")
        when {
            accountEmail != null && threadId != null ->
                IntentState.navigationRoute = Route.MessageThread(accountEmail, threadId)
            intent.getBooleanExtra("compose", false) ->
                IntentState.navigationRoute = Route.Composer()
            intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SENDTO -> {
                val to = if (intent.action == Intent.ACTION_SENDTO) intent.data?.schemeSpecificPart ?: "" else ""
                IntentState.navigationRoute = Route.Composer(
                    to = to,
                    subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "",
                    body = intent.getStringExtra(Intent.EXTRA_TEXT) ?: "",
                )
            }
        }
    }
}

object IntentState {
    var navigationRoute by mutableStateOf<Route?>(null)
}

@Composable
fun MainContent(viewModel: EmailViewModel) {
    val accounts by viewModel.accounts.collectAsState(emptyList())
    if (accounts.isEmpty()) {
        // First run: jump straight into the add-account picker. The conditional
        // above auto-swaps us into EmailApp once the first account lands.
        com.vayunmathur.email.ui.AddAccountScreen(
            onBack = null,
            onAccountAdded = {},
        )
    } else {
        EmailApp(viewModel = viewModel)
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    object MessageList : Route
    @Serializable
    data class MessageThread(val accountEmail: String, val threadId: String) : Route
    @Serializable
    data class Composer(
        val to: String = "",
        val subject: String = "",
        val body: String = "",
        val inReplyTo: String? = null,
        val references: String? = null
    ) : Route
    @Serializable
    object Outbox : Route
    @Serializable
    object AddAccount : Route
    @Serializable
    object Settings : Route
}

@Composable
fun EmailApp(viewModel: EmailViewModel) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val context = LocalContext.current

    val accounts by viewModel.accounts.collectAsState(emptyList())
    val selectedAccountEmail by viewModel.selectedAccountEmail.collectAsState()
    val folders by viewModel.folders.collectAsState(emptyList())
    val selectedFolderName by viewModel.selectedFolderName.collectAsState()
    val outbox by viewModel.outbox.collectAsState(emptyList())

    val backStack = rememberNavBackStack<Route>(Route.MessageList)

    val navigationRoute = IntentState.navigationRoute
    LaunchedEffect(navigationRoute) {
        if (navigationRoute != null) {
            backStack.add(navigationRoute)
            IntentState.navigationRoute = null
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxHeight()) {
                    // Scrollable section: header, accounts, folders, outbox.
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text("Unified Inbox", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.all_accounts)) },
                            selected = selectedAccountEmail == null,
                            onClick = {
                                viewModel.selectAccount("")
                                backStack.reset(Route.MessageList)
                                scope.launch { drawerState.close() }
                            },
                            icon = { IconInbox() },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )

                        HorizontalDivider(Modifier.padding(vertical = 8.dp))

                        Text("Accounts", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                        accounts.forEach { account ->
                            NavigationDrawerItem(
                                label = { Text(account.email) },
                                selected = account.email == selectedAccountEmail,
                                onClick = {
                                    viewModel.selectAccount(account.email)
                                    backStack.reset(Route.MessageList)
                                    scope.launch { drawerState.close() }
                                },
                                icon = {
                                    Surface(
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        color = Color(account.getColor()),
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            IconMail(modifier = Modifier.size(16.dp), tint = Color.White)
                                        }
                                    }
                                },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                        }
                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.add_account)) },
                            selected = false,
                            onClick = {
                                backStack.add(Route.AddAccount)
                                scope.launch { drawerState.close() }
                            },
                            icon = { IconAdd() },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )

                        if (selectedAccountEmail != null) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text("Folders", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                            FolderList(folders, selectedFolderName ?: "INBOX") { folderName ->
                                viewModel.selectFolder(folderName)
                                backStack.reset(Route.MessageList)
                                scope.launch { drawerState.close() }
                            }
                        }

                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    if (outbox.isEmpty()) stringResource(R.string.outbox)
                                    else stringResource(R.string.outbox_with_count, outbox.size)
                                )
                            },
                            selected = false,
                            onClick = {
                                backStack.add(Route.Outbox)
                                scope.launch { drawerState.close() }
                            },
                            icon = { IconSend() },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.settings)) },
                            selected = false,
                            onClick = {
                                backStack.add(Route.Settings)
                                scope.launch { drawerState.close() }
                            },
                            icon = { com.vayunmathur.library.ui.IconSettings() },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }

                    // Pinned footer: logout stays visible no matter how long the list above is.
                    // Only show logout button when a specific account is selected (not in unified inbox)
                    if (selectedAccountEmail != null) {
                        HorizontalDivider()
                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.logout_current_account)) },
                            selected = false,
                            onClick = {
                                viewModel.logout(context)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        }
    ) {
        MainNavigation(backStack) {
            entry<Route.MessageList>(metadata = ListPage()) {
                MessageListScreen(
                    viewModel = viewModel,
                    onMessageClick = { msg ->
                        backStack.add(Route.MessageThread(msg.accountEmail, msg.threadId ?: msg.id.toString()))
                    },
                    onComposeClick = { backStack.add(Route.Composer()) },
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            entry<Route.MessageThread>(metadata = ListDetailPage()) { route ->
                MessageThreadScreen(
                    viewModel = viewModel,
                    accountEmail = route.accountEmail,
                    threadId = route.threadId,
                    onBack = { backStack.pop() },
                    onReply = { to, sub, ref ->
                        backStack.add(Route.Composer(to = to, subject = "Re: $sub", references = ref, inReplyTo = ref))
                    },
                    onForward = { sub, body ->
                        backStack.add(Route.Composer(subject = "Fwd: $sub", body = "\n\n---------- Forwarded message ----------\n$body"))
                    }
                )
            }
            entry<Route.Composer>(metadata = ListDetailPage()) { route ->
                ComposerScreen(
                    viewModel = viewModel,
                    initialTo = route.to,
                    initialSubject = route.subject,
                    initialBody = route.body,
                    inReplyTo = route.inReplyTo,
                    references = route.references,
                    onBack = { backStack.pop() }
                )
            }
            entry<Route.Outbox>(metadata = ListDetailPage()) {
                OutboxScreen(
                    viewModel = viewModel,
                    onBack = { backStack.pop() }
                )
            }
            entry<Route.AddAccount>(metadata = ListDetailPage()) {
                com.vayunmathur.email.ui.AddAccountScreen(
                    onBack = { backStack.pop() },
                    onAccountAdded = { backStack.pop() },
                )
            }
            entry<Route.Settings>(metadata = ListDetailPage()) {
                SettingsScreen(viewModel = viewModel, onBack = { backStack.pop() })
            }
        }
    }
}

@Composable
fun FolderList(folders: List<EmailFolder>, selectedFolder: String, onSelect: (String) -> Unit) {
    val folderTree = remember(folders) { buildFolderTree(folders) }

    // NOTE: a `Column` (not `LazyColumn`) — this composable is rendered
    // inside the drawer's outer `verticalScroll(...)` Column, and nesting a
    // lazy list inside an unbounded vertical scroller crashes with
    // "Vertically scrollable component was measured with an infinity maximum
    // height constraints". Folder lists are tiny so non-lazy is fine.
    Column {
        folderTree.forEach { root ->
            renderFolderTree(root, 0, selectedFolder, onSelect)
        }
    }
}

data class FolderNode(val folder: EmailFolder, val children: List<FolderNode>)

fun buildFolderTree(folders: List<EmailFolder>): List<FolderNode> {
    val childrenMap = folders.groupBy { it.parentFullName }
    
    fun buildNode(folder: EmailFolder): FolderNode {
        return FolderNode(
            folder = folder,
            children = childrenMap[folder.fullName]?.map { buildNode(it) } ?: emptyList()
        )
    }
    
    return folders.filter { it.parentFullName == null }.map { buildNode(it) }
}

@Composable
fun renderFolderTree(
    node: FolderNode,
    depth: Int,
    selectedFolder: String,
    onSelect: (String) -> Unit
) {
    NavigationDrawerItem(
        label = {
            Text(
                text = node.folder.name,
                color = if (node.folder.holdsMessages) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.5f)
            )
        },
        selected = node.folder.fullName == selectedFolder,
        onClick = { if (node.folder.holdsMessages) onSelect(node.folder.fullName) },
        modifier = Modifier
            .padding(NavigationDrawerItemDefaults.ItemPadding)
            .padding(start = (depth * 16).dp)
    )
    node.children.forEach { child ->
        renderFolderTree(child, depth + 1, selectedFolder, onSelect)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageListScreen(
    viewModel: EmailViewModel,
    onMessageClick: (EmailMessage) -> Unit,
    onComposeClick: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    val messages by viewModel.messages.collectAsState(emptyList())
    val selectedAccountEmail by viewModel.selectedAccountEmail.collectAsState()
    val selectedFolderName by viewModel.selectedFolderName.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedUids by viewModel.selectedMessageUids.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val aiSummary by viewModel.aiSummary.collectAsState()
    val aiSummaryLoading by viewModel.aiSummaryLoading.collectAsState()
    var isSearching by remember { mutableStateOf(false) }
    val context = LocalContext.current

    androidx.activity.compose.BackHandler(enabled = isSearching || selectedUids.isNotEmpty()) {
        if (selectedUids.isNotEmpty()) {
            viewModel.clearSelection()
        } else if (searchQuery.isNotEmpty()) {
            viewModel.setSearchQuery("")
        } else {
            isSearching = false
        }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) {
            kotlinx.coroutines.delay(1500)
            if (messages.isNotEmpty()) {
                viewModel.requestAiSummary(messages)
            }
        }
    }

    Scaffold(
        topBar = {
            if (selectedUids.isNotEmpty()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedUids.size)) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            IconClose()
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val account = selectedAccountEmail ?: messages.firstOrNull { it.id in selectedUids }?.accountEmail ?: return@IconButton
                            viewModel.bulkMarkAsRead(account, selectedUids.toList(), true)
                        }) {
                            IconMarkRead()
                        }
                        IconButton(onClick = {
                            val account = selectedAccountEmail ?: messages.firstOrNull { it.id in selectedUids }?.accountEmail ?: return@IconButton
                            viewModel.bulkMarkAsRead(account, selectedUids.toList(), false)
                        }) {
                            IconMarkUnread()
                        }
                    }
                )
            } else if (isSearching) {
                TopAppBar(
                    title = {
                        CommonSearchBar(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            padding = PaddingValues(0.dp)
                        )
                    },
                    navigationIcon = {
                        IconNavigation {
                            isSearching = false
                            viewModel.setSearchQuery("")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(selectedFolderName ?: "Unified Inbox") },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            IconMenu()
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearching = true }) {
                            IconSearch()
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (selectedUids.isEmpty()) {
                FloatingActionButton(onClick = onComposeClick) {
                    IconAdd()
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Thin progress bar while a sync is in flight. We use a `Box` of
            // fixed 2.dp height so the bar's presence never shifts the LazyColumn
            // (the bar is rendered on top of an invisible track).
            Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                if (isSyncing) {
                    LinearProgressIndicator(
                        progress = { syncProgress },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }
            }
            // Quick filters over the loaded messages.
            var msgFilter by remember { mutableStateOf(0) } // 0=All, 1=Unread, 2=Attachments
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.FilterChip(selected = msgFilter == 0, onClick = { msgFilter = 0 }, label = { Text("All") })
                androidx.compose.material3.FilterChip(selected = msgFilter == 1, onClick = { msgFilter = 1 }, label = { Text("Unread") })
                androidx.compose.material3.FilterChip(selected = msgFilter == 2, onClick = { msgFilter = 2 }, label = { Text("Attachments") })
            }
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = isSyncing,
                onRefresh = { viewModel.refresh(context) },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (messages.isEmpty() && searchQuery.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "No messages found. Pull down to refresh.",
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                    if (searchQuery.isNotEmpty() && (aiSummaryLoading || aiSummary != null)) {
                        item(key = "ai_summary") {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("AI Summary", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    Spacer(Modifier.height(8.dp))
                                    if (aiSummaryLoading) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Generating summary…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                        }
                                    } else {
                                        Text(aiSummary ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                }
                            }
                        }
                    }
                    items(
                        when (msgFilter) {
                            1 -> messages.filter { !it.isRead }
                            2 -> messages.filter { it.hasAttachments }
                            else -> messages
                        },
                        key = { "${it.accountEmail}|${it.folderName}|${it.id}" }
                    ) { message ->
                        val accountColor = Color(EmailAccount(message.accountEmail).getColor())
                        val isSelected = message.id in selectedUids
                        
                        Row(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        if (selectedUids.isNotEmpty()) {
                                            viewModel.toggleMessageSelection(message.id)
                                        } else {
                                            onMessageClick(message)
                                        }
                                    },
                                    onLongClick = {
                                        viewModel.toggleMessageSelection(message.id)
                                    }
                                )
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                                .height(IntrinsicSize.Min)
                        ) {
                            if (selectedAccountEmail == null) {
                                Surface(
                                    modifier = Modifier.width(4.dp).fillMaxHeight(),
                                    color = accountColor
                                ) {}
                            }
                            // Primary color band indicator for unread messages
                            Surface(
                                modifier = Modifier.width(4.dp).fillMaxHeight(),
                                color = if (message.isRead) Color.Transparent else MaterialTheme.colorScheme.primary
                            ) {}
                            ListItem(
                                leadingContent = null,
                                headlineContent = {
                                    Text(
                                        text = message.subject,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (message.isRead) FontWeight.Normal else FontWeight.Bold
                                    )
                                },
                                supportingContent = {
                                    Column {
                                        Text(
                                            text = message.from.substringBefore("<").trim(),
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = (if (message.isHtml && message.body != null) {
                                                androidx.core.text.HtmlCompat.fromHtml(message.body, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                                            } else {
                                                message.body ?: ""
                                            }).take(100),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                trailingContent = {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(text = message.date.substringBefore(" "), style = MaterialTheme.typography.labelSmall)
                                        if (message.hasAttachments) {
                                            IconAttachment(modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageThreadScreen(
    viewModel: EmailViewModel,
    accountEmail: String,
    threadId: String,
    onBack: () -> Unit,
    onReply: (String, String, String?) -> Unit,
    onForward: (String, String?) -> Unit
) {
    val messages by viewModel.getThread(accountEmail, threadId).collectAsState(emptyList())
    var hasMarkedAsRead by remember(threadId) { mutableStateOf(false) }

    // Mark all unread messages in the thread as read when the screen loads.
    // Only run once per thread navigation to allow user to mark as unread.
    LaunchedEffect(messages) {
        if (!hasMarkedAsRead && messages.isNotEmpty()) {
            messages.filter { !it.isRead }.forEach { msg ->
                viewModel.markAsRead(msg.accountEmail, msg.folderName, msg.id, true)
            }
            hasMarkedAsRead = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(messages.firstOrNull()?.subject ?: "Conversation") },
                navigationIcon = { IconNavigation(onBack) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { "${it.accountEmail}|${it.folderName}|${it.id}" }) { msg ->
                MessageItem(msg, viewModel, onBack, onReply, onForward)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageItem(
    msg: EmailMessage, 
    viewModel: EmailViewModel,
    onBack: () -> Unit,
    onReply: (String, String, String?) -> Unit,
    onForward: (String, String?) -> Unit
) {
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var showDetails by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    
    LaunchedEffect(msg.id) {
        attachments = viewModel.getAttachments(msg.accountEmail, msg.id)
        // If we only have the header for this message (sync skips bodies), pull
        // the body now. The DB row update will flow back through the Thread Flow
        // and trigger a recomposition with the body visible.
        if (msg.body == null) {
            viewModel.fetchBodyIfNeeded(msg)
        }
    }

    val senderName = msg.from.substringBefore("<").trim().ifEmpty { msg.from }
    val senderEmail = msg.from.substringAfter("<").substringBefore(">").trim()
    val initial = senderName.take(1).uppercase()
    val avatarColor = Color(EmailAccount(msg.accountEmail).getColor())

    Column {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDetails = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = avatarColor,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = initial, color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = "  •  ${msg.date.substringBeforeLast(":")}", // Simplified time
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "to me",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row {
                IconButton(onClick = { onReply(msg.from, msg.subject, msg.serverId) }) {
                    IconUndo()
                }
                IconButton(onClick = {
                    viewModel.markAsRead(msg.accountEmail, msg.folderName, msg.id, !msg.isRead)
                    if (msg.isRead) { // If it was read, we just marked it as unread
                        onBack()
                    }
                }) {
                    if (msg.isRead) IconMarkUnread() else IconMarkRead()
                }
            }
        }

        if (msg.isHtml && msg.body != null) {
            var loadImages by remember(msg.id) { mutableStateOf(false) }
            if (!loadImages) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Remote images blocked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { loadImages = true }) { Text("Load images") }
                }
            }
            HtmlText(
                html = msg.body,
                blockRemoteImages = !loadImages,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
            )
        } else {
            Text(
                text = msg.body ?: "(No Content)", 
                style = MaterialTheme.typography.bodyMedium, 
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        
        if (attachments.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Attachments:", style = MaterialTheme.typography.labelLarge)
                attachments.forEach { att ->
                    AttachmentItem(att, viewModel)
                }
            }
        }
        
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onReply(msg.from, msg.subject, msg.serverId) },
                modifier = Modifier.weight(1f)
            ) {
                IconUndo(modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reply))
            }
            OutlinedButton(
                onClick = { onForward(msg.subject, msg.body) },
                modifier = Modifier.weight(1f)
            ) {
                IconForward(modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.forward))
            }
        }

        HorizontalDivider()
    }

    if (showDetails) {
        ModalBottomSheet(
            onDismissRequest = { showDetails = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = msg.date, // Full date
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End)
                )
                
                DetailItem(label = stringResource(R.string.from_label), name = senderName, email = senderEmail, avatarColor = avatarColor)
                // DetailItem(label = "Reply to", ...) // If available
                DetailItem(label = stringResource(R.string.to_label), name = "me", email = msg.to ?: "", avatarColor = Color.Gray)
                
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconInbox(modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(text = msg.folderName, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun DetailItem(label: String, name: String, email: String, avatarColor: Color) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = avatarColor,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = name.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(text = name, style = MaterialTheme.typography.bodyLarge)
                Text(text = email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun AttachmentItem(attachment: Attachment, viewModel: EmailViewModel) {
    var downloading by remember { mutableStateOf(false) }
    var localPath by remember { mutableStateOf(attachment.localUri) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = attachment.fileName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        if (localPath != null) {
            Text("Downloaded", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        } else {
            IconButton(onClick = { 
                downloading = true
                viewModel.downloadAttachment(attachment, { path -> 
                    downloading = false
                    localPath = path
                }, { downloading = false })
            }, enabled = !downloading) {
                if (downloading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else IconDownload(modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerScreen(
    viewModel: EmailViewModel,
    initialTo: String = "",
    initialSubject: String = "",
    initialBody: String = "",
    inReplyTo: String? = null,
    references: String? = null,
    onBack: () -> Unit,
) {
    val accounts by viewModel.accounts.collectAsState(emptyList())
    val selectedAccount by viewModel.selectedAccount.collectAsState()
    val context = LocalContext.current
    
    var fromAccount by remember(selectedAccount, accounts) { 
        mutableStateOf(selectedAccount ?: accounts.firstOrNull()) 
    }
    
    var to by remember { mutableStateOf(initialTo) }
    var cc by remember { mutableStateOf("") }
    var bcc by remember { mutableStateOf("") }
    var showCcBcc by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf(initialSubject) }
    var body by remember { mutableStateOf(initialBody) }
    var sending by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    
    var showAccountPicker by remember { mutableStateOf(false) }

    // Append the from-account's signature, swapping it when the account changes.
    var appliedSignature by remember { mutableStateOf("") }
    LaunchedEffect(fromAccount) {
        val block = signatureBlock(fromAccount)
        if (block != appliedSignature) {
            body = when {
                appliedSignature.isEmpty() -> body + block
                body.endsWith(appliedSignature) -> body.removeSuffix(appliedSignature) + block
                else -> body + block
            }
            appliedSignature = block
        }
    }

    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { attachments = attachments + it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose)) },
                navigationIcon = { IconNavigation(onBack) },
                actions = {
                    IconButton(onClick = { attachmentLauncher.launch("*/*") }) {
                        IconAttachment()
                    }
                    IconButton(onClick = {
                        val acc = fromAccount ?: return@IconButton
                        sending = true
                        viewModel.sendEmailFrom(
                            account = acc,
                            to = to, 
                            subject = subject, 
                            body = body, 
                            cc = cc.ifBlank { null },
                            bcc = bcc.ifBlank { null },
                            attachments = attachments,
                            inReplyTo = inReplyTo, 
                            references = references, 
                            onSuccess = {
                                sending = false
                                android.widget.Toast.makeText(context, "Message sent", android.widget.Toast.LENGTH_SHORT).show()
                                onBack()
                            },
                            onError = { err ->
                                sending = false
                                // ViewModel has already queued the message to the outbox;
                                // surface the underlying error so the user knows it'll retry.
                                android.widget.Toast.makeText(context, "Saved to Outbox: $err", android.widget.Toast.LENGTH_LONG).show()
                                onBack()
                            }
                        )
                    }, enabled = !sending && fromAccount != null) {
                        if (sending) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        else IconSend()
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Account Picker
            ListItem(
                headlineContent = { Text(fromAccount?.email ?: stringResource(R.string.select_account)) },
                overlineContent = { Text(stringResource(R.string.from_label)) },
                trailingContent = { IconChevronRight() },
                modifier = Modifier.clickable { showAccountPicker = true }
            )
            HorizontalDivider()

            if (showAccountPicker) {
                AlertDialog(
                    onDismissRequest = { showAccountPicker = false },
                    confirmButton = {},
                    title = { Text(stringResource(R.string.select_sender)) },
                    text = {
                        Column {
                            accounts.forEach { acc ->
                                ListItem(
                                    headlineContent = { Text(acc.email) },
                                    modifier = Modifier.clickable { 
                                        fromAccount = acc
                                        showAccountPicker = false 
                                    }
                                )
                            }
                        }
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text(stringResource(R.string.to_label)) }, modifier = Modifier.weight(1f))
                TextButton(onClick = { showCcBcc = !showCcBcc }) { Text("Cc/Bcc") }
            }
            if (showCcBcc) {
                OutlinedTextField(value = cc, onValueChange = { cc = it }, label = { Text("Cc") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = bcc, onValueChange = { bcc = it }, label = { Text("Bcc") }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text(stringResource(R.string.subject_label)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text(stringResource(R.string.body_label)) }, modifier = Modifier.fillMaxWidth().weight(1f))
            
            if (attachments.isNotEmpty()) {
                Text("Attachments:", style = MaterialTheme.typography.labelLarge)
                attachments.forEach { uri ->
                    Text(uri.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboxScreen(
    viewModel: EmailViewModel,
    onBack: () -> Unit,
) {
    val outbox by viewModel.outbox.collectAsState(emptyList())
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.outbox)) },
                navigationIcon = { IconNavigation(onBack) },
                actions = {
                    if (outbox.isNotEmpty()) {
                        TextButton(onClick = {
                            viewModel.sendOutboxNow(context)
                            android.widget.Toast.makeText(context, "Retrying ${outbox.size} pending message(s)…", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.send_now)) }
                    }
                },
            )
        },
    ) { padding ->
        if (outbox.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Outbox is empty",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                items(outbox, key = { it.id }) { entry ->
                    OutboxRow(
                        entry = entry,
                        onDelete = {
                            viewModel.deleteOutboxEntry(entry)
                            android.widget.Toast.makeText(context, "Deleted from Outbox", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun OutboxRow(entry: OutboxEntry, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.subject.ifBlank { "(no subject)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "From ${entry.accountEmail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "To ${entry.to}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { IconDelete() }
        }
        if (entry.body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                entry.body.lineSequence().firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
        }
        if (entry.lastError != null || entry.attemptCount > 0) {
            Spacer(Modifier.height(8.dp))
            val statusColor =
                if (entry.lastError != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            Text(
                buildString {
                    if (entry.lastError != null) append("Failed: ${entry.lastError}")
                    if (entry.attemptCount > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${entry.attemptCount} attempt(s)")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )
        }
    }
}

/** The signature block appended to an outgoing message body, or "" if none. */
private fun signatureBlock(acc: com.vayunmathur.email.EmailAccount?): String {
    val s = acc?.signature?.trim().orEmpty()
    return if (s.isEmpty()) "" else "\n\n-- \n$s"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: EmailViewModel, onBack: () -> Unit) {
    val accounts by viewModel.accounts.collectAsState(emptyList())
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconNavigation(onBack) },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Signatures", style = MaterialTheme.typography.titleMedium)
            if (accounts.isEmpty()) {
                Text(stringResource(R.string.select_account))
            }
            accounts.forEach { acc ->
                var sig by remember(acc.email) { mutableStateOf(acc.signature) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(acc.email, style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = sig,
                        onValueChange = { sig = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        placeholder = { Text("Your signature") },
                    )
                    Button(
                        onClick = {
                            viewModel.setSignature(acc.email, sig)
                            android.widget.Toast.makeText(context, "Signature saved", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
