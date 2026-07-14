package com.vayunmathur.openassistant.ui
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import android.content.ClipData
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.vayunmathur.openassistant.R
import com.vayunmathur.openassistant.Route
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.util.AssistantViewModel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vayunmathur.library.util.NavBackStack
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.util.parseMarkdown
import com.vayunmathur.openassistant.util.copyUriToFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiteRTChatUi(
    backStack: NavBackStack<Route>,
    conversationId: Long,
    assistantViewModel: AssistantViewModel,
) {
    val activeConversation by assistantViewModel.conversationByIdState(conversationId)
    val filteredMessages by assistantViewModel.messagesFor(conversationId).collectAsState(initial = emptyList())
    val isRecording by assistantViewModel.isRecording.collectAsState()
    val recordedAudioPath by assistantViewModel.recordedAudioPath.collectAsState()

    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    val selectedImageUris = remember { mutableStateListOf<Uri>() }
    val selectedImageFiles = remember { mutableStateListOf<File>() }

    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.isNotEmpty()) listState.animateScrollToItem(filteredMessages.size - 1)
    }

    val recordAudioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            assistantViewModel.startRecording()
            if (!assistantViewModel.isRecording.value && assistantViewModel.recordedAudioPath.value == null) {
                scope.launch {
                    snackbarHostState.showSnackbar(resources.getString(R.string.mic_error_format, ""))
                }
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            selectedImageUris.add(uri)
            scope.launch(Dispatchers.IO) {
                val file = copyUriToFile(context, uri)
                if(file != null) {
                    withContext(Dispatchers.Main) { selectedImageFiles.add(file) }
                }
            }
        }
    }

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val navType = if (adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)) {
        NavigationSuiteType.NavigationDrawer
    } else NavigationSuiteType.None

    val allConversations by assistantViewModel.conversations.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(allConversations) {
        if(allConversations.isEmpty()) {
            drawerState.close()
        }
    }

    NavigationSuiteScaffold(layoutType = navType, navigationSuiteItems = {
        allConversations.forEach { item(it.id == conversationId, { backStack.reset(Route.ConversationPage(it.id)) }, {}, label = { Text(it.title, Modifier.fillMaxWidth()) }, badge = {
            IconButton({ assistantViewModel.deleteConversation(it) }) {
                IconDelete()
            }
        }) }
    }) {
        ModalNavigationDrawer({
            ModalDrawerSheet {
                allConversations.forEach { NavigationDrawerItem({ Text(it.title) }, it.id == conversationId, { backStack.reset(Route.ConversationPage(it.id)) }, Modifier.fillMaxWidth(), icon = {}, badge = {
                    IconButton({ assistantViewModel.deleteConversation(it) }, Modifier.offset(x=15.dp)) {
                        IconDelete()
                    }
                }) }
            }
        }, drawerState = drawerState) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    val newConv = stringResource(R.string.new_conversation)
                    CenterAlignedTopAppBar(
                        title = { Text(activeConversation?.title ?: newConv, fontWeight = FontWeight.Bold) },
                        actions = {
                            IconButton({ backStack.add(Route.SettingsPage) }) { IconSettings() }
                            if (conversationId != 0L) IconButton({ backStack.reset(Route.ConversationPage(0)) }) { IconAdd() }
                        },
                        navigationIcon = { if (navType == NavigationSuiteType.None && allConversations.isNotEmpty()) IconButton({ scope.launch { drawerState.open() } }) { IconMenu() } }
                    )
                },
                bottomBar = {
                    ChatInput(
                        Modifier.padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                        inputText = inputText,
                        onTextChange = { inputText = it },
                        selectedImageUris = selectedImageUris,
                        isRecording = isRecording,
                        onAddImage = { imagePicker.launch("image/*") },
                        onRecord = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                assistantViewModel.startRecording()
                            } else recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onSend = {
                            if (isRecording) assistantViewModel.stopRecording()
                            val newConv = resources.getString(R.string.new_conversation)
                            val imagePaths = selectedImageFiles.map { it.absolutePath }
                            val audioPath = recordedAudioPath
                            val textToSend = inputText
                            scope.launch {
                                var currentId = conversationId
                                if (currentId == 0L) {
                                    currentId = assistantViewModel.upsertConversation(Conversation(newConv))
                                    backStack.reset(Route.ConversationPage(currentId))
                                }
                                assistantViewModel.upsertMessage(Message(currentId, textToSend, "user", imagePaths, audioPath != null))
                                assistantViewModel.requestInference(currentId, textToSend, imagePaths, audioPath)
                                inputText = ""; selectedImageFiles.clear(); selectedImageUris.clear()
                                assistantViewModel.consumeRecordedAudio()
                            }
                        },
                        onCancelMedia = {
                            selectedImageUris.clear(); selectedImageFiles.clear()
                            assistantViewModel.cancelRecording()
                        },
                        onRemoveImage = { uri ->
                            val idx = selectedImageUris.indexOf(uri)
                            if (idx != -1) {
                                selectedImageUris.removeAt(idx)
                                if (idx < selectedImageFiles.size) selectedImageFiles.removeAt(idx)
                            }
                        }
                    )
                }
            ) { padding ->
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(filteredMessages, key = { it.id }) { ChatBubble(it) }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatInput(
    modifier: Modifier,
    inputText: String,
    onTextChange: (String) -> Unit,
    selectedImageUris: List<Uri>,
    isRecording: Boolean,
    onAddImage: () -> Unit,
    onRecord: () -> Unit,
    onSend: () -> Unit,
    onCancelMedia: () -> Unit,
    onRemoveImage: (Uri) -> Unit
) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        if (selectedImageUris.isNotEmpty() || isRecording) {
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                if (selectedImageUris.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f, false)) {
                        items(selectedImageUris, key = { it.toString() }) { uri ->
                            Box(Modifier.size(80.dp)) {
                                AsyncImage(
                                    ImageRequest.Builder(context)
                                        .data(uri)
                                        .memoryCacheKey("chat-attach-$uri")
                                        .build(),
                                    null,
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton({ onRemoveImage(uri) }) { IconClose() }
                            }
                        }
                    }
                }
                if (isRecording) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.recording), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            IconButton(onCancelMedia) { IconClose() }
                        }
                    }
                }
            }
        }

        Surface(tonalElevation = 3.dp, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp, 4.dp)) {
                IconButton(onAddImage) { IconAdd() }
                IconButton(onRecord) { Icon(painterResource(android.R.drawable.ic_btn_speak_now), "Voice") }
                TextField(
                    value = inputText,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.message_placeholder)) },
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                )
                val canSend = inputText.isNotBlank() || selectedImageUris.isNotEmpty() || isRecording
                IconButton(enabled = canSend, onClick = onSend) {
                    Icon(painterResource(android.R.drawable.ic_menu_send), "Send", tint = if (canSend) MaterialTheme.colorScheme.primary else Color.Gray)
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: Message) {
    val context = LocalContext.current
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        if (isUser) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                Column(Modifier.padding(if (message.imagePaths.isNotEmpty() || message.hasAudio) 4.dp else 12.dp)) {
                    message.imagePaths.forEach { path ->
                        AsyncImage(
                            ImageRequest.Builder(context)
                                .data(path)
                                .memoryCacheKey("chat-msg-$path")
                                .build(),
                            null,
                            Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    if (message.hasAudio) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(android.R.drawable.ic_btn_speak_now), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.voice_message), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp)
                    }
                    if (message.text.isNotBlank()) Text(message.text, Modifier.padding(8.dp, 4.dp), color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp)
                }
            }
        } else if (isTool) {
            val linkRegex = remember { Regex("\\[(.*?)\\]\\((.*?)\\)") }
            val match = remember(message.text) { linkRegex.find(message.text) }
            val cleanText = remember(message.text, match) {
                if (match != null) message.text.replace(match.value, match.groups[1]!!.value)
                else message.text
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 350.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(android.R.drawable.ic_dialog_info),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            cleanText,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)
                        )
                    }
                    if (match != null) {
                        val url = match.groups[2]!!.value
                        val label = match.groups[1]!!.value
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.w("LiteRTChatUi", "Failed to open link: $url", e)
                                }
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        } else if (message.text.isNotBlank()) {
            Text(
                parseMarkdown(message.text, showMarkers = false),
                Modifier.padding(vertical = 4.dp, horizontal = 0.dp).padding(end = 100.dp),
                style = LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 22.sp)
            )
        }
        if (message.text.isNotBlank() && !isTool) {
            IconButton(
                onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.text))) } },
                modifier = Modifier.size(32.dp).padding(top = 4.dp)
            ) {
                IconCopy(tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            }
        }
    }
}
