package com.vayunmathur.email

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.lifecycle.lifecycleScope
import androidx.core.net.toUri
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.widget.EmailWidgetReceiver
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.*
import com.vayunmathur.library.widgets.updateWidgetPreviews
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        updateWidgetPreviews(EmailWidgetReceiver::class)
        handleIntent(intent)
        // Wake the outbox sender on every cold start: if the process was killed
        // between scheduled retries, this is what gets it going again.
        com.vayunmathur.email.data.OutboxSendWorker.runNow(this)
        // Kick the IMAP IDLE service so we get push notifications even when the
        // app is in the background. The service is a no-op if there are no
        // accounts yet (it'll just stopSelf).
        com.vayunmathur.email.data.ImapIdleService.start(this)
        // One-shot backfill for dateMillis on rows persisted before that column.
        com.vayunmathur.email.data.DateMillisBackfill.runIfNeeded(lifecycleScope, this)
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
    val accounts by viewModel.accounts.collectAsStateWithLifecycle(emptyList())
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
        val references: String? = null,
        val draftId: Long? = null
    ) : Route
    @Serializable
    object Outbox : Route
    @Serializable
    object Drafts : Route
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

    val accounts by viewModel.accounts.collectAsStateWithLifecycle(emptyList())
    val selectedAccountEmail by viewModel.selectedAccountEmail.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle(emptyList())
    val selectedFolderName by viewModel.selectedFolderName.collectAsStateWithLifecycle()
    val outbox by viewModel.outbox.collectAsStateWithLifecycle(emptyList())

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
                            FolderList(folders, selectedFolderName) { folderName ->
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
                        NavigationDrawerItem(
                            label = { Text(stringResource(R.string.drafts)) },
                            selected = false,
                            onClick = {
                                backStack.add(Route.Drafts)
                                scope.launch { drawerState.close() }
                            },
                            icon = { com.vayunmathur.library.ui.IconEdit() },
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
                    },
                    onCompose = { to, sub ->
                        backStack.add(Route.Composer(to = to, subject = sub))
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
                    draftId = route.draftId,
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
            entry<Route.Drafts>(metadata = ListDetailPage()) {
                DraftsScreen(
                    viewModel = viewModel,
                    onBack = { backStack.pop() },
                    onOpenDraft = { id -> backStack.add(Route.Composer(draftId = id)) },
                )
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
    val messages by viewModel.messages.collectAsStateWithLifecycle(emptyList())
    val selectedAccountEmail by viewModel.selectedAccountEmail.collectAsStateWithLifecycle()
    val selectedFolderName by viewModel.selectedFolderName.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedUids by viewModel.selectedMessageUids.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val aiSummary by viewModel.aiSummary.collectAsStateWithLifecycle()
    val aiSummaryLoading by viewModel.aiSummaryLoading.collectAsStateWithLifecycle()
    var isSearching by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<EmailMessage?>(null) }

    // Show an Undo snackbar; commit the server/local delete only if not undone.
    LaunchedEffect(pendingDelete) {
        val msg = pendingDelete ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Message deleted",
            actionLabel = "Undo",
            duration = androidx.compose.material3.SnackbarDuration.Short,
        )
        if (result != androidx.compose.material3.SnackbarResult.ActionPerformed) {
            viewModel.deleteMessage(msg.accountEmail, msg.folderName, msg.id)
        }
        pendingDelete = null
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    title = { Text(if (selectedAccountEmail == null) "Unified Inbox" else selectedFolderName) },
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
            val filteredMessages by remember {
                derivedStateOf {
                    when (msgFilter) {
                        1 -> messages.filter { !it.isRead }
                        2 -> messages.filter { it.hasAttachments }
                        else -> messages
                    }
                }
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
                        filteredMessages,
                        key = { "${it.accountEmail}|${it.folderName}|${it.id}" }
                    ) { message ->
                        val isPending = pendingDelete?.let {
                            it.id == message.id && it.accountEmail == message.accountEmail && it.folderName == message.folderName
                        } == true
                        if (!isPending) {
                        val accountBandColor = Color(accountColor(message.accountEmail))
                        val isSelected = message.id in selectedUids
                        // Read the latest message snapshot inside the swipe handler so
                        // repeated read/unread swipes toggle the CURRENT persisted state
                        // (the confirmValueChange lambda is captured once by the state).
                        val currentMessage by rememberUpdatedState(message)
                        val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                when (value) {
                                    androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> { pendingDelete = currentMessage; true }
                                    androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> {
                                        viewModel.markAsRead(currentMessage.accountEmail, currentMessage.folderName, currentMessage.id, !currentMessage.isRead)
                                        false
                                    }
                                    else -> false
                                }
                            }
                        )
                        androidx.compose.material3.SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                // Only show a background icon while an active swipe is in
                                // progress in that direction — never when settled.
                                when (dismissState.dismissDirection) {
                                    androidx.compose.material3.SwipeToDismissBoxValue.EndToStart ->
                                        Box(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                                            contentAlignment = Alignment.CenterEnd,
                                        ) { com.vayunmathur.library.ui.IconDelete() }
                                    androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd ->
                                        Box(
                                            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                                            contentAlignment = Alignment.CenterStart,
                                        ) { IconMarkRead() }
                                    else -> {}
                                }
                            },
                        ) {
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
                                    color = accountBandColor
                                ) {}
                            }
                            // Primary color band indicator for unread messages
                            Surface(
                                modifier = Modifier.width(4.dp).fillMaxHeight(),
                                color = if (message.isRead) Color.Transparent else MaterialTheme.colorScheme.primary
                            ) {}
                            ListItem(
                                leadingContent = null,
                                content = {
                                    Text(
                                        text = message.subject,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (message.isRead) FontWeight.Normal else FontWeight.Bold
                                    )
                                },
                                supportingContent = {
                                    Column {
                                        Text(
                                            text = senderDisplayName(message.from),
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        val preview = remember(message.body, message.isHtml) { (message.plainTextBody() ?: "").take(100) }
                                        Text(
                                            text = preview,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageThreadScreen(
    viewModel: EmailViewModel,
    accountEmail: String,
    threadId: String,
    onBack: () -> Unit,
    onReply: (String, String, String?) -> Unit,
    onForward: (String, String?) -> Unit,
    onCompose: (String, String) -> Unit
) {
    val messages by viewModel.getThread(accountEmail, threadId).collectAsStateWithLifecycle(emptyList())
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
                MessageItem(msg, viewModel, onBack, onReply, onForward, onCompose)
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
    onForward: (String, String?) -> Unit,
    onCompose: (String, String) -> Unit
) {
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var showDetails by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    
    LaunchedEffect(msg.id) {
        attachments = viewModel.getAttachments(msg.accountEmail, msg.id)
        // If we only have the header for this message (sync skips bodies), pull
        // the body now. The DB row update will flow back through the Thread Flow
        // and trigger a recomposition with the body visible.
        if (msg.body == null) {
            viewModel.fetchBodyIfNeeded(msg)
        }
    }

    val senderName = senderDisplayName(msg.from).ifEmpty { msg.from }
    val senderEmail = msg.from.substringAfter("<").substringBefore(">").trim()
    val initial = senderName.take(1).uppercase()
    val avatarColor = Color(accountColor(msg.accountEmail))

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
                var showSnooze by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { showSnooze = true }) { Text("Snooze") }
                    DropdownMenu(expanded = showSnooze, onDismissRequest = { showSnooze = false }) {
                        val snooze = { at: Long ->
                            showSnooze = false
                            viewModel.snoozeMessage(msg.accountEmail, msg.folderName, msg.id, at)
                            android.widget.Toast.makeText(context, "Snoozed", android.widget.Toast.LENGTH_SHORT).show()
                            onBack()
                        }
                        DropdownMenuItem(text = { Text("Later today (6 PM)") }, onClick = { snooze(scheduleTime(18, sameDay = true)) })
                        DropdownMenuItem(text = { Text("Tomorrow (8 AM)") }, onClick = { snooze(scheduleTime(8, sameDay = false)) })
                        DropdownMenuItem(text = { Text("In 1 week") }, onClick = { snooze(System.currentTimeMillis() + 7L * 24 * 3600_000) })
                    }
                }
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
            var showQuotes by remember(msg.id) { mutableStateOf(false) }
            val hasQuotes = remember(msg.body) {
                listOf("gmail_quote", "yahoo_quoted", "moz-cite-prefix", "<blockquote").any { msg.body.contains(it, ignoreCase = true) }
            }
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
                hideQuotes = hasQuotes && !showQuotes,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
            )
            if (hasQuotes) {
                TextButton(
                    onClick = { showQuotes = !showQuotes },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(if (showQuotes) "Hide quoted text" else "Show quoted text") }
            }
        } else {
            var showQuotes by remember(msg.id) { mutableStateOf(false) }
            val (mainText, quotedText) = remember(msg.body) { splitQuotedText(msg.body ?: "(No Content)") }
            Text(
                text = if (showQuotes || quotedText.isEmpty()) (msg.body ?: "(No Content)") else mainText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (quotedText.isNotEmpty()) {
                TextButton(
                    onClick = { showQuotes = !showQuotes },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(if (showQuotes) "Hide quoted text" else "Show quoted text") }
            }
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

        // Unsubscribe + block sender
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val unsubscribe = remember(msg.listUnsubscribe, msg.listUnsubscribePost, msg.body, msg.isHtml) {
                msg.detectUnsubscribe()
            }
            if (unsubscribe != null) {
                var showConfirm by remember(msg.id) { mutableStateOf(false) }
                TextButton(onClick = { showConfirm = true }) { Text("Unsubscribe") }
                if (showConfirm) {
                    UnsubscribeDialog(
                        method = unsubscribe,
                        onDismiss = { showConfirm = false },
                        onConfirm = {
                            showConfirm = false
                            performUnsubscribe(unsubscribe, context, viewModel, onCompose)
                        },
                    )
                }
            }
            TextButton(onClick = {
                viewModel.blockSender(msg.from)
                android.widget.Toast.makeText(context, "Sender blocked", android.widget.Toast.LENGTH_SHORT).show()
                onBack()
            }) { Text("Block sender") }
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
    val context = LocalContext.current
    var downloading by remember { mutableStateOf(false) }
    var localPath by remember { mutableStateOf(attachment.localUri) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = attachment.fileName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        if (localPath != null) {
            Text(
                "Open",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable {
                    val uri = try { Uri.parse(localPath) } catch (e: Exception) { null }
                    if (uri != null) {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, attachment.mimeType.ifBlank { "*/*" })
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(Intent.createChooser(intent, null))
                        } catch (e: Exception) {
                            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        } else {
            IconButton(onClick = {
                downloading = true
                viewModel.downloadAttachment(attachment, { path ->
                    downloading = false
                    localPath = path
                    Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                }, { error ->
                    downloading = false
                    Toast.makeText(context, "Download failed: $error", Toast.LENGTH_SHORT).show()
                })
            }, enabled = !downloading) {
                if (downloading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else IconDownload(modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ComposerScreen(
    viewModel: EmailViewModel,
    initialTo: String = "",
    initialSubject: String = "",
    initialBody: String = "",
    inReplyTo: String? = null,
    references: String? = null,
    draftId: Long? = null,
    onBack: () -> Unit,
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle(emptyList())
    val selectedAccount by viewModel.selectedAccount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    var fromAccount by remember(selectedAccount, accounts) { 
        mutableStateOf(selectedAccount ?: accounts.firstOrNull()) 
    }
    
    var to by remember { mutableStateOf(initialTo) }
    var cc by remember { mutableStateOf("") }
    var bcc by remember { mutableStateOf("") }
    var showCcBcc by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf(initialSubject) }
    val bodyController = remember { com.vayunmathur.library.ui.HtmlEditorController(initialBody) }
    var sending by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    
    var showAccountPicker by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }

    // Pick recipients from the system contact picker (no READ_CONTACTS needed —
    // the picker grants temporary read access to the chosen email row). After
    // each pick we re-open the picker so several contacts can be added in a row;
    // the user dismisses it (cancel) when done.
    var pickTarget by remember { mutableStateOf(0) } // 0=to, 1=cc, 2=bcc
    var pickTick by remember { mutableStateOf(0) }
    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val email = result.data?.data?.let { contactEmail(context, it) }
            if (!email.isNullOrBlank()) {
                when (pickTarget) {
                    0 -> to = appendRecipient(to, email)
                    1 -> cc = appendRecipient(cc, email)
                    2 -> bcc = appendRecipient(bcc, email)
                }
                pickTick++ // re-open so more contacts can be added
            }
        }
    }
    LaunchedEffect(pickTick) {
        if (pickTick > 0) {
            contactPicker.launch(
                Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI)
            )
        }
    }
    val pickContact = { target: Int ->
        pickTarget = target
        pickTick++
    }

    // Draft auto-save / resume state.
    var currentDraftId by remember { mutableStateOf(draftId) }
    var draftLoaded by remember { mutableStateOf(draftId == null) }
    LaunchedEffect(draftId) {
        if (draftId != null) {
            viewModel.loadDraft(draftId)?.let { d ->
                to = d.to; cc = d.cc; bcc = d.bcc
                if (d.cc.isNotBlank() || d.bcc.isNotBlank()) showCcBcc = true
                subject = d.subject; bodyController.setHtml(d.body)
                accounts.firstOrNull { it.email == d.accountEmail }?.let { fromAccount = it }
            }
            draftLoaded = true
        }
    }

    // Append the from-account's signature, swapping it when the account changes.
    // Skipped when editing an existing draft (its body is already saved).
    var appliedSignature by remember { mutableStateOf("") }
    LaunchedEffect(fromAccount) {
        if (draftId != null) return@LaunchedEffect
        val block = signatureBlockHtml(fromAccount)
        if (block != appliedSignature) {
            val t = bodyController.html
            val newText = when {
                appliedSignature.isEmpty() -> t + block
                t.endsWith(appliedSignature) -> t.removeSuffix(appliedSignature) + block
                else -> t + block
            }
            bodyController.setHtml(newText)
            appliedSignature = block
        }
    }

    // Auto-save the draft as the user types (debounced).
    LaunchedEffect(fromAccount, draftLoaded) {
        val acc = fromAccount
        if (!draftLoaded || acc == null) return@LaunchedEffect
        snapshotFlow { listOf(to, cc, bcc, subject, bodyController.html) }
            .debounce(800)
            .collect {
                val hasContent = to.isNotBlank() || cc.isNotBlank() || bcc.isNotBlank() ||
                    subject.isNotBlank() || bodyController.html.isNotBlank()
                if (hasContent) {
                    viewModel.saveDraft(currentDraftId, acc.email, to, cc, bcc, subject, bodyController.html) { id ->
                        currentDraftId = id
                    }
                }
            }
    }

    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) attachments = attachments + uris
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
                    Box {
                        TextButton(onClick = { showSchedule = true }, enabled = fromAccount != null) {
                            Text("Later")
                        }
                        DropdownMenu(expanded = showSchedule, onDismissRequest = { showSchedule = false }) {
                            val schedule = { at: Long ->
                                showSchedule = false
                                fromAccount?.let { acc ->
                                    viewModel.scheduleSend(
                                        account = acc, to = to, subject = subject,
                                        body = bodyController.html,
                                        asHtml = true,
                                        cc = cc.ifBlank { null }, bcc = bcc.ifBlank { null },
                                        attachments = attachments, inReplyTo = inReplyTo,
                                        references = references, scheduledAt = at,
                                    ) { currentDraftId?.let { viewModel.deleteDraft(it) } }
                                    android.widget.Toast.makeText(context, "Scheduled", android.widget.Toast.LENGTH_SHORT).show()
                                    onBack()
                                }
                            }
                            DropdownMenuItem(text = { Text("In 1 hour") }, onClick = { schedule(System.currentTimeMillis() + 3_600_000L) })
                            DropdownMenuItem(text = { Text("This evening (6 PM)") }, onClick = { schedule(scheduleTime(18, sameDay = true)) })
                            DropdownMenuItem(text = { Text("Tomorrow (8 AM)") }, onClick = { schedule(scheduleTime(8, sameDay = false)) })
                        }
                    }
                    IconButton(onClick = {
                        val acc = fromAccount ?: return@IconButton
                        sending = true
                        viewModel.sendEmailFrom(
                            account = acc,
                            to = to, 
                            subject = subject, 
                            body = bodyController.html,
                            asHtml = true,
                            cc = cc.ifBlank { null },
                            bcc = bcc.ifBlank { null },
                            attachments = attachments,
                            inReplyTo = inReplyTo, 
                            references = references, 
                            onSuccess = {
                                sending = false
                                currentDraftId?.let { viewModel.deleteDraft(it) }
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
        },
        bottomBar = {
            if (bodyController.focused) {
                com.vayunmathur.library.ui.HtmlFormatToolbar(controller = bodyController)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 16.dp, vertical = 8.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Compact account ("From") picker.
            Surface(
                onClick = { showAccountPicker = true },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${stringResource(R.string.from_label)}: ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        fromAccount?.email ?: stringResource(R.string.select_account),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconChevronRight()
                }
            }

            if (showAccountPicker) {
                AlertDialog(
                    onDismissRequest = { showAccountPicker = false },
                    confirmButton = {},
                    title = { Text(stringResource(R.string.select_sender)) },
                    text = {
                        Column {
                            accounts.forEach { acc ->
                                ListItem(
                                    content = { Text(acc.email) },
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
                OutlinedTextField(
                    value = to, onValueChange = { to = it },
                    label = { Text(stringResource(R.string.to_label)) },
                    trailingIcon = { IconButton(onClick = { pickContact(0) }) { com.vayunmathur.library.ui.IconAdd() } },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showCcBcc = !showCcBcc }) { Text("Cc/Bcc") }
            }
            if (showCcBcc) {
                OutlinedTextField(
                    value = cc, onValueChange = { cc = it }, label = { Text("Cc") },
                    trailingIcon = { IconButton(onClick = { pickContact(1) }) { com.vayunmathur.library.ui.IconAdd() } },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bcc, onValueChange = { bcc = it }, label = { Text("Bcc") },
                    trailingIcon = { IconButton(onClick = { pickContact(2) }) { com.vayunmathur.library.ui.IconAdd() } },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = subject, onValueChange = { subject = it },
                label = { Text(stringResource(R.string.subject_label)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            // True HTML body editor (real spans → HTML); the formatting toolbar
            // lives in the Scaffold bottomBar so it docks above the keyboard.
            com.vayunmathur.library.ui.HtmlEditor(
                controller = bodyController,
                placeholder = stringResource(R.string.body_label),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            
            if (attachments.isNotEmpty()) {
                val totalBytes = remember(attachments) { attachments.sumOf { uriSize(context, it) } }
                Text("Attachments (${android.text.format.Formatter.formatShortFileSize(context, totalBytes)})", style = MaterialTheme.typography.labelLarge)
                if (totalBytes > 25L * 1024 * 1024) {
                    Text(
                        "Total attachment size exceeds 25 MB; many providers will reject it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                attachments.forEach { uri ->
                    val attachmentLabel = remember(uri) {
                        "${uriName(context, uri)} · " + android.text.format.Formatter.formatShortFileSize(context, uriSize(context, uri))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconAttachment(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            attachmentLabel,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { attachments = attachments - uri }) {
                            com.vayunmathur.library.ui.IconClose(modifier = Modifier.size(16.dp))
                        }
                    }
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
    val outbox by viewModel.outbox.collectAsStateWithLifecycle(emptyList())
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

/** The signature block (HTML) appended to an outgoing message body, or "" if none. */
private fun signatureBlockHtml(acc: com.vayunmathur.email.EmailAccount?): String {
    val s = acc?.signature?.trim().orEmpty()
    if (s.isEmpty()) return ""
    val escaped = android.text.TextUtils.htmlEncode(s).replace("\n", "<br>")
    return "<br><br>-- <br>$escaped"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: EmailViewModel, onBack: () -> Unit) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle(emptyList())
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

            val blocked by viewModel.blockedSenders.collectAsStateWithLifecycle(emptyList())
            Text("Blocked senders", style = MaterialTheme.typography.titleMedium)
            if (blocked.isEmpty()) {
                Text("None", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            blocked.forEach { b ->
                ListItem(
                    content = { Text(b.address) },
                    trailingContent = {
                        TextButton(onClick = { viewModel.unblockSender(b.address) }) { Text("Unblock") }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(
    viewModel: EmailViewModel,
    onBack: () -> Unit,
    onOpenDraft: (Long) -> Unit,
) {
    val drafts by viewModel.drafts.collectAsStateWithLifecycle(emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drafts)) },
                navigationIcon = { IconNavigation(onBack) },
            )
        }
    ) { padding ->
        if (drafts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No drafts")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(drafts, key = { it.id }) { d ->
                    ListItem(
                        content = { Text(d.subject.ifBlank { "(no subject)" }) },
                        supportingContent = {
                            val prefix = if (d.to.isNotBlank()) "To: ${d.to}  " else ""
                            Text(prefix + d.body.replace("\n", " ").take(80), maxLines = 2)
                        },
                        modifier = Modifier.clickable { onOpenDraft(d.id) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteDraft(d.id) }) {
                                com.vayunmathur.library.ui.IconDelete()
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun uriName(context: android.content.Context, uri: android.net.Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "attachment"

private fun uriSize(context: android.content.Context, uri: android.net.Uri): Long =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }
    }.getOrNull() ?: 0L

/** Epoch millis for [hour]:00 today (or tomorrow if that time already passed, or sameDay=false forces next day). */
private fun scheduleTime(hour: Int, sameDay: Boolean): Long {
    val c = java.util.Calendar.getInstance()
    c.set(java.util.Calendar.HOUR_OF_DAY, hour)
    c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    if (!sameDay || c.timeInMillis <= System.currentTimeMillis()) {
        c.add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    return c.timeInMillis
}

/** Append an email to a comma-separated recipient field, avoiding duplicates. */
private fun appendRecipient(field: String, email: String): String {
    val existing = field.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (existing.any { it.equals(email, ignoreCase = true) }) return field
    return if (existing.isEmpty()) email else existing.joinToString(", ") + ", " + email
}

/** Read the email address from a contact-picker result URI (granted per-item, no permission needed). */
private fun contactEmail(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS),
            null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

/**
 * Split a plain-text body into (visibleText, quotedText). Quoted text starts at
 * the first reply boundary ("On … wrote:", "-----Original Message-----") or a
 * run of '>'-prefixed lines. Returns empty quotedText when nothing is quoted.
 */
private fun splitQuotedText(body: String): Pair<String, String> {
    val lines = body.split("\n")
    val onWrote = Regex("^On .+ wrote:\\s*$")
    val origMsg = Regex("^-{2,}\\s*Original Message\\s*-{2,}\\s*$", RegexOption.IGNORE_CASE)
    for (i in lines.indices) {
        val line = lines[i].trim()
        val isBoundary = onWrote.matches(line) || origMsg.matches(line) || line.startsWith(">")
        if (isBoundary && i > 0) {
            val main = lines.subList(0, i).joinToString("\n").trimEnd()
            val quoted = lines.subList(i, lines.size).joinToString("\n")
            return main to quoted
        }
    }
    return body to ""
}

/** Confirmation dialog shown before acting on a detected unsubscribe option. */
@Composable
private fun UnsubscribeDialog(
    method: UnsubscribeMethod,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (message, confirmLabel) = when (method) {
        is UnsubscribeMethod.OneClickPost ->
            "Send an unsubscribe request to the sender?" to "Unsubscribe"
        is UnsubscribeMethod.OpenWeb ->
            "Open the unsubscribe page in your browser?" to "Open"
        is UnsubscribeMethod.SendMail ->
            "Compose an unsubscribe email to ${method.address}?" to "Compose"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unsubscribe") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Act on a confirmed unsubscribe option. */
private fun performUnsubscribe(
    method: UnsubscribeMethod,
    context: android.content.Context,
    viewModel: EmailViewModel,
    onCompose: (String, String) -> Unit,
) {
    when (method) {
        is UnsubscribeMethod.OneClickPost -> {
            android.widget.Toast.makeText(context, "Unsubscribing…", android.widget.Toast.LENGTH_SHORT).show()
            viewModel.oneClickUnsubscribe(method.url) { ok ->
                val text = if (ok) "Unsubscribed" else "Unsubscribe failed"
                android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        is UnsubscribeMethod.OpenWeb -> {
            val opened = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, method.url.toUri()))
            }.isSuccess
            if (!opened) {
                android.widget.Toast.makeText(context, "Couldn't open unsubscribe page", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        is UnsubscribeMethod.SendMail -> onCompose(method.address, "Unsubscribe")
    }
}
