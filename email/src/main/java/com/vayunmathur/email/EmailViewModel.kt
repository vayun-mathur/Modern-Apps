package com.vayunmathur.email

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import javax.mail.internet.InternetAddress
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailSyncState
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.data.OutboxManager
import com.vayunmathur.email.data.OutboxSendWorker
import com.vayunmathur.library.util.SecureResultReceiver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class EmailViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = EmailDatabase.getInstance(application).emailDao()
    private val emailManager = EmailManager()
    private val appContext = application.applicationContext
    
    val accounts: StateFlow<List<EmailAccount>> =
        dao.getAccountsFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Active sync state — drives the linear progress bar at the top of the inbox. */
    val isSyncing: StateFlow<Boolean> = EmailSyncState.isSyncing
    val syncProgress: StateFlow<Float> = EmailSyncState.progress

    val outbox: Flow<List<OutboxEntry>> = dao.getOutboxFlow()
    val drafts: Flow<List<com.vayunmathur.email.DraftEntry>> = dao.getDraftsFlow()

    /** Load a draft for resuming in the composer. */
    suspend fun loadDraft(id: Long): com.vayunmathur.email.DraftEntry? = dao.getDraft(id)

    /** Insert or update a draft; returns its id (new id when [id] is null). */
    fun saveDraft(
        id: Long?,
        accountEmail: String,
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        onSaved: (Long) -> Unit = {},
    ) {
        viewModelScope.launch {
            val rowId = dao.insertDraft(
                com.vayunmathur.email.DraftEntry(
                    id = id ?: 0,
                    accountEmail = accountEmail,
                    to = to, cc = cc, bcc = bcc, subject = subject, body = body,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            onSaved(if (id != null && id != 0L) id else rowId)
        }
    }

    fun deleteDraft(id: Long) {
        viewModelScope.launch { dao.deleteDraftById(id) }
    }

    /** Block a sender (matched by email address) so their mail is hidden from the inbox. */
    fun blockSender(from: String) {
        val address = extractEmailAddress(from)
        if (address.isBlank()) return
        viewModelScope.launch { dao.insertBlockedSender(com.vayunmathur.email.BlockedSender(address.lowercase())) }
    }

    fun unblockSender(address: String) {
        viewModelScope.launch { dao.deleteBlockedSender(address) }
    }

    /** Queue a message to send at [scheduledAt] (epoch millis) via the outbox. */
    fun scheduleSend(
        account: EmailAccount,
        to: String,
        subject: String,
        body: String,
        cc: String? = null,
        bcc: String? = null,
        attachments: List<Uri> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
        scheduledAt: Long,
        asHtml: Boolean = false,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            OutboxManager.enqueue(
                context = getApplication(),
                accountEmail = account.email,
                to = to, subject = subject, body = body,
                cc = cc, bcc = bcc, attachments = attachments,
                inReplyTo = inReplyTo, references = references,
                scheduledAt = scheduledAt,
                isHtml = asHtml,
            )
            onDone()
        }
    }

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

    private val messagesRaw: Flow<List<EmailMessage>> = combine(
        _selectedAccountEmail,
        _selectedFolderName,
        _searchQuery
    ) { email, folder, query ->
        Triple(email, folder, query)
    }.flatMapLatest { (email, folder, query) ->
        val now = System.currentTimeMillis()
        if (email == null) {
            // Unified Inbox
            if (query.isEmpty()) dao.getUnifiedMessagesFlow("INBOX", now)
            else dao.searchUnifiedMessagesFlow("INBOX", query, now)
        } else {
            if (query.isEmpty()) {
                dao.getMessagesFlow(email, folder, now)
            } else {
                dao.searchMessagesFlow(email, folder, query, now)
            }
        }
    }

    val blockedSenders: Flow<List<com.vayunmathur.email.BlockedSender>> = dao.getBlockedSendersFlow()

    // Hide messages from blocked senders (matched by email address substring).
    val messages: Flow<List<EmailMessage>> = combine(messagesRaw, blockedSenders) { msgs, blocked ->
        val addrs = blocked.map { it.address.lowercase() }
        if (addrs.isEmpty()) msgs
        else msgs.filter { m -> addrs.none { m.from.lowercase().contains(it) } }
    }

    init {
        viewModelScope.launch {
            dao.getAccounts().firstOrNull()?.let {
                _selectedAccountEmail.value = it.email
            }
        }
    }

    fun selectAccount(email: String) {
        _selectedAccountEmail.value = if (email.isEmpty()) null else email
        _selectedFolderName.value = "INBOX"
        _searchQuery.value = ""
    }

    /** Persist the per-account signature appended to outgoing messages. */
    fun setSignature(email: String, signature: String) {
        viewModelScope.launch {
            dao.setSignature(email, signature)
        }
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
            val plainBody = msg.plainTextBody()?.take(150) ?: ""
            "Subject: ${msg.subject}\nFrom: ${senderDisplayName(msg.from)}\n$plainBody"
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
        _selectedMessageUids.update { if (uid in it) it - uid else it + uid }
    }

    fun clearSelection() {
        _selectedMessageUids.value = emptySet()
    }

    /** Delete a message: remove locally (with a tombstone so sync won't re-add it) and expunge it on the IMAP server. */
    fun deleteMessage(accountEmail: String, folderName: String, uid: Long) {
        viewModelScope.launch {
            dao.deleteMessageRow(accountEmail, folderName, uid, tombstone = true)
            val account = dao.getAccountByEmail(accountEmail) ?: return@launch
            try {
                emailManager.deleteMessage(
                    server = account.imapServer(),
                    user = account.loginUser(),
                    auth = account.resolveAuth(appContext),
                    folderName = folderName,
                    uid = uid,
                )
            } catch (e: Exception) {
                android.util.Log.w("EmailViewModel", "Failed to delete message on server: ${e.message}")
            }
        }
    }

    /** Snooze a message: hide from the inbox until [until] (epoch millis), then resurface. */
    fun snoozeMessage(accountEmail: String, folderName: String, uid: Long, until: Long) {
        viewModelScope.launch {
            dao.setSnooze(accountEmail, folderName, uid, until)
            com.vayunmathur.email.data.SnoozeWorker.scheduleNext(getApplication(), until)
        }
    }

    fun markAsRead(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) {
        viewModelScope.launch {
            dao.updateReadStatus(accountEmail, folderName, uid, isRead)
            // Sync read status to IMAP server
            val account = dao.getAccountByEmail(accountEmail) ?: return@launch
            try {
                emailManager.setSeenFlag(
                    server = account.imapServer(),
                    user = account.loginUser(),
                    auth = account.resolveAuth(appContext),
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
            dao.getAccounts().find { it.email == currentEmail }?.let { account ->
                dao.deleteAccount(account)
                dao.clearFolders(currentEmail)
                dao.clearMessages(currentEmail)
            }
            val remaining = dao.getAccounts()
            _selectedAccountEmail.value = remaining.firstOrNull()?.email
            if (remaining.isEmpty()) {
                EmailSyncWorker.cancelSync(context)
                com.vayunmathur.email.data.ImapIdleService.stop(context)
            }
        }
    }

    fun sendEmailFrom(
        account: EmailAccount,
        to: String,
        subject: String,
        body: String,
        cc: String? = null,
        bcc: String? = null,
        attachments: List<Uri> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
        asHtml: Boolean = false,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                emailManager.sendMessage(
                    context = getApplication(),
                    server = account.smtpServer(),
                    user = account.loginUser(),
                    auth = account.resolveAuth(appContext),
                    to = to,
                    subject = subject,
                    body = body,
                    cc = cc,
                    bcc = bcc,
                    attachments = attachments,
                    inReplyTo = inReplyTo,
                    references = references,
                    from = account.email,
                    asHtml = asHtml,
                )
                onSuccess()
            } catch (e: Exception) {
                val msg = e.message ?: e::class.simpleName ?: "Unknown error"
                try {
                    OutboxManager.enqueue(
                        context = getApplication(),
                        accountEmail = account.email,
                        to = to,
                        subject = subject,
                        body = body,
                        cc = cc,
                        bcc = bcc,
                        attachments = attachments,
                        inReplyTo = inReplyTo,
                        references = references,
                        initialError = msg,
                        isHtml = asHtml,
                    )
                } catch (queueError: Exception) {
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
            try {
                val (body, isHtml, attachments) = emailManager.fetchMessageBody(
                    server = account.imapServer(),
                    user = account.loginUser(),
                    auth = account.resolveAuth(appContext),
                    folderName = message.folderName,
                    uid = message.id,
                )
                if (body != null || attachments.isNotEmpty()) {
                    dao.insertMessages(listOf(message.copy(body = body, isHtml = isHtml, hasAttachments = attachments.isNotEmpty())))
                    if (attachments.isNotEmpty()) dao.insertAttachments(attachments)
                }
            } catch (e: Exception) {
                android.util.Log.w("EmailViewModel", "fetchBodyIfNeeded for ${message.id} failed: ${e.message}")
            }
        }
    }

    /**
     * Perform an RFC 8058 one-click unsubscribe: an HTTPS POST to [url] with the
     * body `List-Unsubscribe=One-Click`. Runs off the main thread and reports
     * success (a 2xx response) via [onResult] on the main thread.
     */
    fun oneClickUnsubscribe(url: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 15_000
                        readTimeout = 15_000
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    }
                    try {
                        connection.outputStream.use { it.write("List-Unsubscribe=One-Click".toByteArray()) }
                        connection.responseCode in 200..299
                    } finally {
                        connection.disconnect()
                    }
                } catch (e: Exception) {
                    android.util.Log.w("EmailViewModel", "one-click unsubscribe failed: ${e.message}")
                    false
                }
            }
            onResult(ok)
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
                    user = account.loginUser(),
                    auth = account.resolveAuth(appContext),
                    folderName = attachment.folderName,
                    uid = attachment.messageId,
                    partId = attachment.partId,
                    fileName = attachment.fileName,
                    mimeType = attachment.mimeType
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

/** Extract the bare email address from a "Name <addr@x>" header, or "" if none. */
private fun extractEmailAddress(from: String): String =
    runCatching { InternetAddress.parse(from).firstOrNull()?.address }
        .getOrNull()?.takeIf { it.contains("@") } ?: ""
