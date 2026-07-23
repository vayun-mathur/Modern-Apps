package com.vayunmathur.messages.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.messages.data.MessagesDatabase
import kotlinx.coroutines.launch

/**
 * Thin Android-aware ViewModel that:
 *  1. Starts the foreground service on first composition (so the
 *     puppets begin loading the moment the user opens the app, not
 *     just when they enter a setup screen).
 *  2. Exposes a single suspending [send] for the UI to call.
 *
 * All actual state lives in [MessagesSessionManager] (singleton). This
 * ViewModel exists mostly so Compose has a viewModel() entry point per
 * Activity and to keep the auto-start logic out of MainActivity's setContent.
 */
class MessagesViewModel(application: Application) : AndroidViewModel(application) {

    init {
        // Idempotent: the session manager / service both no-op if already running.
        MessagesSessionManager.init(application)
        MessagesService.start(application)
    }

    val connectionStates = MessagesSessionManager.connectionStates

    fun database(): MessagesDatabase = MessagesSessionManager.database()

    fun send(conversationId: String, body: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = MessagesSessionManager.sendMessage(conversationId, body)
            onResult(ok)
        }
    }

    /**
     * Send an image (or other supported media) on [conversationId].
     * The UI reads the URI into a byte array on the IO dispatcher (so
     * we don't block the main thread on large attachments); the actual
     * upload + send happens in the session manager.
     */
    fun sendMedia(
        conversationId: String,
        uri: android.net.Uri,
        caption: String?,
        onResult: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val (bytes, mime, fileName) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val cr = getApplication<Application>().contentResolver
                val data = cr.openInputStream(uri)?.use { it.readBytes() }
                val type = cr.getType(uri) ?: "application/octet-stream"
                val name = uri.lastPathSegment ?: "attachment"
                Triple(data, type, name)
            }
            if (bytes == null) {
                onResult(false)
                return@launch
            }
            val ok = MessagesSessionManager.sendMedia(
                conversationId = conversationId,
                bytes = bytes,
                mime = mime,
                fileName = fileName,
                caption = caption,
            )
            onResult(ok)
        }
    }

    fun sendReaction(messageId: String, emoji: String, action: ReactionAction) {
        viewModelScope.launch {
            MessagesSessionManager.sendReaction(messageId, emoji, action)
        }
    }

    /** Create a poll on [conversationId] (routes to the platform client). */
    fun sendPoll(
        conversationId: String,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
        onResult: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            onResult(
                MessagesSessionManager.sendPoll(conversationId, question, options, allowMultiple)
            )
        }
    }

    fun sendPollVote(
        pollMessageId: String,
        optionNames: List<String>,
        onResult: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            onResult(MessagesSessionManager.sendPollVote(pollMessageId, optionNames))
        }
    }

    /**
     * Share a location on [conversationId]. [text] is the FindFamily share
     * URL minted at send time; sent as a normal message on every platform.
     */
    fun sendLocation(
        conversationId: String,
        text: String,
        onResult: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            onResult(MessagesSessionManager.sendLocation(conversationId, text))
        }
    }

    fun deleteConversation(conversationId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(MessagesSessionManager.deleteConversation(conversationId))
        }
    }

    /** Accept a message request (Signal/Messenger/Instagram). */
    fun acceptMessageRequest(conversationId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(MessagesSessionManager.acceptMessageRequest(conversationId))
        }
    }

    /** Block + drop a message-request conversation. */
    fun blockConversation(conversationId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onResult(MessagesSessionManager.blockConversation(conversationId))
        }
    }

    fun sendTyping(conversationId: String) {
        viewModelScope.launch {
            MessagesSessionManager.sendTyping(conversationId)
        }
    }

    /**
     * Search contacts across both backends + the device contact db.
     * The UI calls this on every keystroke; debounce is the caller's
     * concern (use Compose `LaunchedEffect(query) { delay(150); … }`).
     */
    suspend fun searchContacts(query: String): List<ContactSuggestion> =
        MessagesSessionManager.searchContacts(query)

    suspend fun searchDeviceContacts(query: String): List<ContactSuggestion> =
        MessagesSessionManager.searchDeviceContacts(query)

    /** Which sources already have an existing 1:1 thread with this number? */
    suspend fun resolveSourcesForNumber(phoneE164: String): Set<com.vayunmathur.messages.data.MessageSource> =
        MessagesSessionManager.resolveSourcesForNumber(phoneE164)

    /**
     * Read a content:// URI's bytes + mime + filename off the IO
     * dispatcher. Returns null if the URI is unreadable.
     *
     * Exposed here (vs. having callers do it inline) because the
     * Compose-side new-conversation screen needs to read multiple URIs
     * from a share intent before any single send is fired, and it's
     * cleaner to keep the contentResolver dance in one place.
     */
    suspend fun readUri(uri: android.net.Uri): NewMediaPart? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            val bytes = runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull() ?: return@withContext null
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "attachment"
            NewMediaPart(bytes = bytes, mime = mime, fileName = name)
        }

    private fun queryDisplayName(uri: android.net.Uri): String? = try {
        val cr = getApplication<Application>().contentResolver
        cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Create + send to a brand-new thread in one call. Returns the new
     * conversation id (prefixed) so the caller can navigate into it.
     */
    fun sendNewMessage(
        source: com.vayunmathur.messages.data.MessageSource,
        recipients: List<String>,
        body: String?,
        media: NewMediaPart? = null,
        onResult: (String?) -> Unit = {},
    ) {
        viewModelScope.launch {
            onResult(MessagesSessionManager.sendNewMessage(source, recipients, body, media))
        }
    }

    fun fetchMessages(conversationId: String) {
        MessagesSessionManager.fetchMessages(conversationId)
    }

    fun markRead(conversationId: String) {
        viewModelScope.launch {
            MessagesSessionManager.markConversationRead(conversationId)
        }
    }

    fun forceResync() {
        MessagesSessionManager.forceResync()
    }
}
