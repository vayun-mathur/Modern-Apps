package com.vayunmathur.communicate.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.SmsMessage
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.canSendPoll
import com.vayunmathur.communicate.data.canShareContact
import com.vayunmathur.communicate.data.findContactName
import com.vayunmathur.communicate.data.isUnknownContact
import com.vayunmathur.communicate.data.loadSmsMessagesMerged
import com.vayunmathur.communicate.data.acceptSignalIdentity
import com.vayunmathur.communicate.data.sendMessage
import com.vayunmathur.communicate.data.sendPoll
import com.vayunmathur.communicate.data.shareContact
import com.vayunmathur.communicate.data.signalPendingIdentityChange
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConversationScreen(
    threadId: Long,
    address: String,
    line: CommunicateLine,
    remoteId: String?,
    subscriptionId: Int? = null,
    isGroup: Boolean = false,
    participants: List<String> = emptyList(),
    groupTitle: String? = null,
    onBack: () -> Unit,
    onOpenRcsVerify: (String) -> Unit = {}, onOpenRcsGroupMembers: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val simLineLabel = stringResource(R.string.account_sim)
    val contactShareFailedMessage = stringResource(R.string.contact_share_failed)
    val conversationTitleLabel = stringResource(R.string.conversation_title)
    val gvSendFailedMessage = stringResource(R.string.gv_send_failed)
    val pollSendFailedMessage = stringResource(R.string.poll_send_failed)
    val safetyNumberFailedMessage = stringResource(R.string.signal_safety_number_accept_failed)
    val scope = rememberCoroutineScope()
    var draft by remember(threadId) { mutableStateOf("") }
    var selectedAttachments by remember(threadId) { mutableStateOf<List<CommunicateAttachment>>(emptyList()) }
    // Bumped after a send to re-fetch the thread.
    var refresh by remember(threadId) { mutableIntStateOf(0) }
    val lineChoices = rememberLineChoices()
    val fixedLineChoice = remember(line, subscriptionId, lineChoices) {
        when (line) {
            CommunicateLine.GoogleVoice -> LineChoice.GoogleVoice
            CommunicateLine.WhatsApp -> LineChoice.WhatsApp
            CommunicateLine.Signal -> LineChoice.Signal
            CommunicateLine.Rcs -> LineChoice.Rcs
            CommunicateLine.Sim -> lineChoices
                .filterIsInstance<LineChoice.Sim>()
                .firstOrNull { subscriptionId == null || it.subscriptionId == subscriptionId }
                ?: LineChoice.Sim(subscriptionId ?: -1, simLineLabel)
        }
    }
    // Poll composer and contact picker, offered only on lines that support them.
    var showPollComposer by remember { mutableStateOf(false) }
    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val shared = withContext(Dispatchers.IO) { readSharedContact(context, uri) }
            if (shared == null) {
                AppMessages.show(contactShareFailedMessage)
                return@launch
            }
            val ok = CommunicateRepository.shareContact(
                context = context,
                line = line,
                conversationId = remoteId ?: address,
                contact = shared,
            )
            if (!ok) AppMessages.show(contactShareFailedMessage)
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selectedAttachments = uris.map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            CommunicateAttachment(
                contentUri = uri.toString(),
                mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                fileName = displayNameOf(context, uri),
            )
        }
    }
    val thread = remember(threadId) {
        SmsThread(
            threadId = threadId,
            address = address,
            displayName = null,
            snippet = "",
            timestampMillis = 0,
            unreadCount = 0,
            line = line,
            remoteId = remoteId,
            isGroup = isGroup,
            participants = participants,
            groupTitle = groupTitle,
        )
    }
    val title = produceState(
        initialValue = address.ifBlank { conversationTitleLabel },
        address) {
        value = withContext(Dispatchers.IO) {
            CommunicateRepository.findContactName(context, address)
                ?: address.ifBlank { conversationTitleLabel }
        }
    }
    // Offer "save this number" only for an address that is genuinely an unsaved phone number.
    val isUnknownContact by produceState(initialValue = false, address) {
        value = withContext(Dispatchers.IO) { CommunicateRepository.isUnknownContact(context, address) }
    }
    // For groups, resolve a "Alice, Bob +N" subtitle from the participant addresses (contact names
    // where available). Cheap: runs once off the main thread.
    val groupSubtitle = produceState<String?>(initialValue = null, isGroup, participants) {
        if (!isGroup || participants.isEmpty()) {
            value = null
        } else {
            value = withContext(Dispatchers.IO) {
                val names = participants.take(3).map { p ->
                    CommunicateRepository.findContactName(context, p) ?: p
                }
                val extra = participants.size - names.size
                if (extra > 0) names.joinToString(", ") + " +$extra" else names.joinToString(", ")
            }
        }
    }

    // Per-line mark-read dispatch + foreground polling (no realtime channel
    // yet on GV/WhatsApp/Signal). Extracted to keep this file under the limit.
    ConversationReadEffects(
        context = context,
        line = line,
        threadId = threadId,
        remoteId = remoteId,
        address = address,
        onRefreshTick = { refresh++ },
    )

    // RCS peer typing indicator (§4.1): collect the typing flow for 1:1
    // threads (keyed by sender E.164 = remoteId/address).
    val isPeerTyping = rememberRcsPeerTyping(line, isGroup, remoteId, address)

    AppScaffold(
        title = {
            ConversationTitle(
                threadId = threadId,
                isGroup = isGroup,
                groupTitle = groupTitle,
                title = title.value,
                groupSubtitle = groupSubtitle.value,
                address = address,
                line = line,
                isPeerTyping = isPeerTyping,
            )
        },
        onNavigateBack = onBack,
        actions = {
            ConversationActions(
                threadId = threadId,
                address = address,
                line = line,
                remoteId = remoteId,
                isGroup = isGroup,
                participants = participants,
                groupTitle = groupTitle,
                isUnknownContact = isUnknownContact,
                onBack = onBack,
                onOpenRcsVerify = onOpenRcsVerify,
                onOpenRcsGroupMembers = onOpenRcsGroupMembers,
            )
        },
        bottomBar = {
            ComposeSmsRow(
                draft = draft,
                onDraftChange = {
                    draft = it
                    // Outbound typing (§4.1): throttled active/idle reports
                    // on the RCS 1:1 line only.
                    scope.sendRcsTyping(line, isGroup, remoteId, address, it)
                },
                attachments = selectedAttachments,
                // Media and documents both: every line's send path already accepts an arbitrary content type,
                // only this filter was stopping documents from being picked.
                onAttachMedia = { attachmentPicker.launch(arrayOf("image/*", "video/*")) },
                onAttachFile = { attachmentPicker.launch(arrayOf("*/*")) },
                onCreatePoll = if (CommunicateRepository.canSendPoll(line)) {
                    { showPollComposer = true }
                } else {
                    null
                },
                onShareContact = if (CommunicateRepository.canShareContact(line)) {
                    { contactPicker.launch(null) }
                } else {
                    null
                },
                onRemoveAttachment = { attachment ->
                    selectedAttachments = selectedAttachments.filterNot { it.contentUri == attachment.contentUri }
                },
                onSend = {
                    val text = draft.trim()
                    val attachments = selectedAttachments
                    if (text.isEmpty() && attachments.isEmpty()) return@ComposeSmsRow
                    draft = ""
                    selectedAttachments = emptyList()
                    scope.launch {
                        val ok = CommunicateRepository.sendMessage(
                            context,
                            fixedLineChoice,
                            address,
                            text,
                            if (fixedLineChoice is LineChoice.Sim) null else remoteId,
                            attachments,
                            participants = if (isGroup) participants else emptyList(),
                        )
                        if (ok) refresh++ else AppMessages.show(gvSendFailedMessage)
                    }
                },
            )
        },
    ) { padding ->
        if (showPollComposer) {
            PollComposerDialog(
                onDismiss = { showPollComposer = false },
                onCreate = { question, options ->
                    scope.launch {
                        val ok = CommunicateRepository.sendPoll(
                            context = context,
                            line = line,
                            conversationId = remoteId ?: address,
                            question = question,
                            options = options,
                        )
                        if (ok) refresh++ else AppMessages.show(pollSendFailedMessage)
                    }
                },
            )
        }
        // A changed Signal identity key blocks sending until the user verifies it, so the warning has to
        // be in front of them here rather than only in a notification.
        var safetyNumber by remember(remoteId, address) { mutableStateOf<Pair<String, String>?>(null) }
        androidx.compose.runtime.LaunchedEffect(line, remoteId, address, refresh) {
            safetyNumber = if (line == CommunicateLine.Signal) {
                try {
                    CommunicateRepository.signalPendingIdentityChange(context, remoteId, address)
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
        }
        safetyNumber?.let { (number, keyHex) ->
            SafetyNumberBanner(
                safetyNumber = number,
                onAccept = {
                    scope.launch {
                        val ok = CommunicateRepository.acceptSignalIdentity(context, remoteId, address, keyHex)
                        if (ok) {
                            safetyNumber = null
                            refresh++
                        } else {
                            AppMessages.show(safetyNumberFailedMessage)
                        }
                    }
                },
                modifier = Modifier.padding(padding),
            )
        }
        RcsEncryptionBanner(
            line = line,
            remoteId = remoteId,
            address = address,
            refresh = refresh,
            modifier = Modifier.padding(padding),
        )
        // Google Voice threads don't require the default-SMS role or READ_SMS; only SIM does.
        if (line == CommunicateLine.GoogleVoice) {
            MessagesList(padding, refresh) {
                CommunicateRepository.loadSmsMessagesMerged(context, thread)
            }
            return@AppScaffold
        }
        DefaultSmsGate(modifier = Modifier.padding(padding)) { roleRevision ->
            PermissionGate(
                permission = Manifest.permission.READ_SMS,
                message = stringResource(R.string.permission_sms_message),
                modifier = Modifier.padding(padding),
            ) { permissionRevision ->
                val messages = produceState<List<SmsMessage>?>(
                    initialValue = null,
                    threadId,
                    roleRevision,
                    permissionRevision,
                    refresh) {
                    value = withContext(Dispatchers.IO) { CommunicateRepository.loadSmsMessagesMerged(context, thread) }
                }
                MessagesContent(padding, messages.value)
            }
        }
    }
}
