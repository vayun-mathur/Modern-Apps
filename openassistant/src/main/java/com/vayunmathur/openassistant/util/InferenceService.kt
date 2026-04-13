package com.vayunmathur.openassistant.util
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.google.ai.edge.litertlm.*
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.IntentLauncher
import com.vayunmathur.library.util.buildDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import java.io.File
import java.util.regex.Pattern
import kotlin.time.Clock
import com.vayunmathur.openassistant.data.AppDatabase
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.Message

class InferenceService : Service() {

    companion object {
        var newTitle: String? = null
    }

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

        val modelFile = File(applicationContext.getExternalFilesDir(null)!!, "gemma4.litertlm")
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
            You are a helpful Android assistant.
            On the first request the user sends to you, you MUST define a title for the conversation. You may optionally change the title if the topic of conversation changes sufficiently
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
            automaticToolCalling = true,
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

        var fullResponseText = ""
        var displayedText = ""

        // On the first loop iteration, build multimodal content
        val stream = run {
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
        }

        stream.catch { e ->
            updateMessageInDb(aiMsgId, "Error: ${e.message}")
        }.collect { chunk ->
            val chunkText = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            fullResponseText += chunkText
            displayedText += chunkText

            if(newTitle != null) {
                updateTitleInDb(conversationId, newTitle!!)
                newTitle = null
            }

            if (displayedText.isNotBlank()) {
                updateMessageInDb(aiMsgId, displayedText)
            }
        }
    }

    // Database interaction placeholders - implementation should be provided by your DB layer
    private suspend fun fetchHistoryFromDb(id: Long): List<Message> = viewModel.getAll<Message>().filter { it.conversationId == id }
    private suspend fun upsertMessageToDb(msg: Message): Long = viewModel.upsert(msg)
    private suspend fun updateMessageInDb(id: Long, text: String) {
        val newMsg = viewModel.get<Message>(id).copy(text = text)
        upsertMessageToDb(newMsg)
    }
    private suspend fun updateTitleInDb(id: Long, title: String) {
        val newMsg = viewModel.get<Conversation>(id).copy(title = title)
        viewModel.upsert(newMsg)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        engine?.close()
        super.onDestroy()
    }
}