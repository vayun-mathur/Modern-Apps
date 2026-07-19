package com.vayunmathur.openassistant.util

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.ConversationDao
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.data.MemoryDao
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.data.MessageDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Clock

/**
 * ViewModel for the OpenAssistant app.
 *
 * Owns:
 *  - filtered chat-message stream per conversation (via [MessageDao])
 *  - all-conversations and all-memories flows backed by their DAOs
 *  - audio recorder + recording-state lifecycle (mic permission still lives in
 *    composables since it uses [androidx.activity.compose.rememberLauncherForActivityResult])
 *  - inference-service lifecycle: pre-warm on init and dispatch per-message
 *    inference requests via [requestInference]
 *  - one-time on-disk migration of the legacy gemma4 model file
 *
 * DAOs are injected directly so this VM owns the persistence layer for the app.
 */
class AssistantViewModel(
    application: Application,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val memoryDao: MemoryDao,
) : AndroidViewModel(application) {

    private val _isRecording = MutableStateFlow(false)
    /** True while the [WavRecorder] is actively capturing microphone audio. */
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedAudioPath = MutableStateFlow<String?>(null)
    /** Absolute path of the most recent recording, or null if none is pending send. */
    val recordedAudioPath: StateFlow<String?> = _recordedAudioPath.asStateFlow()

    private var audioRecorder: WavRecorder? = null

    /** All conversations, newest first. */
    val conversations: StateFlow<List<Conversation>> = conversationDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All memories. */
    val memories: StateFlow<List<Memory>> = memoryDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        cleanupLegacyModelFile()
        cleanupStaleSiglipModels()
        // Pre-warm the inference engine so the first user prompt is responsive.
        val context = getApplication<Application>()
        context.startService(Intent(context, InferenceService::class.java))
    }

    /**
     * Returns a flow of messages belonging to [conversationId], sorted by
     * timestamp.
     */
    fun messagesFor(conversationId: Long): Flow<List<Message>> =
        messageDao.getByConversationFlow(conversationId)

    /**
     * Composable State that tracks the [Conversation] with [id], or null if it
     * doesn't exist (e.g. a fresh conversation that hasn't been persisted yet).
     */
    @Composable
    fun conversationByIdState(id: Long): State<Conversation?> {
        val flow = remember(id) { conversationDao.getByIdFlow(id) }
        return flow.collectAsState(initial = null)
    }

    // ---------- Mutations ----------

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch(Dispatchers.IO) { conversationDao.delete(conversation) }
    }

    /** Suspending upsert returning the persisted row id. */
    suspend fun upsertConversation(conversation: Conversation): Long =
        kotlinx.coroutines.withContext(Dispatchers.IO) { conversationDao.upsert(conversation) }

    /** Suspending upsert returning the persisted row id. */
    suspend fun upsertMessage(message: Message): Long =
        kotlinx.coroutines.withContext(Dispatchers.IO) { messageDao.upsert(message) }

    fun deleteMemory(memory: Memory) {
        viewModelScope.launch(Dispatchers.IO) { memoryDao.delete(memory) }
    }

    /**
     * Starts a new microphone capture into the app cache. Caller is expected
     * to have already obtained [android.Manifest.permission.RECORD_AUDIO];
     * if recording fails to initialize, [isRecording] stays false.
     */
    fun startRecording() {
        if (_isRecording.value) return
        val context = getApplication<Application>()
        val file = File(context.cacheDir, "recording_${Clock.System.now().toEpochMilliseconds()}.wav")
        try {
            audioRecorder = WavRecorder(context, file, viewModelScope).apply { start() }
            _recordedAudioPath.value = file.absolutePath
            _isRecording.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            audioRecorder = null
            _recordedAudioPath.value = null
            _isRecording.value = false
        }
    }

    /** Stops the current recording (if any) but keeps [recordedAudioPath] for send. */
    fun stopRecording() {
        audioRecorder?.stop()
        audioRecorder = null
        _isRecording.value = false
    }

    /** Stops recording and discards the pending audio file path. */
    fun cancelRecording() {
        stopRecording()
        _recordedAudioPath.value = null
    }

    /** Clears the recorded-audio reference without stopping a live recording. */
    fun consumeRecordedAudio() {
        _recordedAudioPath.value = null
    }

    /**
     * Dispatches a standard inference request to [InferenceService] for the
     * given conversation. The service owns its own queue, history fetch, and
     * streaming response writes back to the database.
     */
    fun requestInference(
        conversationId: Long,
        userText: String,
        imagePaths: List<String>,
        audioPath: String?,
    ) {
        val context = getApplication<Application>()
        context.startService(Intent(context, InferenceService::class.java).apply {
            putExtra("conversation_id", conversationId)
            putExtra("user_text", userText)
            putExtra("image_paths", imagePaths.toTypedArray())
            putExtra("audio_path", audioPath)
        })
    }

    private fun cleanupLegacyModelFile() {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val externalDir = context.getExternalFilesDir(null) ?: return@launch
                // Every model file we no longer use. Add to this list when the
                // active model changes (e.g. gemma4-4b → gemma4-2b) so users
                // don't keep stale gigabytes on disk. Re-download of the new file
                // happens automatically: InitialDownloadChecker gates on file
                // presence, so an absent (renamed) active model shows the download
                // screen on next launch.
                val legacyModelFiles = listOf("gemma4.litertlm", "gemma4-4b.litertlm")
                for (name in legacyModelFiles) {
                    if (File(externalDir, name).delete()) {
                        Log.i(TAG, "Deleted legacy model file $name")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up legacy model file", e)
            }
        }
    }

    override fun onCleared() {
        audioRecorder?.stop()
        audioRecorder = null
        super.onCleared()
    }

    /**
     * Version the on-demand SigLIP2 model files: if [SiglipEmbedder.MODEL_VERSION]
     * changed since we last downloaded them, delete the stale files and clear the
     * downloadservice `dlid_`/`progress_` keys so they re-download on the next
     * embedding request. Unlike the gemma path this never touches
     * `dbSetupComplete` — SigLIP2 is optional and fetched on demand.
     */
    private fun cleanupStaleSiglipModels() {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ds = DataStoreUtils.getInstance(context)
                val stored = ds.getLong("siglip_model_version") ?: 0L
                if (stored == SiglipEmbedder.MODEL_VERSION.toLong()) return@launch

                val externalDir = context.getExternalFilesDir(null)
                val files = listOf(
                    SiglipEmbedder.VISION_FILE,
                    SiglipEmbedder.TEXT_FILE,
                    SiglipEmbedder.TOKENIZER_FILE,
                )
                for (name in files) {
                    externalDir?.let { File(it, name).takeIf { f -> f.exists() }?.delete() }
                    ds.setLong("dlid_$name", 0L)
                    ds.setDouble("progress_$name", 0.0)
                }
                ds.setLong("siglip_model_version", SiglipEmbedder.MODEL_VERSION.toLong())
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up stale SigLIP2 model files", e)
            }
        }
    }

    companion object {
        private const val TAG = "AssistantViewModel"
    }
}
