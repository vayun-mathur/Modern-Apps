package com.vayunmathur.openassistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.google.ai.edge.litertlm.*
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconStop
import kotlinx.coroutines.CoroutineScope
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
 * Data class for Chat Messages supporting multiple images and audio
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val imageUris: List<Uri> = emptyList(),
    val hasAudio: Boolean = false
)

/**
 * ToolSet implementation
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
    fun openApp(@ToolParam(description = "package id") packageId: String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(packageId)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Success: Opened $packageId"
        } else "Error: App not found"
    }

    @Tool(description = "Send a message")
    fun sendMessage(recipient: String, message: String): String {
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

    @Tool(description = "Make a phone call")
    fun makePhoneCall(recipient: String): String {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = "tel:$recipient".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened dialer."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @Tool(description = "Get weather")
    fun getWeather(latitude: Double, longitude: Double): String = "Weather: 22°C, Sunny."
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
    var globalError by remember { mutableStateOf<String?>(null) }

    // State for multiple images
    val selectedImageUris = remember { mutableStateListOf<Uri>() }
    val selectedImageFiles = remember { mutableStateListOf<File>() }

    var isRecording by remember { mutableStateOf(false) }
    var audioRecorder by remember { mutableStateOf<WavRecorder?>(null) }
    var recordedAudioFile by remember { mutableStateOf<File?>(null) }

    var activeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val isGenerating by remember { derivedStateOf { activeJob?.isActive == true } }

    val assistantTools = remember { AssistantToolSet(context) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val recordAudioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            val file = File(context.cacheDir, "recording_${System.currentTimeMillis()}.wav")
            recordedAudioFile = file
            try {
                val recorder = WavRecorder(context, file, scope)
                recorder.start()
                audioRecorder = recorder
                isRecording = true
                globalError = null
            } catch (e: Exception) {
                globalError = "Mic error: ${e.localizedMessage}"
            }
        } else {
            Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Support multiple picks
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        uris.forEach { uri ->
            selectedImageUris.add(uri)
            scope.launch(Dispatchers.IO) {
                val file = copyUriToFile(context, uri)
                withContext(Dispatchers.Main) { selectedImageFiles.add(file) }
            }
        }
    }

    LaunchedEffect(modelFile) {
        withContext(Dispatchers.IO) {
            try {
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    audioBackend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath
                )
                val newEngine = Engine(config)
                newEngine.initialize()
                engine = newEngine

                val systemPrompt = """
                    You are a helpful Android assistant. Use tool calls:
                    <start_function_call>tool_name(arg1="val1")<end_function_call>
                    Interpret tool results conversationally.
                """.trimIndent()

                conversation = newEngine.createConversation(ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    tools = listOf(tool(assistantTools)),
                    automaticToolCalling = false
                ) )
                isInitializing = false
            } catch (e: Exception) {
                isInitializing = false
                globalError = "Init error: ${e.localizedMessage}"
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Open Assistant", fontWeight = FontWeight.Bold) }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (selectedImageUris.isNotEmpty() || isRecording) {
                    Row(
                        modifier = Modifier.padding(bottom = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        if (selectedImageUris.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                items(selectedImageUris) { uri ->
                                    Box(modifier = Modifier.size(80.dp)) {
                                        AsyncImage(
                                            model = uri,
                                            contentDescription = "Selected image",
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentScale = ContentScale.Crop
                                        )
                                        IconButton(
                                            onClick = {
                                                val index = selectedImageUris.indexOf(uri)
                                                if (index != -1) {
                                                    selectedImageUris.removeAt(index)
                                                    if (index < selectedImageFiles.size) selectedImageFiles.removeAt(index)
                                                }
                                            }
                                        ) {
                                            IconClose()
                                        }
                                    }
                                }
                            }
                        }
                        if (isRecording) {
                            Spacer(Modifier.width(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Recording...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    IconButton(
                                        onClick = {
                                            audioRecorder?.stop()
                                            audioRecorder = null
                                            isRecording = false
                                            recordedAudioFile = null
                                        }
                                    ) {
                                        IconClose()
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(
                    tonalElevation = 3.dp,
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        IconButton(onClick = { imagePickerLauncher.launch("image/*") }, enabled = !isGenerating) { IconAdd() }
                        IconButton(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    val file = File(context.cacheDir, "recording_${System.currentTimeMillis()}.wav")
                                    recordedAudioFile = file
                                    try {
                                        val recorder = WavRecorder(context, file, scope)
                                        recorder.start()
                                        audioRecorder = recorder
                                        isRecording = true
                                        globalError = null
                                    } catch (e: Exception) {
                                        globalError = "Failed to start mic: ${e.localizedMessage}"
                                    }
                                } else recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            enabled = !isGenerating
                        ) {
                            Icon(painterResource(android.R.drawable.ic_btn_speak_now), contentDescription = "Voice")
                        }

                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            enabled = !isGenerating && !isRecording,
                            placeholder = { Text("Message...") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )

                        if (isGenerating) {
                            IconButton(onClick = { activeJob?.cancel(); activeJob = null }) { IconStop(MaterialTheme.colorScheme.error) }
                        } else {
                            val canSend = (inputText.isNotBlank() || selectedImageFiles.isNotEmpty() || recordedAudioFile != null)
                            IconButton(
                                enabled = canSend,
                                onClick = {
                                    if (isRecording) {
                                        audioRecorder?.stop()
                                        audioRecorder = null
                                        isRecording = false
                                    }
                                    sendMessageManual(
                                        scope, conversation, assistantTools, messages,
                                        inputText, selectedImageFiles.toList(), selectedImageUris.toList(), recordedAudioFile,
                                        onComplete = {
                                            inputText = ""; selectedImageFiles.clear(); selectedImageUris.clear(); recordedAudioFile = null
                                        },
                                        onJobFinished = { activeJob = null },
                                        onJobStarted = { activeJob = it }
                                    )
                                }
                            ) {
                                Icon(painterResource(android.R.drawable.ic_menu_send), contentDescription = "Send", tint = if (canSend) MaterialTheme.colorScheme.primary else Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isInitializing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (globalError != null) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(globalError!!, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                }
                items(messages) { msg -> ChatBubble(msg) }
            }
        }
    }
}

/**
 * Custom WavRecorder using AudioRecord to produce 16kHz PCM WAV files
 * needed for LiteRT-LM miniaudio decoder stability.
 */
