package com.vayunmathur.email

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val clientId = "827025129169-1ihnv9r1a8nd1i3qjs98tkvluo4vjbhe.apps.googleusercontent.com"
    private val redirectUri = "com.googleusercontent.apps.827025129169-1ihnv9r1a8nd1i3qjs98tkvluo4vjbhe:/oauth2redirect"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            val viewModel: EmailViewModel = viewModel()
            DynamicTheme {
                MainContent(
                    viewModel = viewModel,
                    onGoogleLogin = { startGoogleLogin() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        val expectedScheme = "com.googleusercontent.apps.827025129169-1ihnv9r1a8nd1i3qjs98tkvluo4vjbhe"
        
        val accountEmail = intent?.getStringExtra("accountEmail")
        val threadId = intent?.getStringExtra("threadId")
        if (accountEmail != null && threadId != null) {
            IntentState.navigationRoute = Route.MessageThread(accountEmail, threadId)
        } else if (intent?.getBooleanExtra("compose", false) == true) {
            IntentState.navigationRoute = Route.Composer()
        }

        if (data != null && data.scheme == expectedScheme && data.path == "/oauth2redirect") {
            val code = data.getQueryParameter("code")
            if (code != null) {
                exchangeCodeForToken(code)
            }
        } else if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SENDTO) {
            val to = if (intent.action == Intent.ACTION_SENDTO) data?.schemeSpecificPart ?: "" else ""
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
            val body = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            
            IntentState.navigationRoute = Route.Composer(to, subject, body)
        }
    }

    private fun startGoogleLogin() {
        val verifier = generateCodeVerifier()
        TokenState.codeVerifier = verifier
        val challenge = generateCodeChallenge(verifier)

        val authUri = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "https://mail.google.com/ email profile")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "select_account")
            .build()

        startActivity(Intent(Intent.ACTION_VIEW, authUri))
    }

    private fun exchangeCodeForToken(code: String) {
        val verifier = TokenState.codeVerifier ?: return

        scope.launch {
            try {
                HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }.use { client ->
                    val httpResponse = client.submitForm(
                        url = "https://oauth2.googleapis.com/token",
                        formParameters = parameters {
                            append("client_id", clientId)
                            append("code", code)
                            append("code_verifier", verifier)
                            append("grant_type", "authorization_code")
                            append("redirect_uri", redirectUri)
                        }
                    )

                    if (httpResponse.status.isSuccess()) {
                        val response: TokenResponse = httpResponse.body()
                        
                        val userInfo: UserInfo = client.get("https://www.googleapis.com/oauth2/v3/userinfo") {
                            bearerAuth(response.accessToken)
                        }.body()

                        val dao = EmailDatabase.getInstance(this@MainActivity).emailDao()
                        dao.insertAccount(EmailAccount(
                            email = userInfo.email,
                            accessToken = response.accessToken,
                            refreshToken = response.refreshToken
                        ))
                        
                        EmailSyncWorker.schedulePeriodicSync(this@MainActivity)
                        EmailSyncWorker.runOneOffSync(this@MainActivity)
                    } else {
                        val errorText = httpResponse.bodyAsText()
                        android.util.Log.e("OAuthError", "Failed to exchange code: $errorText")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun generateCodeVerifier(): String {
        val sr = SecureRandom()
        val code = ByteArray(32)
        sr.nextBytes(code)
        return Base64.encodeToString(code, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).trim()
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        val digest = md.digest()
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).trim()
    }
}

object IntentState {
    var navigationRoute by mutableStateOf<Route?>(null)
}

object TokenState {
    var codeVerifier: String? = null
}

@Serializable
data class TokenResponse(
    @kotlinx.serialization.SerialName("access_token") val accessToken: String,
    @kotlinx.serialization.SerialName("expires_in") val expiresIn: Int,
    @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String? = null,
    @kotlinx.serialization.SerialName("scope") val scope: String,
    @kotlinx.serialization.SerialName("token_type") val tokenType: String
)

@Serializable
data class UserInfo(
    val email: String,
    val name: String? = null,
    val picture: String? = null
)

@Composable
fun MainContent(viewModel: EmailViewModel, onGoogleLogin: () -> Unit) {
    val accounts by viewModel.accounts.collectAsState(emptyList())
    if (accounts.isEmpty()) {
        LoginScreen(onGoogleLogin)
    } else {
        EmailApp(viewModel = viewModel, onAddAccount = onGoogleLogin)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onGoogleLogin: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Email") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Welcome to Email",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            Button(
                onClick = onGoogleLogin,
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Sign in with Google")
            }
        }
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
}

@Composable
fun EmailApp(viewModel: EmailViewModel, onAddAccount: () -> Unit) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val context = LocalContext.current
    
    val accounts by viewModel.accounts.collectAsState(emptyList())
    val selectedAccountEmail by viewModel.selectedAccountEmail.collectAsState()
    val folders by viewModel.folders.collectAsState(emptyList())
    val selectedFolderName by viewModel.selectedFolderName.collectAsState()
    
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
                Text("Unified Inbox", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                NavigationDrawerItem(
                    label = { Text("All Accounts") },
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
                    label = { Text("Add Account") },
                    selected = false,
                    onClick = { 
                        onAddAccount()
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
                
                Spacer(Modifier.weight(1f))
                NavigationDrawerItem(
                    label = { Text("Logout Current Account") },
                    selected = false,
                    onClick = { 
                        viewModel.logout(context)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
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
        }
    }
}

@Composable
fun FolderList(folders: List<EmailFolder>, selectedFolder: String, onSelect: (String) -> Unit) {
    val folderTree = remember(folders) { buildFolderTree(folders) }
    
    LazyColumn {
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

fun androidx.compose.foundation.lazy.LazyListScope.renderFolderTree(
    node: FolderNode, 
    depth: Int, 
    selectedFolder: String, 
    onSelect: (String) -> Unit
) {
    item {
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
    }
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
    var isSearching by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            if (isSearching) {
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
                        IconButton(onClick = { viewModel.refresh(context) }) {
                            IconRestore()
                        }
                        IconButton(onClick = { isSearching = true }) {
                            IconSearch()
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onComposeClick) {
                IconAdd()
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (messages.isEmpty() && searchQuery.isEmpty()) {
                Text(
                    text = "No messages found. Try refreshing.",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(messages) { message ->
                        val accountColor = Color(EmailAccount(message.accountEmail, "", "").getColor())
                        
                        Row(modifier = Modifier.clickable { onMessageClick(message) }.height(IntrinsicSize.Min)) {
                            if (selectedAccountEmail == null) {
                                Surface(
                                    modifier = Modifier.width(4.dp).fillMaxHeight(),
                                    color = accountColor
                                ) {}
                            }
                            ListItem(
                                headlineContent = {
                                    Text(text = message.subject, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                },
                                supportingContent = {
                                    Column {
                                        Text(text = message.from.substringBefore("<").trim(), style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = message.body?.take(100) ?: "",
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
                                modifier = Modifier.weight(1f)
                            )
                        }
                        HorizontalDivider()
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
            items(messages) { msg ->
                MessageItem(msg, viewModel, onReply, onForward)
            }
        }
    }
}

@Composable
fun MessageItem(
    msg: EmailMessage, 
    viewModel: EmailViewModel,
    onReply: (String, String, String?) -> Unit,
    onForward: (String, String?) -> Unit
) {
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    LaunchedEffect(msg.id) {
        attachments = viewModel.getAttachments(msg.accountEmail, msg.id)
    }

    Column {
        ListItem(
            overlineContent = {
                Text(text = "From: ${msg.from}", style = MaterialTheme.typography.labelSmall)
            },
            headlineContent = {
                Text(text = "To: ${msg.to ?: ""}", style = MaterialTheme.typography.labelSmall)
            },
            supportingContent = {
                Text(text = msg.date, style = MaterialTheme.typography.labelSmall)
            },
            trailingContent = {
                Row {
                    IconButton(onClick = { onReply(msg.from, msg.subject, msg.serverId) }) {
                        IconUndo()
                    }
                    IconButton(onClick = { onForward(msg.subject, msg.body) }) {
                        IconChevronRight()
                    }
                }
            }
        )
        Text(
            text = msg.body ?: "(No Content)", 
            style = MaterialTheme.typography.bodyMedium, 
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        
        if (attachments.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Attachments:", style = MaterialTheme.typography.labelLarge)
                attachments.forEach { att ->
                    AttachmentItem(att, viewModel)
                }
            }
        }
        HorizontalDivider()
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
    onBack: () -> Unit
) {
    val accounts by viewModel.accounts.collectAsState(emptyList())
    val selectedAccount by viewModel.selectedAccount.collectAsState()
    
    var fromAccount by remember(selectedAccount, accounts) { 
        mutableStateOf(selectedAccount ?: accounts.firstOrNull()) 
    }
    
    var to by remember { mutableStateOf(initialTo) }
    var subject by remember { mutableStateOf(initialSubject) }
    var body by remember { mutableStateOf(initialBody) }
    var sending by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    
    var showAccountPicker by remember { mutableStateOf(false) }

    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { attachments = attachments + it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compose") },
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
                            attachments = attachments,
                            inReplyTo = inReplyTo, 
                            references = references, 
                            onSuccess = {
                                sending = false
                                onBack()
                            }, 
                            onError = { sending = false }
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
                headlineContent = { Text(fromAccount?.email ?: "Select Account") },
                overlineContent = { Text("From") },
                trailingContent = { IconChevronRight() },
                modifier = Modifier.clickable { showAccountPicker = true }
            )
            HorizontalDivider()

            if (showAccountPicker) {
                AlertDialog(
                    onDismissRequest = { showAccountPicker = false },
                    confirmButton = {},
                    title = { Text("Select Sender") },
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

            OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text("To") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Body") }, modifier = Modifier.fillMaxWidth().weight(1f))
            
            if (attachments.isNotEmpty()) {
                Text("Attachments:", style = MaterialTheme.typography.labelLarge)
                attachments.forEach { uri ->
                    Text(uri.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
