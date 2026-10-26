package com.vayunmathur.openassistant.util

import android.app.Application
import android.content.Intent
import com.vayunmathur.library.log.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.downloadservice.ModelUrls
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.data.OpenAssistantRepository
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
 *  - filtered chat-message stream per conversation (via [OpenAssistantRepository])
 *  - all-conversations and all-memories flows backed by the repository
 *  - audio recorder + recording-state lifecycle (mic permission still lives in
 *    composables since it uses [androidx.activity.compose.rememberLauncherForActivityResult])
 *  - inference-service lifecycle: pre-warm on init and dispatch per-message
 *    inference requests via [requestInference]
 *  - one-time on-disk migration of the legacy gemma4 model file
 */
class AssistantViewModel(
    application: Application,
    private val repository: OpenAssistantRepository,
) : AndroidViewModel(application) {

    private val _isRecording = MutableStateFlow(false)
    /** True while the [WavRecorder] is actively capturing microphone audio. */
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedAudioPath = MutableStateFlow<String?>(null)
    /** Absolute path of the most recent recording, or null if none is pending send. */
    val recordedAudioPath: StateFlow<String?> = _recordedAudioPath.asStateFlow()

    private var audioRecorder: WavRecorder? = null

    /**
     * Screen contents captured by the Assist API (see
     * [com.vayunmathur.openassistant.assist.OpenAssistantSession]) when the app was
     * invoked as the default digital assistant. Prepended to the next inference
     * request as context so the model can answer about what is on screen, then
     * cleared so it only seeds a single turn.
     */
    private var pendingScreenContext: String? = null

    /** Stores screen text captured by the Assist API for the next inference turn. */
    fun setScreenContext(text: String?) {
        pendingScreenContext = text?.takeIf { it.isNotBlank() }
    }

    /** All conversations, newest first. */
    val conversations: StateFlow<List<Conversation>> = repository.conversationsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All memories. */
    val memories: StateFlow<List<Memory>> = repository.memoriesFlow()
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
        repository.messagesForConversationFlow(conversationId)

    /**
     * Composable State that tracks the [Conversation] with [id], or null if it
     * doesn't exist (e.g. a fresh conversation that hasn't been persisted yet).
     */
    @Composable
    fun conversationByIdState(id: Long): State<Conversation?> {
        val flow = remember(id) { repository.conversationByIdFlow(id) }
        return flow.collectAsState(initial = null)
    }

    // ---------- Mutations ----------

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteConversation(conversation) }
    }

    /** Suspending upsert returning the persisted row id. */
    suspend fun upsertConversation(conversation: Conversation): Long =
        kotlinx.coroutines.withContext(Dispatchers.IO) { repository.upsertConversation(conversation) }

    /** Suspending upsert returning the persisted row id. */
    suspend fun upsertMessage(message: Message): Long =
        kotlinx.coroutines.withContext(Dispatchers.IO) { repository.upsertMessage(message) }

    fun deleteMemory(memory: Memory) {
        viewModelScope.launch(Dispatchers.IO) { repository.deleteMemory(memory) }
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
        } catch (expected: Exception) {
            Log.error(TAG, "Failed to start recording", expected)
            audioRecorder = null
            _recordedAudioPath.value = null
            _isRecording.value = false
        }
    }

    /** Stops the current recording (if any) but keeps [recordedAudioPath] for send. */
    fun stopRecording() {
        audioRecorder?.stop()
        _isRecording.value = false
    }

    /**
     * The recording's path once the file is actually on disk, or null if none arrived.
     *
     * [stopRecording] ends the capture but the WAV is written afterwards, by the coroutine
     * `WavRecorder.start` launched. Reading [recordedAudioPath] straight after stopping therefore
     * yields a path to a file that does not exist yet, and the attachment is dropped downstream
     * without a word - which is indistinguishable from the assistant not supporting audio.
     *
     * Send waits on this instead. Clearing the path when nothing arrived is deliberate: a stale
     * path is worse than none, because everything downstream treats it as an attachment the user
     * made and the model is told a clip could not be heard when there was no clip.
     */
    suspend fun awaitRecordedAudio(): String? {
        val recorder = audioRecorder ?: return _recordedAudioPath.value
        val written = recorder.finish()
        audioRecorder = null
        if (written == null) {
            Log.status(TAG, "recording produced no file: ${_recordedAudioPath.value}")
            _recordedAudioPath.value = null
        }
        return _recordedAudioPath.value
    }

    /** Stops recording and discards the pending audio file path. */
    fun cancelRecording() {
        stopRecording()
        audioRecorder = null
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
        val screenContext = pendingScreenContext
        pendingScreenContext = null
        val effectiveText = if (screenContext != null) {
            "Current screen contents:\n$screenContext\n\nUser request: $userText"
        } else {
            userText
        }
        val context = getApplication<Application>()
        context.startService(Intent(context, InferenceService::class.java).apply {
            putExtra("conversation_id", conversationId)
            putExtra("user_text", effectiveText)
            putExtra("image_paths", imagePaths.toTypedArray())
            putExtra("audio_path", audioPath)
        })
    }

    /**
     * Deletes the retired single-file Gemma models (and their `.part` leftovers) at the root of
     * the external files dir, but only once the maml set in `gemma4/` is complete, so an
     * upgrading user never ends up with neither. `gemma4/` itself is a directory and is skipped.
     */
    private fun cleanupLegacyModelFile() {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!ModelUrls.GemmaMaml.isDownloaded(context)) return@launch
                val externalDir = context.getExternalFilesDir(null) ?: return@launch
                val legacy = externalDir.listFiles { file -> file.isFile && file.name.startsWith("gemma4") }
                for (file in legacy.orEmpty()) {
                    if (file.delete()) Log.status(TAG, "Deleted legacy model file ${file.name}")
                }
            } catch (expected: Exception) {
                Log.error(TAG, "Error cleaning up legacy model file", expected)
            }
        }
    }

    override fun onCleared() {
        audioRecorder?.stop()
        audioRecorder = null
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
            } catch (expected: Exception) {
                Log.error(TAG, "Error cleaning up stale SigLIP2 model files", expected)
            }
        }
    }

    companion object {
        private const val TAG = "AssistantViewModel"
    }
}

/** Factory for constructing [AssistantViewModel] with the repository. */
class AssistantViewModelFactory(
    private val application: Application,
    private val repository: OpenAssistantRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AssistantViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return AssistantViewModel(application, repository) as T
    }
}
