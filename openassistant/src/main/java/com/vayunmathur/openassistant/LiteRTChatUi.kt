package com.vayunmathur.openassistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.ai.edge.litertlm.*
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.util.*
import java.util.regex.Pattern

/**
 * ToolSet implementation for LiteRT-LM.
 */
class AssistantToolSet(private val context: Context) : ToolSet {

    @Tool(description = "Get the current date and time in the local timezone")
    fun getLocalCurrentDateTime(): String {
        val now = LocalDateTime.now()
        val tz = TimeZone.getDefault().id
        return "$tz: $now"
    }

    @SuppressLint("QueryPermissionsNeeded")
    @Tool(description = "Get a list of installed apps on the device")
    fun getListOfApps(): String {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps.map { it.loadLabel(pm).toString() }.toString()
    }

    @Tool(description = "Open an app given its package id")
    fun openApp(
        @ToolParam(description = "the package id of the app to open") packageId: String
    ): String {
        val intent = context.packageManager.getLaunchIntentForPackage(packageId)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Success: Opened $packageId"
        } else {
            "Error: App not found"
        }
    }

    @Tool(description = "Send a message to a recipient")
    fun sendMessage(
        @ToolParam(description = "the phone number of the recipient") recipient: String,
        @ToolParam(description = "the content of the message") message: String
    ): String {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = "smsto:$recipient".toUri()
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened messaging app."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Make a phone call to a recipient")
    fun makePhoneCall(
        @ToolParam(description = "the phone number of the recipient") recipient: String
    ): String {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = "tel:$recipient".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened dialer."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Get the current weather for a specific location")
    fun getWeather(
        @ToolParam(description = "the latitude of the location") latitude: Double,
        @ToolParam(description = "the longitude of the location") longitude: Double
    ): String {
        return "Weather data for ($latitude, $longitude): 22°C, Sunny."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiteRTChatUi(modelFile: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var engine by remember { mutableStateOf<Engine?>(null) }
    var conversation by remember { mutableStateOf<Conversation?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var inputText by remember { mutableStateOf("") }
    var isInitializing by remember { mutableStateOf(true) }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageFile by remember { mutableStateOf<File?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var audioRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordedAudioFile by remember { mutableStateOf<File?>(null) }

    var activeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val isGenerating by remember { derivedStateOf { activeJob?.isActive == true } }

    val assistantTools = remember { AssistantToolSet(context) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val recordAudioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            val file = File(context.cacheDir, "recording_${System.currentTimeMillis()}.amr")
            recordedAudioFile = file
            startRecording(context, file) { recorder ->
                audioRecorder = recorder
                isRecording = true
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        selectedImageUri = uri
        uri?.let { scope.launch(Dispatchers.IO) { selectedImageFile = copyUriToFile(context, it) } }
    }

    LaunchedEffect(modelFile) {
        withContext(Dispatchers.IO) {
            try {
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    cacheDir = context.cacheDir.absolutePath
                )
                val newEngine = Engine(config).apply { initialize() }
                engine = newEngine

                val systemPrompt = """
                    You are a helpful Android assistant with access to tools.
                    When you need to use a tool, you MUST use the following format:
                    <start_function_call>tool_name(arg1="val1", arg2="val2")<end_function_call>
                    
                    Important instructions:
                    1. Respond ONLY with the function call block when a tool is needed, then end your turn immediately.
                    2. Once you receive the tool result, interpret the data and convey the information to the user in natural, conversational language.
                    3. Do not repeat the raw, machine-readable tool output (like JSON, raw lists, or technical timestamps) directly to the user.
                """.trimIndent()

                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    tools = listOf(tool(assistantTools)),
                    automaticToolCalling = false
                )
                conversation = newEngine.createConversation(conversationConfig)
                isInitializing = false
            } catch (e: Exception) {
                e.printStackTrace()
                isInitializing = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeJob?.cancel()
            audioRecorder?.apply { try { stop() } catch(e: Exception) {}; release() }
            conversation?.close()
            engine?.close()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Open Assistant", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                if (selectedImageUri != null || isRecording) {
                    Row(modifier = Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selectedImageUri != null) {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(60.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("Image", style = MaterialTheme.typography.labelSmall)
                                    IconButton(onClick = { selectedImageUri = null; selectedImageFile = null }, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
                                        Icon(painterResource(android.R.drawable.ic_menu_close_clear_cancel), contentDescription = null, tint = Color.Red)
                                    }
                                }
                            }
                        }
                        if (isRecording) {
                            Spacer(Modifier.width(8.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(8.dp)) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Recording...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { stopRecording(audioRecorder); audioRecorder = null; isRecording = false; recordedAudioFile = null }, contentPadding = PaddingValues(0.dp)) {
                                        Text("Cancel", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(tonalElevation = 2.dp, shadowElevation = 1.dp, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        if (!isRecording) {
                            IconButton(onClick = { imagePickerLauncher.launch("image/*") }, enabled = !isInitializing && !isGenerating) { IconAdd() }
                            IconButton(onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    val file = File(context.cacheDir, "recording_${System.currentTimeMillis()}.amr"); recordedAudioFile = file
                                    startRecording(context, file) { recorder -> audioRecorder = recorder; isRecording = true }
                                } else { recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO) }
                            }, enabled = !isInitializing && !isGenerating) {
                                Icon(painterResource(R.drawable.baseline_mic_24), contentDescription = "Record Voice")
                            }
                        }

                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            enabled = !isInitializing && !isGenerating && !isRecording,
                            placeholder = { Text("Ask anything...") },
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                        )

                        if (isGenerating) {
                            IconButton(onClick = { activeJob?.cancel(); activeJob = null }) { IconStop(MaterialTheme.colorScheme.error) }
                        } else {
                            val canSend = (inputText.isNotBlank() || selectedImageFile != null || recordedAudioFile != null)
                            IconButton(
                                enabled = !isInitializing && canSend,
                                onClick = {
                                    if (isRecording) { stopRecording(audioRecorder); audioRecorder = null; isRecording = false }
                                    sendMessageManual(scope = scope, conversation = conversation, assistantTools = assistantTools, messages = messages, text = inputText, imageFile = selectedImageFile, audioFile = recordedAudioFile, onComplete = {
                                        inputText = ""; selectedImageFile = null; selectedImageUri = null; recordedAudioFile = null
                                    }, onJobFinished = {activeJob = null} , onJobStarted = { activeJob = it })
                                }
                            ) {
                                Icon(painterResource(android.R.drawable.ic_menu_send), contentDescription = "Send", tint = if (canSend) MaterialTheme.colorScheme.primary else Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).background(MaterialTheme.colorScheme.background)) {
            if (isInitializing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(messages) { msg -> ChatBubble(msg) }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        if (message.isUser) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp), modifier = Modifier.widthIn(max = 280.dp)) {
                Text(text = message.text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp)
            }
        } else {
            Text(text = message.text, modifier = Modifier.widthIn(max = 300.dp), color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}

private fun sendMessageManual(
    scope: kotlinx.coroutines.CoroutineScope,
    conversation: Conversation?,
    assistantTools: AssistantToolSet,
    messages: MutableList<ChatMessage>,
    text: String,
    imageFile: File?,
    audioFile: File?,
    onComplete: () -> Unit,
    onJobFinished: () -> Unit,
    onJobStarted: (kotlinx.coroutines.Job) -> Unit
) {
    val userDisplay = when {
        audioFile != null -> "🎤 Audio Message"
        imageFile != null -> "🖼️ Image Message"
        else -> ""
    } + if (text.isNotBlank()) " $text" else ""
    messages.add(ChatMessage(userDisplay.trim(), true))

    val aiMessageIndex = messages.size
    messages.add(ChatMessage("", false))

    val job = scope.launch {
        val initialParts = mutableListOf<Content>()
        imageFile?.let { initialParts.add(Content.ImageFile(it.absolutePath)) }
        audioFile?.let { initialParts.add(Content.AudioFile(it.absolutePath)) }
        initialParts.add(Content.Text(text))

        var nextInput: Any = Contents.of(initialParts)
        var isLooping = true

        while (isLooping) {
            var fullResponseText = ""
            var displayedText = ""
            var tagBuffer = ""
            var insideTag = false
            var insideFunctionBlock = false

            val stream = if (nextInput is Contents) {
                conversation?.sendMessageAsync(nextInput)
            } else {
                conversation?.sendMessageAsync(nextInput as Message)
            } ?: break

            stream.catch { e ->
                messages[aiMessageIndex] = ChatMessage("Error: ${e.message}", false)
                isLooping = false
            }.collect { chunk ->
                val chunkText = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                fullResponseText += chunkText

                // Character-by-character filtering and Turn Control
                for (char in chunkText) {
                    if (char == '<') {
                        insideTag = true
                        tagBuffer = "<"
                    } else if (insideTag) {
                        tagBuffer += char
                        if (char == '>') {
                            insideTag = false

                            when {
                                tagBuffer.contains("start_function_call") -> {
                                    insideFunctionBlock = true
                                }
                                tagBuffer.contains("end_function_call") -> {
                                    insideFunctionBlock = false
                                }
                                tagBuffer.contains("end_of_turn") -> {
                                }
                            }
                            tagBuffer = ""
                        }
                    } else {
                        if (!insideFunctionBlock) {
                            displayedText += char
                        }
                    }
                }

                if (displayedText.isNotBlank()) {
                    messages[aiMessageIndex] = ChatMessage(displayedText, false)
                }
            }

            // Execute logic using regex on the FULL raw response
            val toolRegex = Pattern.compile("<start_function_call>(.*?)<end_function_call>", Pattern.DOTALL)
            val matcher = toolRegex.matcher(fullResponseText)

            if (matcher.find()) {
                val callBody = matcher.group(1)?.trim() ?: ""
                val toolResult = processManualCallBody(callBody, assistantTools)

                withContext(Dispatchers.Main) {
                    messages[aiMessageIndex] = ChatMessage("Thinking...", false)
                }

                nextInput = Message.tool(Contents.of(listOf(Content.ToolResponse("manual_action", toolResult))))
            } else {
                withContext(Dispatchers.Main) {
                    // Final UI cleanup
                    val finalDisplay = if (insideTag && !tagBuffer.contains("function_call") && !tagBuffer.contains("end_of_turn")) {
                        displayedText + tagBuffer
                    } else {
                        displayedText
                    }
                    messages[aiMessageIndex] = ChatMessage(finalDisplay, false)
                }
                isLooping = false
            }
        }
        onJobFinished()
    }
    onJobStarted(job)
    onComplete()
}

private fun processManualCallBody(body: String, tools: AssistantToolSet): String {
    return try {
        val name = body.substringBefore("(").trim()
        val argsString = body.substringAfter("(").substringBeforeLast(")")

        val argsMap = mutableMapOf<String, String>()
        if (argsString.isNotBlank()) {
            argsString.split(",").forEach { pair ->
                val parts = pair.split("=")
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                    argsMap[key] = value
                }
            }
        }

        when (name) {
            "get_local_current_date_time" -> tools.getLocalCurrentDateTime()
            "get_list_of_apps" -> tools.getListOfApps()
            "open_app" -> tools.openApp(argsMap["packageId"] ?: "")
            "send_message" -> tools.sendMessage(argsMap["recipient"] ?: "", argsMap["message"] ?: "")
            "make_phone_call" -> tools.makePhoneCall(argsMap["recipient"] ?: "")
            "get_weather" -> tools.getWeather(
                argsMap["latitude"]?.toDoubleOrNull() ?: 0.0,
                argsMap["longitude"]?.toDoubleOrNull() ?: 0.0
            )
            else -> "Error: Unknown tool $name"
        }
    } catch (e: Exception) {
        "Error parsing tool call: ${e.message}"
    }
}

private fun startRecording(context: Context, file: File, onStarted: (MediaRecorder) -> Unit) {
    val recorder = MediaRecorder(context).apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
        setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
        setOutputFile(file.absolutePath)
        prepare()
        start()
    }
    onStarted(recorder)
}

private fun stopRecording(recorder: MediaRecorder?) {
    try { recorder?.stop(); recorder?.release() } catch (e: Exception) { e.printStackTrace() }
}

private fun copyUriToFile(context: Context, uri: Uri): File {
    val tempFile = File(context.cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tempFile).use { output -> input.copyTo(output) } }
    return tempFile
}

data class ChatMessage(val text: String, val isUser: Boolean)