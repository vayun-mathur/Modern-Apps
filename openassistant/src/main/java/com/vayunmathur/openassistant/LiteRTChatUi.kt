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
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiteRTChatUi(backStack: NavBackStack<Route>, conversationId: Long, viewModel: DatabaseViewModel) {
    val activeConversation by viewModel.getNullable<Conversation>(conversationId)
    val allMessages by viewModel.data<Message>().collectAsState(initial = emptyList())

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }

    val filteredMessages = remember(allMessages, conversationId) {
        allMessages.filter { it.conversationId == conversationId }.sortedBy { it.timestamp }
    }

    // State for multiple images
    val selectedImageUris = remember { mutableStateListOf<Uri>() }
    val selectedImageFiles = remember { mutableStateListOf<File>() }

    var isRecording by remember { mutableStateOf(false) }
    var audioRecorder by remember { mutableStateOf<WavRecorder?>(null) }
    var recordedAudioFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.isNotEmpty()) {
            listState.animateScrollToItem(filteredMessages.size - 1)
        }
    }

    val recordAudioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            val file = File(context.cacheDir, "recording_${Clock.System.now().toEpochMilliseconds()}.wav")
            recordedAudioFile = file
            try {
                val recorder = WavRecorder(context, file, scope)
                recorder.start()
                audioRecorder = recorder
                isRecording = true
            } catch (e: Exception) {
                Toast.makeText(context, "Mic error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        uris.forEach { uri ->
            selectedImageUris.add(uri)
            scope.launch(Dispatchers.IO) {
                val file = copyUriToFile(context, uri)
                withContext(Dispatchers.Main) { selectedImageFiles.add(file) }
            }
        }
    }

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val customNavSuiteType = with(adaptiveInfo) {
        if (windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)) {
            NavigationSuiteType.WideNavigationRailExpanded
        } else {
            NavigationSuiteType.None
        }
    }
    val allConversations by viewModel.data<Conversation>().collectAsState(initial = emptyList())

    val coroutineScope = rememberCoroutineScope()

    NavigationSuiteScaffold(layoutType = customNavSuiteType, navigationSuiteItems = {
        allConversations.forEach {
            item(it.id == conversationId, {
                backStack.reset(Route.ConversationPage(it.id))
            }, {}, label = {Text(it.title)})
        }
    }) {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        ModalNavigationDrawer({
            ModalDrawerSheet {
                allConversations.forEach {
                    NavigationDrawerItem(
                        { Text(it.title) },
                        selected = it.id == conversationId,
                        onClick = {
                            backStack.reset(Route.ConversationPage(it.id))
                        })
                }
            }
        }, drawerState = drawerState) {
            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                activeConversation?.title ?: "New Conversation",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        actions = {
                            if(conversationId != 0L) {
                                IconButton({ backStack.reset(Route.ConversationPage(0)) }) {
                                    IconAdd()
                                }
                            }
                        },
                        navigationIcon = {
                            if(customNavSuiteType == NavigationSuiteType.None) {
                                IconButton({ coroutineScope.launch {
                                    drawerState.open()
                                }}) {
                                    IconMenu()
                                }
                            }
                        }
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
                                                    contentDescription = null,
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
                                                            if (index < selectedImageFiles.size) selectedImageFiles.removeAt(
                                                                index
                                                            )
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
                                        Row(
                                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "Recording...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                IconButton(onClick = { imagePickerLauncher.launch("image/*") }) { IconAdd() }
                                IconButton(
                                    onClick = {
                                        if (ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                        ) {
                                            val file = File(
                                                context.cacheDir,
                                                "recording_${
                                                    Clock.System.now().toEpochMilliseconds()
                                                }.wav"
                                            )
                                            recordedAudioFile = file
                                            try {
                                                val recorder = WavRecorder(context, file, scope)
                                                recorder.start()
                                                audioRecorder = recorder
                                                isRecording = true
                                            } catch (e: Exception) {
                                            }
                                        } else recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                ) {
                                    Icon(
                                        painterResource(android.R.drawable.ic_btn_speak_now),
                                        contentDescription = "Voice"
                                    )
                                }

                                TextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("Message...") },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    )
                                )

                                val canSend =
                                    (inputText.isNotBlank() || selectedImageFiles.isNotEmpty() || recordedAudioFile != null)
                                IconButton(
                                    enabled = canSend,
                                    onClick = {
                                        if (isRecording) {
                                            audioRecorder?.stop()
                                            audioRecorder = null
                                            isRecording = false
                                        }

                                        scope.launch {
                                            var currentId = conversationId
                                            if (currentId == 0L) {
                                                val newConv = Conversation(title = "New Conversation")
                                                currentId = viewModel.upsert(newConv)
                                                backStack.reset(Route.ConversationPage(currentId))
                                            }

                                            // 1. Save User Message to Database
                                            val userMsg = Message(
                                                conversationId = currentId,
                                                text = inputText,
                                                role = "user",
                                                imagePaths = selectedImageFiles.map { it.absolutePath },
                                                hasAudio = recordedAudioFile != null,
                                                timestamp = Clock.System.now().toEpochMilliseconds()
                                            )
                                            viewModel.upsert(userMsg)

                                            // 2. Start Service with the message payload
                                            val intent =
                                                Intent(context, InferenceService::class.java).apply {
                                                    putExtra("conversation_id", currentId)
                                                    putExtra("user_text", inputText)
                                                    putExtra(
                                                        "image_paths",
                                                        selectedImageFiles.map { it.absolutePath }
                                                            .toTypedArray()
                                                    )
                                                    putExtra(
                                                        "audio_path",
                                                        recordedAudioFile?.absolutePath
                                                    )
                                                }
                                            context.startService(intent)

                                            // 3. Clear UI State
                                            inputText = ""
                                            selectedImageFiles.clear()
                                            selectedImageUris.clear()
                                            recordedAudioFile = null
                                        }
                                    }
                                ) {
                                    Icon(
                                        painterResource(android.R.drawable.ic_menu_send),
                                        contentDescription = "Send",
                                        tint = if (canSend) MaterialTheme.colorScheme.primary else Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(filteredMessages, key = { it.id }) { msg -> ChatBubble(msg) }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: Message) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start
    ) {
        if (message.role == "user") {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Column(modifier = Modifier.padding(if (message.imagePaths.isNotEmpty() || message.hasAudio) 4.dp else 12.dp)) {
                    message.imagePaths.forEach { path ->
                        AsyncImage(
                            model = path,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop
                        )
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
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(vertical = 4.dp).padding(end = 100.dp),
                horizontalAlignment = Alignment.Start
            ) {
                if (message.text.isNotBlank()) {
                    MarkdownText(
                        message.text,
                        style = LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    )
                }
            }
        }
    }
}

/**
 * Helper to record audio and write to a WAV file
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
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return

        isRecording = true
        audioRecord?.startRecording()

        scope.launch(Dispatchers.IO) {
            val tempRaw = File(context.cacheDir, "temp_${Clock.System.now().toEpochMilliseconds()}.raw")
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
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
            header[20] = 1; header[21] = 0 // PCM
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

private fun copyUriToFile(context: Context, uri: Uri): File {
    val tempFile = File(context.cacheDir, "img_${Clock.System.now().toEpochMilliseconds()}_${UUID.randomUUID()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
    }
    return tempFile
}