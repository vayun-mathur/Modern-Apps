package com.vayunmathur.openassistant.ui
import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.NavigationDrawerItem
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.currentWindowAdaptiveInfo
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.ui.NavigationSuiteScaffold
import com.vayunmathur.library.ui.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.vayunmathur.openassistant.R
import com.vayunmathur.openassistant.Route
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.util.AssistantViewModel
import com.vayunmathur.openassistant.util.ChatActions
import com.vayunmathur.openassistant.util.ChatUiState
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.openassistant.util.copyUriToFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Binds [AssistantViewModel] and the nav back stack to the stateless [ChatScreen]. The
 * conversation drawer stays here: it is chrome around the chat rather than part of it, and
 * it needs the adaptive window info.
 */
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
    val surfaces by assistantViewModel.a2ui.surfaces.collectAsState()
    LaunchedEffect(filteredMessages) { assistantViewModel.a2ui.sync(filteredMessages) }

    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var inputText by remember { mutableStateOf("") }
    val selectedImageUris = remember { mutableStateListOf<Uri>() }
    val selectedImageFiles = remember { mutableStateListOf<File>() }

    val recordAudioPermission = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO) { isGranted ->
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

    val actions = object : ChatActions {
        override fun setInputText(text: String) { inputText = text }

        override fun openConversations() { scope.launch { drawerState.open() } }

        override fun openSettings() { backStack.add(Route.SettingsPage) }

        override fun newConversation() { backStack.reset(Route.ConversationPage(0)) }

        override fun addImage() { imagePicker.launch("image/*") }

        override fun removeImage(uri: Uri) {
            val idx = selectedImageUris.indexOf(uri)
            if (idx != -1) {
                selectedImageUris.removeAt(idx)
                if (idx < selectedImageFiles.size) selectedImageFiles.removeAt(idx)
            }
        }

        override fun record() {
            recordAudioPermission()
        }

        override fun cancelMedia() {
            selectedImageUris.clear(); selectedImageFiles.clear()
            assistantViewModel.cancelRecording()
        }

        override fun send() {
            if (isRecording) assistantViewModel.stopRecording()
            val newConv = resources.getString(R.string.new_conversation)
            val imagePaths = selectedImageFiles.map { it.absolutePath }
            val textToSend = inputText
            scope.launch {
                // Awaited rather than read: the WAV is written after stopRecording() returns, so
                // reading the path here would dispatch one to a file that does not exist yet.
                val audioPath = assistantViewModel.awaitRecordedAudio()
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
        }
    }

    NavigationSuiteScaffold(layoutType = navType, navigationSuiteItems = {
        allConversations.forEach { item(it.id == conversationId, { backStack.reset(Route.ConversationPage(it.id)) }, {}, label = { Text(it.title, Modifier.fillMaxWidth()) }, badge = {
            IconButton({
                assistantViewModel.deleteConversation(it)
                if (it.id == conversationId) backStack.reset(Route.ConversationPage(0))
            }) {
                IconDelete()
            }
        }) }
    }) {
        ModalNavigationDrawer({
            ModalDrawerSheet {
                allConversations.forEach { NavigationDrawerItem({ Text(it.title) }, it.id == conversationId, { backStack.reset(Route.ConversationPage(it.id)) }, Modifier.fillMaxWidth(), icon = {}, badge = {
                    IconButton({
                        assistantViewModel.deleteConversation(it)
                        if (it.id == conversationId) backStack.reset(Route.ConversationPage(0))
                    }, Modifier.offset(x=15.dp)) {
                        IconDelete()
                    }
                }) }
            }
        }, drawerState = drawerState) {
            ChatScreen(
                state = ChatUiState(
                    title = activeConversation?.title,
                    messages = filteredMessages,
                    surfaces = surfaces,
                    inputText = inputText,
                    attachments = selectedImageUris,
                    isRecording = isRecording,
                    showConversationsButton = navType == NavigationSuiteType.None && allConversations.isNotEmpty(),
                    showNewChatButton = conversationId != 0L,
                ),
                actions = actions,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}
