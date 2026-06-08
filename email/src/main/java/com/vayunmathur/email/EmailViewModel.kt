package com.vayunmathur.email

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.text.HtmlCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailSyncState
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.data.OutboxManager
import com.vayunmathur.email.data.OutboxSendWorker
import com.vayunmathur.email.widget.EmailWidget
import com.vayunmathur.library.util.SecureResultReceiver
import com.vayunmathur.library.widgets.updateWidget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class EmailViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = EmailDatabase.getInstance(application).emailDao()
    private val emailManager = EmailManager()
    private val appContext = application.applicationContext
    
    val accounts = dao.getAccountsFlow()

    /** Active sync state — drives the linear progress bar at the top of the inbox. */
    val isSyncing: StateFlow<Boolean> = EmailSyncState.isSyncing
    val syncProgress: StateFlow<Float> = EmailSyncState.progress

    val outbox: Flow<List<OutboxEntry>> = dao.getOutboxFlow()

    private val _aiSummary = MutableStateFlow<String?>(null)
    val aiSummary: StateFlow<String?> = _aiSummary

    private val _aiSummaryLoading = MutableStateFlow(false)
    val aiSummaryLoading: StateFlow<Boolean> = _aiSummaryLoading
    
    private val _selectedAccountEmail = MutableStateFlow<String?>(null)
    val selectedAccountEmail: StateFlow<String?> = _selectedAccountEmail

    val selectedAccount = _selectedAccountEmail.flatMapLatest { email ->
        if (email == null) flowOf(null)
        else accounts.map { list -> list.find { it.email == email } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val folders = _selectedAccountEmail.flatMapLatest { email ->
        if (email == null) flowOf(emptyList())
        else dao.getFoldersFlow(email)
    }
    
    private val _selectedFolderName = MutableStateFlow("INBOX")
    val selectedFolderName: StateFlow<String> = _selectedFolderName

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedMessageUids = MutableStateFlow<Set<Long>>(emptySet())
    val selectedMessageUids: StateFlow<Set<Long>> = _selectedMessageUids

    val messages: Flow<List<EmailMessage>> = combine(
        _selectedAccountEmail,
        _selectedFolderName,
        _searchQuery
    ) { email, folder, query ->
        Triple(email, folder, query)
    }.flatMapLatest { (email, folder, query) ->
        if (email == null) {
            // Unified Inbox
            if (query.isEmpty()) dao.getUnifiedMessagesFlow("INBOX")
            else dao.searchUnifiedMessagesFlow("INBOX", query)
        } else {
            if (query.isEmpty()) {
                dao.getMessagesFlow(email, folder)
            } else {
                dao.searchMessagesFlow(email, folder, query)
            }
        }
    }

    init {
        viewModelScope.launch {
            accounts.first().firstOrNull()?.let {
                _selectedAccountEmail.value = it.email
            }
        }
    }

    fun selectAccount(email: String) {
        _selectedAccountEmail.value = if (email.isEmpty()) null else email
        _selectedFolderName.value = "INBOX"
        _searchQuery.value = ""
    }

    fun selectFolder(folderName: String) {
        _selectedFolderName.value = folderName
        _searchQuery.value = ""
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isEmpty()) {
            _aiSummary.value = null
            _aiSummaryLoading.value = false
        }
    }

    fun requestAiSummary(messages: List<EmailMessage>) {
        if (_aiSummaryLoading.value) return

        val pm = appContext.packageManager
        try {
            pm.getPackageInfo(OA_PACKAGE, 0)
        } catch (_: Exception) {
            return
        }

        _aiSummaryLoading.value = true
        _aiSummary.value = null

        val emailSnippets = messages.take(5).joinToString("\n---\n") { msg ->
            val plainBody = msg.body?.let { body ->
                if (msg.isHtml) HtmlCompat.fromHtml(body, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                else body
            }?.take(150) ?: ""
            "Subject: ${msg.subject}\nFrom: ${msg.from.substringBefore("<").trim()}\n$plainBody"
        }
        val prompt = "Summarize these emails in 1-2 sentences:\n\n$emailSnippets"
        val schema = """{"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"]}"""

        val receiver = SecureResultReceiver(Handler(Looper.getMainLooper())) { code, data ->
            if (code == 0) {
                val json = data?.getString("json_result")
                if (json != null) {
                    try {
                        val obj = Json.parseToJsonElement(json).jsonObject
                        _aiSummary.value = obj["summary"]?.jsonPrimitive?.content
                    } catch (_: Exception) { }
                }
            }
            _aiSummaryLoading.value = false
        }

        val intent = Intent().apply {
            component = ComponentName(OA_PACKAGE, OA_SERVICE)
            putExtra("user_text", prompt)
            putExtra("schema", schema)
            putExtra("RECEIVER", receiver as android.os.ResultReceiver)
        }

        try {
            appContext.startForegroundService(intent)
        } catch (_: Exception) {
            _aiSummaryLoading.value = false
        }
    }

    fun toggleMessageSelection(uid: Long) {
        val current = _selectedMessageUids.value
        if (uid in current) {
            _selectedMessageUids.value = current - uid
        } else {
            _selectedMessageUids.value = current + uid
        }
    }

    fun clearSelection() {
        _selectedMessageUids.value = emptySet()
    }

    fun markAsRead(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) {
        viewModelScope.launch {
            dao.updateReadStatus(accountEmail, folderName, uid, isRead)
            // Sync read status to IMAP server
            val account = dao.getAccountByEmail(accountEmail) ?: return@launch
            try {
                emailManager.setSeenFlag(
                    server = account.imapServer(),
                    user = account.email,
                    auth = account.authType(),
                    folderName = folderName,
                    uid = uid,
                    seen = isRead,
                )
            } catch (e: Exception) {
                android.util.Log.w("EmailViewModel", "Failed to sync read status to server: ${e.message}")
            }
        }
    }

    fun bulkMarkAsRead(accountEmail: String, uids: List<Long>, isRead: Boolean) {
        viewModelScope.launch {
            dao.updateBulkReadStatus(accountEmail, uids, isRead)
            clearSelection()
        }
    }

    fun refresh(context: android.content.Context) {
        EmailSyncWorker.runOneOffSync(context)
    }

    suspend fun getMessage(accountEmail: String, folderName: String, uid: Long): EmailMessage? {
        return dao.getMessage(accountEmail, folderName, uid)
    }

    fun getThread(accountEmail: String, threadId: String): Flow<List<EmailMessage>> {
        return dao.getThreadFlow(accountEmail, threadId)
    }

    suspend fun getAttachments(accountEmail: String, messageId: Long): List<Attachment> {
        return dao.getAttachments(accountEmail, messageId)
    }

    fun logout(context: android.content.Context) {
        val currentEmail = _selectedAccountEmail.value ?: return
        viewModelScope.launch {
            val account = dao.getAccounts().find { it.email == currentEmail }
            if (account != null) {
                dao.deleteAccount(account)
                dao.clearFolders(currentEmail)
                dao.clearMessages(currentEmail)
            }
            val remaining = dao.getAccounts()
            if (remaining.isEmpty()) {
                _selectedAccountEmail.value = null
                EmailSyncWorker.cancelSync(context)
                com.vayunmathur.email.data.ImapIdleService.stop(context)
            } else {
                _selectedAccountEmail.value = remaining.first().email
            }
        }
    }

    fun sendEmailFrom(
        account: EmailAccount,
        to: String,
        subject: String,
        body: String,
        cc: String? = null,
        attachments: List<Uri> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
        onSuccess: () -> Unit,
        /**
         * Called after the message has been queued to the outbox because the
         * immediate send failed. The supplied string is the underlying error
         * message — the UI typically surfaces it via a Snackbar like
         * "Saved to Outbox: …" and then pops the composer.
         */
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            suspend fun attemptSend(acct: EmailAccount) {
                emailManager.sendMessage(
                    context = getApplication(),
                    server = acct.smtpServer(),
                    user = acct.email,
                    auth = acct.authType(),
                    to = to,
                    subject = subject,
                    body = body,
                    cc = cc,
                    attachments = attachments,
                    inReplyTo = inReplyTo,
                    references = references,
                )
            }

            try {
                attemptSend(account)
                onSuccess()
            } catch (e: Exception) {
                val msg = e.message ?: e::class.simpleName ?: "Unknown error"
                // Persist to outbox so it survives app death; the worker will
                // retry every 5 minutes until it lands.
                try {
                    OutboxManager.enqueue(
                        context = getApplication(),
                        accountEmail = account.email,
                        to = to,
                        subject = subject,
                        body = body,
                        cc = cc,
                        attachments = attachments,
                        inReplyTo = inReplyTo,
                        references = references,
                        initialError = msg,
                    )
                } catch (queueError: Exception) {
                    // If even queueing fails, fall through to the error callback so
                    // the user sees *something* instead of silently losing the draft.
                    onError("$msg (and outbox save failed: ${queueError.message})")
                    return@launch
                }
                onError(msg)
            }
        }
    }

    /**
     * Lazy-load the body + attachments for a single message. Called by
     * MessageItem when a stored row has `body == null` (the sync only fetches
     * headers — bodies are downloaded on first open). Updates the row in the
     * DB; the Flow-based UI will recompose automatically.
     */
    fun fetchBodyIfNeeded(message: EmailMessage) {
        if (message.body != null) return
        viewModelScope.launch {
            val account = dao.getAccountByEmail(message.accountEmail) ?: return@launch
            suspend fun attempt(acct: EmailAccount): Triple<String?, Boolean, List<Attachment>> {
                return emailManager.fetchMessageBody(
                    server = acct.imapServer(),
                    user = acct.email,
                    auth = acct.authType(),
                    folderName = message.folderName,
                    uid = message.id,
                )
            }
            try {
                val (body, isHtml, attachments) = attempt(account)
                if (body != null || attachments.isNotEmpty()) {
                    dao.insertMessages(listOf(message.copy(body = body, isHtml = isHtml, hasAttachments = attachments.isNotEmpty())))
                    if (attachments.isNotEmpty()) dao.insertAttachments(attachments)
                }
            } catch (e: Exception) {
                android.util.Log.w("EmailViewModel", "fetchBodyIfNeeded for ${message.id} failed: ${e.message}")
            }
        }
    }

    fun deleteOutboxEntry(entry: OutboxEntry) {
        viewModelScope.launch {
            OutboxManager.delete(getApplication(), entry)
        }
    }

    fun sendOutboxNow(context: android.content.Context) {
        OutboxSendWorker.runNow(context)
    }

    fun downloadAttachment(
        attachment: Attachment,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val account = selectedAccount.value ?: return onError("No account selected")
        viewModelScope.launch {
            try {
                val path = emailManager.downloadAttachment(
                    context = getApplication(),
                    server = account.imapServer(),
                    user = account.email,
                    auth = account.authType(),
                    folderName = attachment.folderName,
                    uid = attachment.messageId,
                    partId = attachment.partId,
                    fileName = attachment.fileName
                )
                dao.updateAttachmentLocalUri(account.email, attachment.messageId, attachment.partId, path)
                onSuccess(path)
            } catch (e: Exception) {
                onError(e.message ?: "Unknown error")
            }
        }
    }

    companion object {
        private const val OA_PACKAGE = "com.vayunmathur.openassistant"
        private const val OA_SERVICE = "$OA_PACKAGE.util.InferenceService"
    }
}
