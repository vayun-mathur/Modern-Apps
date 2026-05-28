package com.vayunmathur.email

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailSyncWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class EmailViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = EmailDatabase.getInstance(application).emailDao()
    private val emailManager = EmailManager()
    
    val accounts = dao.getAccountsFlow()
    
    private val _selectedAccountEmail = MutableStateFlow<String?>(null)
    val selectedAccountEmail: StateFlow<String?> = _selectedAccountEmail

    val selectedAccount = _selectedAccountEmail.flatMapLatest { email ->
        if (email == null) flowOf(null)
        else accounts.map { list -> list.find { it.email == email } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                emailManager.sendMessage(
                    context = getApplication(),
                    host = "smtp.gmail.com",
                    user = account.email,
                    auth = EmailManager.AuthType.OAuth2(account.accessToken),
                    to = to,
                    subject = subject,
                    body = body,
                    cc = cc,
                    attachments = attachments,
                    inReplyTo = inReplyTo,
                    references = references
                )
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Unknown error")
            }
        }
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
                    host = "imap.gmail.com",
                    user = account.email,
                    auth = EmailManager.AuthType.OAuth2(account.accessToken),
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
}
