package com.vayunmathur.email.platform

import android.content.Context
import android.net.Uri
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailPreview
import java.io.File

/**
 * The UI contract between [com.vayunmathur.email.EmailViewModel] and the two screens the
 * store listing images are rendered from — the message list and a conversation.
 *
 * Those screens take a state value plus an actions interface rather than the ViewModel
 * itself, so they can be rendered by a `@Preview` — see `src/screenshotTest`. It lives in
 * `util` rather than next to the composables so the dependency runs one way: the UI
 * depends on this, and the ViewModel implements these interfaces.
 *
 * The ViewModel publishes `Flow`s rather than snapshot state, so [MessageListUiState] is
 * assembled by the `…Page` binder — which is where the `collectAsStateWithLifecycle`
 * subscriptions have to live for recomposition to work — rather than by a getter.
 */

/** Everything the message list draws. */
data class MessageListUiState(
    val messages: List<EmailPreview> = emptyList(),
    /** null means the unified inbox, i.e. every account at once. */
    val selectedAccountEmail: String? = null,
    val selectedFolderName: String = "INBOX",
    val searchQuery: String = "",
    /** UIDs picked out by long-press, driving the bulk-action app bar. */
    val selectedUids: Set<Long> = emptySet(),
    val isSyncing: Boolean = false,
    val syncProgress: Float = 0f,
    val aiSummary: String? = null,
    val aiSummaryLoading: Boolean = false,
)

/**
 * Message-list callbacks. Every method has a no-op default so a preview can render the
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface MessageListActions {
    fun setSearchQuery(query: String) {}
    fun toggleMessageSelection(uid: Long) {}
    fun clearSelection() {}
    fun bulkMarkAsRead(accountEmail: String, uids: List<Long>, isRead: Boolean) {}
    fun markAsRead(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) {}
    fun deleteMessage(accountEmail: String, folderName: String, uid: Long) {}
    fun refresh(context: Context) {}
    fun requestAiSummary(messages: List<EmailPreview>) {}

    /** Provides a no-op MessageListActions for previews and defaults. */
    companion object {
        val Noop: MessageListActions = object : MessageListActions {}
    }
}

/** Conversation-view callbacks. Same no-op-default arrangement as [MessageListActions]. */
interface MessageThreadActions {
    fun markAsRead(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) {}
    fun snoozeMessage(accountEmail: String, folderName: String, uid: Long, until: Long) {}
    fun blockSender(from: String) {}

    /** Bodies are only headers until first open; this pulls the rest down. */
    fun fetchBodyIfNeeded(message: EmailMessage) {}

    suspend fun getAttachments(accountEmail: String, messageId: Long): List<Attachment> =
        emptyList()

    /** Inline `cid:` parts, extracted to cache so the HTML body can reference them. */
    suspend fun loadCidMap(context: Context, message: EmailMessage): Map<String, File> =
        emptyMap()

    fun downloadAttachment(
        attachment: Attachment,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {}

    fun oneClickUnsubscribe(url: String, onResult: (Boolean) -> Unit) {}

    fun exportEml(
        accountEmail: String,
        folderName: String,
        uid: Long,
        targetUri: Uri,
        onResult: (Boolean, String?) -> Unit,
    ) {}

    /** Provides a no-op MessageThreadActions for previews and defaults. */
    companion object {
        val Noop: MessageThreadActions = object : MessageThreadActions {}
    }
}