class WavRecorder(val context: Context, val outputFile: File, val scope: CoroutineScope) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    fun start() {
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) throw Exception("AudioRecord init failed")

        isRecording = true
        audioRecord?.startRecording()

        scope.launch(Dispatchers.IO) {
            val tempRaw = File(context.cacheDir, "temp_${System.currentTimeMillis()}.raw")
            FileOutputStream(tempRaw).use { fos ->
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) fos.write(buffer, 0, read)
                }
            }
            writeWavFile(tempRaw, outputFile)
            tempRaw.delete()
        }
    }

    fun stop() {
        isRecording = false
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                try { stop() } catch(e: Exception) {}
            }
            release()
        }
        audioRecord = null
    }

    private fun writeWavFile(rawFile: File, wavFile: File) {
        val rawData = rawFile.readBytes()
        val totalAudioLen = rawData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (16 * sampleRate * 1 / 8).toLong()

        FileOutputStream(wavFile).use { out ->
            val header = ByteArray(44)
            header[0] = 'R'.toByte(); header[1] = 'I'.toByte(); header[2] = 'F'.toByte(); header[3] = 'F'.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.toByte(); header[9] = 'A'.toByte(); header[10] = 'V'.toByte(); header[11] = 'E'.toByte()
            header[12] = 'f'.toByte(); header[13] = 'm'.toByte(); header[14] = 't'.toByte(); header[15] = ' '.toByte()
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // fmt chunk size
            header[20] = 1; header[21] = 0 // PCM = 1
            header[22] = 1; header[23] = 0 // Mono
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = 2; header[33] = 0 // block align
            header[34] = 16; header[35] = 0 // bits per sample
            header[36] = 'd'.toByte(); header[37] = 'a'.toByte(); header[38] = 't'.toByte(); header[39] = 'a'.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
            header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
            header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
            out.write(header)
            out.write(rawData)
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        if (message.isUser) {
            // User message remains in a primary colored bubble
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Column(modifier = Modifier.padding(if (message.imageUris.isNotEmpty() || message.hasAudio) 4.dp else 12.dp)) {
                    if (message.imageUris.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            message.imageUris.forEach { uri ->
                                AsyncImage(
                                    model = uri,
                                    contentDescription = "User attached image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .clip(RoundedCornerShape(16.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    if (message.hasAudio) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(android.R.drawable.ic_btn_speak_now), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Voice Message", color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp)
                        }
                    }

                    if (message.text.isNotBlank()) {
                        Text(
                            text = message.text,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(
                                horizontal = if (message.imageUris.isNotEmpty() || message.hasAudio) 8.dp else 0.dp,
                                vertical = if (message.imageUris.isNotEmpty() || message.hasAudio) 8.dp else 0.dp
                            )
                        )
                    }
                }
            }
        } else {
            // AI output is plain text without a bubble surface
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // In case AI ever sends images/audio (currently handled for consistency)
                if (message.imageUris.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        message.imageUris.forEach { uri ->
                            AsyncImage(
                                model = uri,
                                contentDescription = "AI attached image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    )
                }
            }
        }
    }
}

