package com.vayunmathur.openassistant.util

import android.net.Uri
import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import com.vayunmathur.openassistant.data.Memory
import com.vayunmathur.openassistant.data.Message

/**
 * The UI contract between [AssistantViewModel] plus the nav back stack and the screens.
 *
 * Screens take a state value and an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the binders in `ui` implement these interfaces.
 */

/** Everything the chat screen draws. */
data class ChatUiState(
    /** Null until the conversation is persisted; the screen then shows "New conversation". */
    val title: String? = null,
    val messages: List<Message> = emptyList(),
    /** A2UI surfaces by surface id (`m<messageId>`), drawn under their assistant message. */
    val surfaces: Map<String, A2uiCoreSurfaceModel> = emptyMap(),
    val inputText: String = "",
    /** Images picked for the next message, not yet sent. */
    val attachments: List<Uri> = emptyList(),
    val isRecording: Boolean = false,
    /** The drawer button is hidden until there is a conversation to switch to. */
    val showConversationsButton: Boolean = false,
    val showNewChatButton: Boolean = false,
)

/**
 * Chat callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface ChatActions {
    fun setInputText(text: String) {}
    fun openConversations() {}
    fun openSettings() {}
    fun newConversation() {}
    fun addImage() {}
    fun removeImage(uri: Uri) {}
    fun record() {}

    /** Discards the pending recording and any picked images. */
    fun cancelMedia() {}
    fun send() {}

    companion object {
        val Noop: ChatActions = object : ChatActions {}
    }
}

/** Everything the settings screen draws. */
data class SettingsUiState(
    val memories: List<Memory> = emptyList(),
    /** The chat system prompt; editable, defaults to the built-in prompt. */
    val systemPrompt: String = "",
)

/** Settings callbacks. Same no-op-default arrangement as [ChatActions]. */
interface SettingsActions {
    fun back() {}
    fun deleteMemory(memory: Memory) {}
    fun setSystemPrompt(prompt: String) {}

    /** Restore the system prompt to the built-in default. */
    fun resetSystemPrompt() {}

    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}
