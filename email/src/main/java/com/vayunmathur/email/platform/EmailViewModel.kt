package com.vayunmathur.email.platform

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailPreview
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.data.EmailSyncState
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.data.OutboxEntry
import com.vayunmathur.email.platform.MessageListActions
import com.vayunmathur.email.platform.MessageThreadActions
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class EmailViewModel(application: Application) :
    AndroidViewModel(application), MessageListActions, MessageThreadActions {
    private val repository = EmailRepository.get(application)
    private val accountsDao: com.vayunmathur.email.data.EmailAccountDao
        get() = repository.getDatabase().accountDao()
    private val messagesDao: com.vayunmathur.email.data.EmailMessageDao
        get() = repository.getDatabase().messageDao()
    private val queriesDao: com.vayunmathur.email.data.EmailQueryDao
        get() = repository.getDatabase().queryDao()
    private val outboxDao: com.vayunmathur.email.data.EmailOutboxDao
        get() = repository.getDatabase().outboxDao()
    private val emailManager = EmailManager()
    private val appContext = application.applicationContext

    val draftsActions = EmailDraftActions(viewModelScope, repository.getDatabase().outboxDao())
    val send = EmailSendActions(viewModelScope, appContext, emailManager)
    val aiSummaryHelper = AiSummaryHelper(appContext)

    val accounts: StateFlow<List<EmailAccount>> =
        accountsDao.getAccountsFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Active sync state — drives the linear progress bar at the top of the inbox. */
    val isSyncing: StateFlow<Boolean> = EmailSyncState.isSyncing
    val syncProgress: StateFlow<Float> = EmailSyncState.progress

    val outbox: Flow<List<OutboxEntry>> = outboxDao.getOutboxFlow()
    val drafts: Flow<List<com.vayunmathur.email.data.DraftEntry>> = outboxDao.getDraftsFlow()

    /** Block a sender (matched by email address) so their mail is hidden from the inbox. */
    override fun blockSender(from: String) {
        val address = extractEmailAddress(from)
        if (address.isBlank()) return
        viewModelScope.launch {
            val sender = com.vayunmathur.email.data.BlockedSender(address.lowercase())
            accountsDao.insertBlockedSender(sender)
        }
    }

    fun unblockSender(address: String) {
        viewModelScope.launch { accountsDao.deleteBlockedSender(address) }
    }

    val aiSummary: StateFlow<String?> = aiSummaryHelper.summary
    val aiSummaryLoading: StateFlow<Boolean> = aiSummaryHelper.loading
    
    private val _selectedAccountEmail = MutableStateFlow<String?>(null)
    val selectedAccountEmail: StateFlow<String?> = _selectedAccountEmail

    val selectedAccount = _selectedAccountEmail.flatMapLatest { email ->
        if (email == null) flowOf(null)
        else accounts.map { list -> list.find { it.email == email } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val folders = _selectedAccountEmail.flatMapLatest { email ->
        if (email == null) flowOf(emptyList())
        else accountsDao.getFoldersFlow(email)
    }
    
    private val _selectedFolderName = MutableStateFlow("INBOX")
    val selectedFolderName: StateFlow<String> = _selectedFolderName

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedMessageUids = MutableStateFlow<Set<Long>>(emptySet())
    val selectedMessageUids: StateFlow<Set<Long>> = _selectedMessageUids

    private val messagesRaw: Flow<List<EmailPreview>> = combine(
        _selectedAccountEmail,
        _selectedFolderName,
        _searchQuery
    ) { email, folder, query ->
        Triple(email, folder, query)
    }.flatMapLatest { (email, folder, query) ->
        val now = System.currentTimeMillis()
        if (email == null) {
            // Unified Inbox
            if (query.isEmpty()) queriesDao.getUnifiedMessagesPreviewFlow("INBOX", now)
            else queriesDao.searchUnifiedMessagesPreviewFlow("INBOX", query, now)
        } else {
            if (query.isEmpty()) {
                queriesDao.getMessagesPreviewFlow(email, folder, now)
            } else {
                queriesDao.searchMessagesPreviewFlow(email, folder, query, now)
            }
        }
    }

    val blockedSenders: Flow<List<com.vayunmathur.email.data.BlockedSender>> = accountsDao.getBlockedSendersFlow()

    // Hide messages from blocked senders (matched by email address substring).
    val messages: Flow<List<EmailPreview>> = combine(messagesRaw, blockedSenders) { msgs, blocked ->
        val addrs = blocked.map { it.address.lowercase() }
        if (addrs.isEmpty()) msgs
        else msgs.filter { m -> addrs.none { m.from.lowercase().contains(it) } }
    }

    init {
        viewModelScope.launch {
            accountsDao.getAccounts().firstOrNull()?.let {
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
            accountsDao.setSignature(email, signature)
        }
    }

    fun selectFolder(folderName: String) {
        _selectedFolderName.value = folderName
        _searchQuery.value = ""
    }

    override fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isEmpty()) {
            aiSummaryHelper.clear()
        }
    }

    override fun requestAiSummary(messages: List<EmailPreview>) {
        aiSummaryHelper.request(messages)
    }

    override fun toggleMessageSelection(uid: Long) {
        _selectedMessageUids.update { if (uid in it) it - uid else it + uid }
    }

    override fun clearSelection() {
        _selectedMessageUids.value = emptySet()
    }

    /**
     * Delete a message: remove locally (with a tombstone so sync won't re-add
     * it) and expunge it on the IMAP server.
     */
    override fun deleteMessage(accountEmail: String, folderName: String, uid: Long) {
        viewModelScope.launch {
            messagesDao.deleteMessageRow(accountEmail, folderName, uid, tombstone = true)
            val account = accountsDao.getAccountByEmail(accountEmail) ?: return@launch
            try {
                emailManager.deleteMessage(
                    server = account.imapServer(),
                    user = account.loginUser(),
                    auth = account.resolveAuth(appContext),
                    folderName = folderName,
                    uid = uid,
                )
            } catch (_: Exception) {
                Log.status("EmailViewModel", "Failed to delete message on server")
            }
        }
    }

    /** Snooze a message: hide from the inbox until [until] (epoch millis), then resurface. */
    override fun snoozeMessage(accountEmail: String, folderName: String, uid: Long, until: Long) {
        viewModelScope.launch {
            messagesDao.setSnooze(accountEmail, folderName, uid, until)
            com.vayunmathur.email.data.SnoozeWorker.scheduleNext(getApplication(), until)
        }
    }

    override fun markAsRead(accountEmail: String, folderName: String, uid: Long, isRead: Boolean) {
        viewModelScope.launch {
            messagesDao.updateReadStatus(accountEmail, folderName, uid, isRead)
            // Sync read status to IMAP server
            val account = accountsDao.getAccountByEmail(accountEmail) ?: return@launch
            try {
                emailManager.setSeenFlag(
                    server = account.imapServer(),
                    user = account.loginUser(),
                    auth = account.resolveAuth(appContext),
                    folderName = folderName,
                    uid = uid,
                    seen = isRead,
                )
            } catch (_: Exception) {
                Log.status("EmailViewModel", "Failed to sync read status to server")
            }
        }
    }

    override fun bulkMarkAsRead(accountEmail: String, uids: List<Long>, isRead: Boolean) {
        viewModelScope.launch {
            messagesDao.updateBulkReadStatus(accountEmail, uids, isRead)
            clearSelection()
        }
    }

    override fun refresh(context: android.content.Context) {
        EmailSyncWorker.runOneOffSync(context)
    }

    suspend fun getMessage(accountEmail: String, folderName: String, uid: Long): EmailMessage? {
        return messagesDao.getMessage(accountEmail, folderName, uid)
    }

    fun getThread(accountEmail: String, threadId: String): Flow<List<EmailMessage>> {
        return messagesDao.getThreadFlow(accountEmail, threadId)
    }

    override suspend fun getAttachments(accountEmail: String, messageId: Long): List<Attachment> {
        return messagesDao.getAttachments(accountEmail, messageId)
    }

    fun logout(context: android.content.Context) {
        val currentEmail = _selectedAccountEmail.value ?: return
        viewModelScope.launch {
            accountsDao.getAccounts().find { it.email == currentEmail }?.let { account ->
                accountsDao.deleteAccount(account)
                accountsDao.clearFolders(currentEmail)
                messagesDao.clearMessages(currentEmail)
            }
            val remaining = accountsDao.getAccounts()
            _selectedAccountEmail.value = remaining.firstOrNull()?.email
            if (remaining.isEmpty()) {
                EmailSyncWorker.cancelSync(context)
                com.vayunmathur.email.data.ImapIdleService.stop(context)
            }
        }
    }

    /**
     * Lazy-load the body + attachments for a single message. Called by
     * MessageItem when a stored row has `body == null` (the sync only fetches
     * headers — bodies are downloaded on first open). Updates the row in the
     * DB; the Flow-based UI will recompose automatically.
     * Also extracts inline CID images to cache/cid/<uid>/ for viewer rendering.
     */
    override fun fetchBodyIfNeeded(message: EmailMessage) {
        if (message.body != null) return
        viewModelScope.launch {
            val account = accountsDao.getAccountByEmail(message.accountEmail) ?: return@launch
            try {
                // We need raw mime to extract CID files
                val ctx = getApplication<Application>()
                val full = emailManager.fetchFullForBody(
                    context = ctx,
                    server = account.imapServer(),
                    user = account.loginUser(),
                    auth = account.resolveAuth(appContext),
                    folderName = message.folderName,
                    uid = message.id,
                    accountEmail = message.accountEmail,
                )
                val (body, isHtml, attachments) = full.contentTriple
                if (body != null || attachments.isNotEmpty()) {
                    val updated = message.copy(
                        body = body,
                        isHtml = isHtml,
                        hasAttachments = attachments.isNotEmpty(),
                    )
                    messagesDao.insertMessages(listOf(updated))
                    if (attachments.isNotEmpty()) messagesDao.insertAttachments(attachments)
                }
            } catch (_: Exception) {
                Log.status("EmailViewModel", "fetchBodyIfNeeded for ${message.id} failed")
            }
        }
    }

    /** CID map loader for an already-fetched message: extracts inline files to cache. */
    override suspend fun loadCidMap(
        context: android.content.Context,
        message: EmailMessage,
    ): Map<String, java.io.File> {
        return try {
            val account = accountsDao.getAccountByEmail(message.accountEmail) ?: return emptyMap()
            val auth = account.resolveAuth(appContext)
            emailManager.fetchCidMap(
                context = context,
                server = account.imapServer(),
                user = account.loginUser(),
                auth = auth,
                folderName = message.folderName,
                uid = message.id,
            )
        } catch (_: Exception) {
            Log.status("EmailViewModel", "loadCidMap failed")
            emptyMap()
        }
    }

    /**
     * Perform an RFC 8058 one-click unsubscribe: an HTTPS POST to [url] with the
     * body `List-Unsubscribe=One-Click`. Runs off the main thread and reports
     * success (a 2xx response) via [onResult] on the main thread.
     */
    override fun oneClickUnsubscribe(url: String, onResult: (Boolean) -> Unit) {
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
                } catch (_: Exception) {
                    Log.status("EmailViewModel", "one-click unsubscribe failed")
                    false
                }
            }
            onResult(ok)
        }
    }

    override fun downloadAttachment(
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
                messagesDao.updateAttachmentLocalUri(account.email, attachment.messageId, attachment.partId, path)
                onSuccess(path)
            } catch (ignored: Exception) {
                onError(ignored.message ?: "Unknown error")
            }
        }
    }

    /**
     * Export a single email as a .eml file to a SAF [targetUri] (from CreateDocument).
     * Streams the raw RFC822 bytes directly to avoid OOM on large messages.
     */
    override fun exportEml(
        accountEmail: String,
        folderName: String,
        uid: Long,
        targetUri: Uri,
        onResult: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val account = accountsDao.getAccountByEmail(accountEmail)
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, "Account not found") }
                        return@launch
                    }
                val server = account.imapServer()
                val user = account.loginUser()
                val auth = account.resolveAuth(appContext)
                val ctx = getApplication<Application>()
                ctx.contentResolver.openOutputStream(targetUri)?.use { out ->
                    emailManager.fetchRawMessageTo(server, user, auth, folderName, uid, out)
                } ?: run {
                    withContext(Dispatchers.Main) { onResult(false, "Could not open destination") }
                    return@launch
                }
                withContext(Dispatchers.Main) { onResult(true, null) }
            } catch (ignored: Exception) {
                Log.status("EmailViewModel", "exportEml failed", ignored)
                withContext(Dispatchers.Main) { onResult(false, ignored.message ?: ignored.javaClass.simpleName) }
            }
        }
    }
}

/** Extract the bare email address from a "Name <addr@x>" header, or "" if none. */
private fun extractEmailAddress(from: String): String {
    val trimmed = from.trim()
    if (trimmed.isEmpty()) return ""
    val angleMatch = Regex("""<([^>]+@[^>]+)>""").find(trimmed)
    if (angleMatch != null) {
        val addr = angleMatch.groupValues[1].trim()
        if (addr.contains("@")) return addr
    }
    // Try bare address token
    val tokens = trimmed.split(Regex("[\\s,;<>]+"))
    for (t in tokens) {
        if (t.contains("@") && t.contains(".")) return t.trim().trim('"', '\'', '<', '>')
    }
    // Fallback: contains @ anywhere
    if (trimmed.contains("@")) {
        val maybe = trimmed.substringAfterLast(' ').trim()
        if (maybe.contains("@")) return maybe.trim('<', '>', '"', '\'')
    }
    return ""
}