private fun sendMessageManual(
    scope: kotlinx.coroutines.CoroutineScope,
    conversation: Conversation?,
    assistantTools: AssistantToolSet,
    messages: MutableList<ChatMessage>,
    text: String,
    imageFiles: List<File>,
    imageUris: List<Uri>,
    audioFile: File?,
    onComplete: () -> Unit,
    onJobFinished: () -> Unit,
    onJobStarted: (kotlinx.coroutines.Job) -> Unit
) {
    messages.add(ChatMessage(
        text = text,
        isUser = true,
        imageUris = imageUris,
        hasAudio = audioFile != null
    ))

    val aiMessageIndex = messages.size
    messages.add(ChatMessage("", false))

    val job = scope.launch {
        val initialParts = mutableListOf<Content>()
        imageFiles.forEach { initialParts.add(Content.ImageFile(it.absolutePath)) }
        audioFile?.let { initialParts.add(Content.AudioFile(it.absolutePath)) }
        if (text.isNotBlank()) initialParts.add(Content.Text(text))

        var nextInput: Any = Contents.of(initialParts)
        var isLooping = true

        while (isLooping) {
            var fullResponseText = ""
            var displayedText = ""
            var tagBuffer = ""
            var insideTag = false
            var insideFunctionBlock = false

            val stream = if (nextInput is Contents) conversation?.sendMessageAsync(nextInput)
            else conversation?.sendMessageAsync(nextInput as Message)

            if (stream == null) break

            stream.catch { e ->
                messages[aiMessageIndex] = ChatMessage("Error: ${e.message}", false)
                isLooping = false
            }.collect { chunk ->
                val chunkText = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                fullResponseText += chunkText

                for (char in chunkText) {
                    if (char == '<') { insideTag = true; tagBuffer = "<" }
                    else if (insideTag) {
                        tagBuffer += char
                        if (char == '>') {
                            insideTag = false
                            if (tagBuffer.contains("start_function_call")) insideFunctionBlock = true
                            else if (tagBuffer.contains("end_function_call")) insideFunctionBlock = false
                            tagBuffer = ""
                        }
                    } else if (!insideFunctionBlock) {
                        displayedText += char
                    }
                }
                if (displayedText.isNotBlank()) messages[aiMessageIndex] = ChatMessage(displayedText, false)
            }

            val toolRegex = Pattern.compile("<start_function_call>(.*?)<end_function_call>", Pattern.DOTALL)
            val matcher = toolRegex.matcher(fullResponseText)

            if (matcher.find()) {
                val callBody = matcher.group(1)?.trim() ?: ""
                val toolResult = processManualCallBody(callBody, assistantTools)
                withContext(Dispatchers.Main) { messages[aiMessageIndex] = ChatMessage("Thinking...", false) }
                nextInput = Message.tool(Contents.of(listOf(Content.ToolResponse("manual_action", toolResult))))
            } else {
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
                    argsMap[parts[0].trim()] = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                }
            }
        }
        when (name) {
            "get_local_current_date_time" -> tools.getLocalCurrentDateTime()
            "get_list_of_apps" -> tools.getListOfApps()
            "open_app" -> tools.openApp(argsMap["packageId"] ?: "")
            "send_message" -> tools.sendMessage(argsMap["recipient"] ?: "", argsMap["message"] ?: "")
            "make_phone_call" -> tools.makePhoneCall(argsMap["recipient"] ?: "")
            "get_weather" -> tools.getWeather(argsMap["latitude"]?.toDoubleOrNull() ?: 0.0, argsMap["longitude"]?.toDoubleOrNull() ?: 0.0)
            else -> "Error: Unknown tool $name"
        }
    } catch (e: Exception) { "Error: ${e.message}" }
}

private fun copyUriToFile(context: Context, uri: Uri): File {
    val tempFile = File(context.cacheDir, "temp_image_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
    }
    return tempFile
}