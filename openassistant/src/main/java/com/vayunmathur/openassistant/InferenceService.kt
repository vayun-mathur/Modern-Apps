package com.vayunmathur.openassistant

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.google.ai.edge.litertlm.*
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import java.io.File
import java.util.regex.Pattern
import kotlin.time.Clock

class InferenceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Keeping the engine and conversation warm in memory
    private var engine: Engine? = null
    private var currentConversation: com.google.ai.edge.litertlm.Conversation? = null
    private var currentConversationId: Long = -1L

    // Simple lock to prevent concurrent generations from crashing the NPU/GPU
    private var isGenerating = false

    val db = buildDatabase<AppDatabase>()
    val viewModel = DatabaseViewModel(db, Conversation::class to db.conversationDao(), Message::class to db.messageDao())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val conversationId = intent?.getLongExtra("conversation_id", -1L) ?: -1L
        val userText = intent?.getStringExtra("user_text") ?: ""
        val imagePaths = intent?.getStringArrayExtra("image_paths") ?: emptyArray()
        val audioPath = intent?.getStringExtra("audio_path")
        
        if (conversationId != -1L) {
            processInference(conversationId, userText, imagePaths, audioPath)
        }

        // START_STICKY ensures the service stays alive to keep the model in RAM
        return START_STICKY
    }

    private fun processInference(
        conversationId: Long, 
        userText: String, 
        imagePaths: Array<String>, 
        audioPath: String?
    ) {
        if (isGenerating) return 
        isGenerating = true

        serviceScope.launch {
            try {
                ensureEngineInitialized()
                
                // If switching conversations, we need to create a new conversation object
                if (currentConversationId != conversationId || currentConversation == null) {
                    val history = fetchHistoryFromDb(conversationId)
                        .filter { it.text != userText || it.timestamp < Clock.System.now().toEpochMilliseconds() - 1000 }
                    setupConversation(conversationId, history)
                }

                runInferenceLoop(conversationId, userText, imagePaths, audioPath)
            } catch (e: Exception) {
                // In a real app, update the UI state in DB for the error
                val errId = upsertMessageToDb(Message(
                    conversationId = conversationId,
                    text = "Error: ${e.localizedMessage}",
                    role = "assistant",
                    timestamp = Clock.System.now().toEpochMilliseconds()
                ))
            } finally {
                isGenerating = false
            }
        }
    }

    private suspend fun ensureEngineInitialized() {
        if (engine != null) return

        val modelFile = File(applicationContext.getExternalFilesDir(null)!!, "model.litertlm")
        if (!modelFile.exists()) throw Exception("Model file missing at ${modelFile.absolutePath}")

        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.GPU(),
            visionBackend = Backend.GPU(),
            audioBackend = Backend.CPU(), // CPU preferred for audio stability on hardened kernels
            cacheDir = applicationContext.cacheDir.absolutePath
        )
        
        val newEngine = Engine(config)
        withContext(Dispatchers.IO) {
            newEngine.initialize()
        }
        engine = newEngine
    }

    private fun setupConversation(id: Long, history: List<Message>) {
        val systemPrompt = """
            You are a helpful Android assistant. Use tool calls:
            <start_function_call>tool_name(arg1="val1")<end_function_call>
            Interpret tool results conversationally.
            
            MANDATORY: You must identify the conversation topic based on the user's first input and provide a short summary title (3-5 words). 
            Your very first response in this conversation MUST start exactly with <title_start>Your Summary Title<title_end>.
        """.trimIndent()

        val initialMessages = history.map { msg ->
            when (msg.role) {
                "user" -> com.google.ai.edge.litertlm.Message.user(Contents.of(msg.text))
                "assistant" -> com.google.ai.edge.litertlm.Message.model(Contents.of(msg.text))
                else -> com.google.ai.edge.litertlm.Message.user(Contents.of(msg.text))
            }
        }

        currentConversation = engine?.createConversation(ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            initialMessages = initialMessages,
            tools = listOf(tool(AssistantToolSet(applicationContext))),
            automaticToolCalling = false
        ))
        currentConversationId = id
    }

    private suspend fun runInferenceLoop(
        conversationId: Long, 
        userText: String, 
        imagePaths: Array<String>, 
        audioPath: String?
    ) {
        val conv = currentConversation ?: return
        
        // Create the AI message record placeholder
        val aiMsgId = upsertMessageToDb(Message(
            conversationId = conversationId,
            text = "...",
            role = "assistant",
            timestamp = Clock.System.now().toEpochMilliseconds()
        ))

        var isLooping = true
        var nextInput: com.google.ai.edge.litertlm.Message? = null

        while (isLooping) {
            var fullResponseText = ""
            var displayedText = ""
            var tagBuffer = ""
            var insideTag = false
            var insideFunctionBlock = false
            var insideTitleBlock = false
            var titleBuffer = ""

            // On the first loop iteration, build multimodal content
            val stream = if (nextInput == null) {
                val contents = mutableListOf<Content>()
                
                // Add images
                imagePaths.forEach { path ->
                    contents.add(Content.ImageFile(path))
                }
                
                // Add audio
                audioPath?.let { path ->
                    if (File(path).exists()) {
                        contents.add(Content.AudioFile(path))
                    }
                }
                
                // Add text last
                if (userText.isNotBlank()) {
                    contents.add(Content.Text(userText))
                }

                conv.sendMessageAsync(com.google.ai.edge.litertlm.Message.user(Contents.of(contents)))
            } else {
                conv.sendMessageAsync(nextInput)
            }

            stream.catch { e ->
                updateMessageInDb(aiMsgId, "Error: ${e.message}")
                isLooping = false
            }.collect { chunk ->
                val chunkText = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                fullResponseText += chunkText

                for (char in chunkText) {
                    if (char == '<') { 
                        insideTag = true
                        tagBuffer = "<" 
                    } else if (insideTag) {
                        tagBuffer += char
                        if (char == '>') {
                            insideTag = false
                            when {
                                tagBuffer.contains("start_function_call") -> insideFunctionBlock = true
                                tagBuffer.contains("end_function_call") -> insideFunctionBlock = false
                                tagBuffer.contains("title_start") -> insideTitleBlock = true
                                tagBuffer.contains("title_end") -> {
                                    insideTitleBlock = false
                                    updateTitleInDb(conversationId, titleBuffer.trim())
                                }
                            }
                            tagBuffer = ""
                        }
                    } else {
                        if (insideTitleBlock) {
                            titleBuffer += char
                        } else if (!insideFunctionBlock) {
                            displayedText += char
                        }
                    }
                }
                
                if (displayedText.isNotBlank()) {
                    updateMessageInDb(aiMsgId, displayedText)
                }
            }

            // Check for tool calls to continue the loop
            val toolRegex = Pattern.compile("<start_function_call>(.*?)<end_function_call>", Pattern.DOTALL)
            val matcher = toolRegex.matcher(fullResponseText)

            if (matcher.find()) {
                val callBody = matcher.group(1)?.trim() ?: ""
                val toolResult = processManualCallBody(callBody)
                updateMessageInDb(aiMsgId, "Thinking...")
                nextInput = com.google.ai.edge.litertlm.Message.tool(Contents.of(listOf(Content.ToolResponse("manual_action", toolResult))))
            } else {
                isLooping = false
            }
        }
    }

    private fun processManualCallBody(body: String): String {
        return try {
            val tools = AssistantToolSet(applicationContext)
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

    // Database interaction placeholders - implementation should be provided by your DB layer
    private suspend fun fetchHistoryFromDb(id: Long): List<Message> = viewModel.getAll<Message>().filter { it.conversationId == id }
    private suspend fun upsertMessageToDb(msg: Message): Long = viewModel.getDaoInterface<Message>().dao.upsert(msg)
    private suspend fun updateMessageInDb(id: Long, text: String) {
        val newMsg = viewModel.getAll<Message>().find { it.id == id }!!.copy(text = text)
        upsertMessageToDb(newMsg)
    }
    private suspend fun updateTitleInDb(id: Long, title: String) {
        val newMsg = viewModel.getAll<Conversation>().find { it.id == id }!!.copy(title = title)
        viewModel.getDaoInterface<Conversation>().dao.upsert(newMsg)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        engine?.close()
        super.onDestroy()
    }
}