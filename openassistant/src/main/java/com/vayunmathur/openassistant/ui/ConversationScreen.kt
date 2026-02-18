package com.vayunmathur.openassistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavBackStack
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.pop
import com.vayunmathur.library.util.setLast
import com.vayunmathur.openassistant.R
import com.vayunmathur.openassistant.Route
import com.vayunmathur.openassistant.api.GrokApi
import com.vayunmathur.openassistant.api.GrokRequest
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.data.Tools
import com.vayunmathur.openassistant.data.database.MessageDatabase
import com.vayunmathur.openassistant.data.toGrokMessage
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okio.Buffer
import kotlin.random.Random
import kotlin.random.nextUInt


class ConversationWorker(appContext: Context, workerParams: WorkerParameters): CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val conversationID = inputData.getLong("conv_id", 0)
        val userMessage = inputData.getString("userMessage")!!
        val uris = inputData.getStringArray("uris")!!.map { it.toUri() }
        val ds = DataStoreUtils.getInstance(applicationContext)
        ds.setBoolean("isThinking", true)
        delay(500)
        val db = applicationContext.buildDatabase<MessageDatabase>()
        val viewModel = DatabaseViewModel(db,Message::class to db.messageDao(), Conversation::class to db.conversationDao())
        send(viewModel, ds, conversationID, userMessage, uris)
        ds.setBoolean("isThinking", false)
        return Result.success()
    }

    suspend fun requestResponse(viewModel: DatabaseViewModel, conversationID: Long, apiKey: String, userMessage: Message? = null) {
        val grokApi = GrokApi(apiKey)

        val messages = viewModel.data<Message>().value.filter { it.conversationId == conversationID }

        val request = GrokRequest(
            messages = (messages + userMessage).filterNotNull().map(Message::toGrokMessage),
            model = "grok-4-fast-reasoning",
            stream = true,
            temperature = 0.7,
            tools = Tools.API_TOOLS
        )
        if (userMessage != null)
            viewModel.upsert(userMessage)

        var assistantMessage = Message(
            id = Random.nextLong(),
            conversationId = conversationID,
            role = "assistant",
            textContent = "",
            images = emptyList(),
            toolCalls = listOf()
        )
        viewModel.upsert(assistantMessage)

        var fullResponse = ""
        var usedTools = false

        try {
            grokApi.getGrokCompletionStream(request) {
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show()
                }
            }.collect { chunk ->
                val delta = chunk.choices.first().delta
                delta.toolCalls?.forEach {
                    usedTools = true
                    val action = Tools.getToolAction(it.function.name)
                    if (action != null) {
                        val result = action(Json.decodeFromString(it.function.arguments), applicationContext)
                        val message = Message(
                            conversationId = conversationID,
                            role = "tool",
                            textContent = result.llmResponse,
                            displayContent = result.userResponse,
                            images = emptyList(),
                            toolCallId = it.id,
                        )
                        viewModel.upsert(message)
                    }
                    assistantMessage =
                        assistantMessage.copy(toolCalls = assistantMessage.toolCalls + it)
                    viewModel.upsert(assistantMessage)
                }
                delta.content?.let {
                    fullResponse += it
                    assistantMessage = assistantMessage.copy(textContent = fullResponse)
                    viewModel.upsert(assistantMessage)
                }
            }
        } catch (e: GrokApi.GrokException) {
            when (e.errorNum) {
                400, 401 -> {
                    // 400 means invalid api key, 401 means no api key included
                    if (e.errorNum == 400) {
                        Toast.makeText(applicationContext, "Invalid API key", Toast.LENGTH_SHORT).show()
                    }
                    e.printStackTrace()
                }

                else -> {
                    throw e
                }
            }
        } finally {
        }

        if (usedTools) {
            delay(1000)
            requestResponse(viewModel, conversationID, apiKey)
        }
    }

    suspend fun send(viewModel: DatabaseViewModel, ds: DataStoreUtils, conversationID: Long, userInput: String, selectedImageUris: List<Uri>) {
        val imageBase64s = selectedImageUris.map { uri ->
            val inputStream = applicationContext.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val baos = Buffer()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos.outputStream())
            Base64.encodeToString(baos.readByteArray(), Base64.DEFAULT or Base64.NO_WRAP)
        }

        val userMessage = Message(
            conversationId = conversationID,
            role = "user",
            textContent = userInput,
            images = imageBase64s
        )
        requestResponse(viewModel, conversationID, ds.getString("api_key")!!, userMessage)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel,
    ds: DataStoreUtils,
    conversationID: Long
) {
    val isThinking by ds.booleanFlow("isThinking").collectAsState(false)

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val conversation by viewModel.getNullable<Conversation>(conversationID)

    val allMessages by viewModel.data<Message>().collectAsState()
    val messages = allMessages.filter { it.conversationId == conversationID }

    val visibleMessages by remember(messages, conversation) {
        derivedStateOf {
            messages.filter { it.textContent.isNotBlank() && it.conversationId == conversationID }
        }
    }

    var userInput by remember { mutableStateOf("") }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var selectedImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        selectedImageUris = uris
    }
    val lazyListState = rememberLazyListState()

    fun createNewConversation(beforeNavigating: (Long) -> Unit = {}) {
        val newConversation = Conversation(title = "Conversation ${Random.nextUInt(0xFFFFu).toUShort().toHexString()}")
        viewModel.upsert(newConversation) {
            beforeNavigating(it)
            if(backStack.last() is Route.Conversation)
                backStack.setLast(Route.Conversation(it))
            else
                backStack.add(Route.Conversation(it))
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModel.delete(conversation)
        backStack.pop()
    }

    LaunchedEffect(visibleMessages.size) {
        lazyListState.animateScrollToItem(if (visibleMessages.isEmpty()) 0 else visibleMessages.size - 1)
    }

    fun send() {
        if (userInput.isNotBlank()) {
            if (conversationID == 0L) {
                createNewConversation() { id ->
                    // run wiht userInput
                    val workManager = androidx.work.WorkManager.getInstance(context)
                    workManager.enqueueUniqueWork("conversation-${id}", ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<ConversationWorker>().setInputData(
                            Data.Builder().apply {
                                putLong("conv_id", id)
                                putString("userMessage", userInput)
                                putStringArray("uris", selectedImageUris.map { it.toString() }.toTypedArray())
                            }.build()
                        ).build()
                    )
                    userInput = ""
                    selectedImageUris = listOf()
                }
            } else {
                val workManager = androidx.work.WorkManager.getInstance(context)
                workManager.enqueueUniqueWork("conversation-${conversationID}", ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<ConversationWorker>().setInputData(
                        Data.Builder().apply {
                            putLong("conv_id", conversationID)
                            putString("userMessage", userInput)
                            putStringArray("uris", selectedImageUris.map { it.toString() }.toTypedArray())
                        }.build()
                    ).build()
                )
                userInput = ""
                selectedImageUris = listOf()
            }
        }
    }

    if (showApiKeyDialog) {
        ApiKeyDialog(
            onDismiss = { showApiKeyDialog = false },
            onSave = { apiKey ->
                coroutineScope.launch {
                    ds.setString("api_key", apiKey)
                    showApiKeyDialog = false
                }
            }
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(conversation?.title ?: "New Conversation") },
                navigationIcon = {
                    if(backStack.last() is Route.Conversation) {
                        IconNavigation(backStack)
                    }
                },
                actions = {
                    conversation?.let {
                        IconButton(::createNewConversation) {
                            IconAdd()
                        }
                        IconButton(onClick = { deleteConversation(it) }) {
                            IconDelete()
                        }
                    }
                    IconButton(onClick = { showApiKeyDialog = true }) {
                        IconSettings()
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                Column {
                    OutlinedTextField(
                        userInput,
                        { userInput = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Ask Grok...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        leadingIcon = {
                            IconButton(onClick = { imageLauncher.launch("image/*") }) {
                                Icon(
                                    painterResource(id = R.drawable.baseline_add_photo_alternate_24),
                                    contentDescription = "Add Image"
                                )
                            }
                        },
                        trailingIcon = {
                            IconButton(::send) {
                                Icon(
                                    painterResource(R.drawable.outline_send_24),
                                    contentDescription = "Send"
                                )
                            }
                        }
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (visibleMessages.isEmpty() && !isThinking) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Send a message to start chatting!")
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(visibleMessages, key = { it.id }) { message ->
                        when (message.role) {
                            "user" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.padding(start = 64.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            if (message.textContent.isNotBlank()) {
                                                Text(text = message.textContent)
                                            }
                                            if (message.images.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    items(message.images) { base64 ->
                                                        val imageBytes =
                                                            Base64.decode(
                                                                base64,
                                                                Base64.DEFAULT
                                                            )
                                                        val bitmap =
                                                            BitmapFactory.decodeByteArray(
                                                                imageBytes,
                                                                0,
                                                                imageBytes.size
                                                            )
                                                        Image(
                                                            bitmap = bitmap.asImageBitmap(),
                                                            contentDescription = null,
                                                            modifier = Modifier
                                                                .size(128.dp)
                                                                .clip(
                                                                    RoundedCornerShape(
                                                                        8.dp
                                                                    )
                                                                )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            "assistant" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    Column(modifier = Modifier.padding(end = 64.dp)) {
                                        MarkdownText(
                                            markdown = message.displayContent ?: message.textContent,
                                            style = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onBackground)
                                        )
                                    }
                                }
                            }

                            else -> { // tool
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    Column(modifier = Modifier.padding(end = 64.dp)) {
                                        Text(
                                            message.displayContent ?: message.textContent,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (isThinking) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 64.dp),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "Thinking...")
                            }
                        }
                    }
                }
            }
            LazyRow(modifier = Modifier.padding(8.dp)) {
                items(selectedImageUris) { uri ->
                    val bitmap = remember {
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                    val filename = uri.lastPathSegment
                    if (bitmap != null) {
                        InputChip(
                            selected = false,
                            onClick = { },
                            label = {
                                Text(filename ?: "image")
                            },
                            trailingIcon = {
                                IconButton(onClick = {
                                    selectedImageUris -= uri
                                }) {
                                    Icon(
                                        painterResource(id = R.drawable.baseline_close_24),
                                        contentDescription = "Remove Image"
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ApiKeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter API Key") },
        text = {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(apiKey) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
}
