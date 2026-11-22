package com.vayunmathur.communicate.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.canSendPoll
import com.vayunmathur.communicate.data.canShareContact
import com.vayunmathur.communicate.data.findContactName
import com.vayunmathur.communicate.data.isUnknownContact
import com.vayunmathur.communicate.data.sendMessage
import com.vayunmathur.communicate.data.sendPoll
import com.vayunmathur.communicate.data.shareContact
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
    /** Shared text/attachments seeding the composer (Direct Share); empty for a plain open. */
    initialDraft: String = "",
    initialAttachmentUris: List<String> = emptyList(),
    onBack: () -> Unit,
    onOpenRcsVerify: (String) -> Unit = {}, onOpenRcsGroupMembers: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val simLineLabel = stringResource(R.string.account_sim)
    val contactShareFailedMessage = stringResource(R.string.contact_share_failed)
    val conversationTitleLabel = stringResource(R.string.conversation_title)
    val gvSendFailedMessage = stringResource(R.string.gv_send_failed)
    val pollSendFailedMessage = stringResource(R.string.poll_send_failed)
    val scope = rememberCoroutineScope()
    // Contextual notification ask (todo/files pattern: system request only at first
    // use, never on first frame): after the first successful send the user has shown
    // messages matter to them, so ask once there and persist that we asked.
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    // The send below only flips this flag; the ask itself runs in a
    // LaunchedEffect (the cast/email/taxi/fooddelivery pattern), so it can
    // only run while this composition — and therefore the launcher's
    // registration — is alive. Launching directly from the send-success
    // coroutine instead could outlive the composition: rememberCoroutineScope
    // cancellation is cooperative, and a send resuming off the main thread
    // raced unregistering the launcher (observed crashing the Y2 screen
    // tests with "unregistered ActivityResultLauncher"). The ask is
    // best-effort, so any residual unregistered state skips it silently
    // rather than crashing a successful send.
    var notificationsAskRequested by remember { mutableStateOf(false) }
    LaunchedEffect(notificationsAskRequested) {
        if (!notificationsAskRequested) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val prefs = context.getSharedPreferences(
                "communicate_notifications", Context.MODE_PRIVATE,
            )
            if (!prefs.getBoolean("has_asked_notifications", false)) {
                prefs.edit().putBoolean("has_asked_notifications", true).apply()
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    runCatching {
                        notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }
    var draft by remember(threadId, initialDraft) { mutableStateOf(initialDraft) }
    var selectedAttachments by remember(threadId, initialAttachmentUris) {
        mutableStateOf(
            initialAttachmentUris.mapNotNull { uriString ->
                runCatching {
                    val uri = android.net.Uri.parse(uriString)
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    CommunicateAttachment(
                        contentUri = uriString,
                        mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                        fileName = displayNameOf(context, uri),
                    )
                }.getOrNull()
            },
        )
    }
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
                    val sentDraft = draft
                    val attachments = selectedAttachments
                    if (text.isEmpty() && attachments.isEmpty()) return@ComposeSmsRow
                    // Keep the draft and attachments in place until the send
                    // succeeds: clearing them up front lost the composed
                    // message entirely when the send failed.
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
                        if (ok) {
                            notificationsAskRequested = true
                            // Clear only what was sent; anything the user
                            // typed or attached while sending survives.
                            if (draft == sentDraft) draft = ""
                            if (selectedAttachments == attachments) {
                                selectedAttachments = emptyList()
                            }
                            refresh++
                        } else {
                            AppMessages.show(gvSendFailedMessage)
                        }
                    }
                },
            )
        },
    ) { padding ->
        ConversationBody(
            padding = padding,
            line = line,
            remoteId = remoteId,
            address = address,
            refresh = refresh,
            onRefresh = { refresh++ },
            thread = thread,
            showPollComposer = showPollComposer,
            onDismissPollComposer = { showPollComposer = false },
            onCreatePoll = { question, options ->
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
}
