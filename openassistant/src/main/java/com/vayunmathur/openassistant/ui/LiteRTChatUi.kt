package com.vayunmathur.openassistant.ui
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.openassistant.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vayunmathur.library.util.NavBackStack
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.util.DatabaseViewModel
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiteRTChatUi(backStack: NavBackStack<Route>, conversationId: Long, viewModel: DatabaseViewModel) {
    val activeConversation by viewModel.getNullable<Conversation>(conversationId)
    val allMessages by viewModel.data<Message>().collectAsState(initial = emptyList())
    val filteredMessages = remember(allMessages, conversationId) {
        allMessages.filter { it.conversationId == conversationId }.sortedBy { it.timestamp }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    val selectedImageUris = remember { mutableStateListOf<Uri>() }
    val selectedImageFiles = remember { mutableStateListOf<File>() }
    var isRecording by remember { mutableStateOf(false) }
    var audioRecorder by remember { mutableStateOf<WavRecorder?>(null) }
    var recordedAudioFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.isNotEmpty()) listState.animateScrollToItem(filteredMessages.size - 1)
    }

    val recordAudioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            val file = File(context.cacheDir, "recording_${Clock.System.now().toEpochMilliseconds()}.wav")
            recordedAudioFile = file
            try {
                audioRecorder = WavRecorder(context, file, scope).apply { start() }
                isRecording = true
            } catch (e: Exception) {
                scope.launch {
                    snackbarHostState.showSnackbar("Mic error: ${e.localizedMessage}")
                }
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri ->
            selectedImageUris.add(uri)
            scope.launch(Dispatchers.IO) {
                val file = copyUriToFile(context, uri)
                withContext(Dispatchers.Main) { selectedImageFiles.add(file) }
            }
        }
    }

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val navType = if (adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)) {
        NavigationSuiteType.WideNavigationRailExpanded
    } else NavigationSuiteType.None

    val allConversations by viewModel.data<Conversation>().collectAsState(initial = emptyList())
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    NavigationSuiteScaffold(layoutType = navType, navigationSuiteItems = {
        allConversations.forEach { item(it.id == conversationId, { backStack.reset(Route.ConversationPage(it.id)) }, {}, label = { Text(it.title, Modifier.fillMaxWidth()) }) }
    }) {
        ModalNavigationDrawer({
            ModalDrawerSheet {
                allConversations.forEach { NavigationDrawerItem({ Text(it.title) }, it.id == conversationId, { backStack.reset(Route.ConversationPage(it.id)) }, Modifier.fillMaxWidth()) }
            }
        }, drawerState = drawerState) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text(activeConversation?.title ?: "New Conversation", fontWeight = FontWeight.Bold) },
                        actions = { if (conversationId != 0L) IconButton({ backStack.reset(Route.ConversationPage(0)) }) { IconAdd() } },
                        navigationIcon = { if (navType == NavigationSuiteType.None) IconButton({ scope.launch { drawerState.open() } }) { IconMenu() } }
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
                                val file = File(context.cacheDir, "recording_${Clock.System.now().toEpochMilliseconds()}.wav")
                                recordedAudioFile = file
                                try {
                                    audioRecorder = WavRecorder(context, file, scope).apply { start() }
                                    isRecording = true
                                } catch (e: Exception) {}
                            } else recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onSend = {
                            if (isRecording) { audioRecorder?.stop(); audioRecorder = null; isRecording = false }
                            scope.launch {
                                var currentId = conversationId
                                if (currentId == 0L) {
                                    currentId = viewModel.upsert(Conversation("New Conversation"))
                                    backStack.reset(Route.ConversationPage(currentId))
                                }
                                viewModel.upsert(Message(currentId, inputText, "user", selectedImageFiles.map { it.absolutePath }, recordedAudioFile != null))
                                context.startService(Intent(context, InferenceService::class.java).apply {
                                    putExtra("conversation_id", currentId)
                                    putExtra("user_text", inputText)
                                    putExtra("image_paths", selectedImageFiles.map { it.absolutePath }.toTypedArray())
                                    putExtra("audio_path", recordedAudioFile?.absolutePath)
                                })
                                inputText = ""; selectedImageFiles.clear(); selectedImageUris.clear(); recordedAudioFile = null
                            }
                        },
                        onCancelMedia = {
                            selectedImageUris.clear(); selectedImageFiles.clear()
                            audioRecorder?.stop(); audioRecorder = null; isRecording = false; recordedAudioFile = null
                        }
                    )
                }
            ) { padding ->
                LazyColumn(state = listState, modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(filteredMessages, key = { it.id }) { ChatBubble(it) }
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
    onCancelMedia: () -> Unit
) {
    Column(modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        if (selectedImageUris.isNotEmpty() || isRecording) {
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                if (selectedImageUris.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f, false)) {
                        items(selectedImageUris) { uri ->
                            Box(Modifier.size(80.dp)) {
                                AsyncImage(uri, null, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
                                IconButton(onCancelMedia) { IconClose() }
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
    val isUser = message.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        if (isUser) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                Column(Modifier.padding(if (message.imagePaths.isNotEmpty() || message.hasAudio) 4.dp else 12.dp)) {
                    message.imagePaths.forEach { AsyncImage(it, null, Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) }
                    if (message.hasAudio) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(android.R.drawable.ic_btn_speak_now), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.voice_message), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp)
                    }
                    if (message.text.isNotBlank()) Text(message.text, Modifier.padding(8.dp, 4.dp), color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp)
                }
            }
        } else if (message.text.isNotBlank()) {
            MarkdownText(message.text, Modifier.padding(vertical = 4.dp, horizontal = 0.dp).padding(end = 100.dp), style = LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 22.sp))
        }
    }
}
