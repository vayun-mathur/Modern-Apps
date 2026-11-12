package com.vayunmathur.email.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.R
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.EmlUtils
import com.vayunmathur.email.platform.MessageThreadActions
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.sharedText

@Composable
fun MessageThreadPage(
    viewModel: EmailViewModel,
    accountEmail: String,
    threadId: String,
    onBack: () -> Unit,
    onReply: (String, String, String?) -> Unit,
    onForward: (String, String?) -> Unit,
    onCompose: (String, String) -> Unit
) {
    val messages by viewModel.getThread(accountEmail, threadId).collectAsStateWithLifecycle(emptyList())
    MessageThreadScreen(messages = messages, actions = viewModel, threadId = threadId, onBack = onBack, onReply = onReply, onForward = onForward, onCompose = onCompose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageThreadScreen(
    messages: List<EmailMessage>,
    actions: MessageThreadActions,
    threadId: String = "",
    onBack: () -> Unit = {},
    onReply: (String, String, String?) -> Unit = { _, _, _ -> },
    onForward: (String, String?) -> Unit = { _, _ -> },
    onCompose: (String, String) -> Unit = { _, _ -> },
) {
    var hasMarkedAsRead by remember(threadId) { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<EmailMessage?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("message/rfc822")) { targetUri ->
        val msg = pendingExport
        pendingExport = null
        if (targetUri != null && msg != null) {
            actions.exportEml(msg.accountEmail, msg.folderName, msg.id, targetUri) { ok, err ->
                AppMessages.show(if (ok) "Saved as .eml" else "Save failed: ${err ?: "unknown"}")
            }
        }
    }
    LaunchedEffect(messages) {
        if (!hasMarkedAsRead && messages.isNotEmpty()) {
            messages.filter { !it.isRead }.forEach { msg -> actions.markAsRead(msg.accountEmail, msg.folderName, msg.id, true) }
            hasMarkedAsRead = true
        }
    }
    // The message being read, saved by key so rotation (the issue #900
    // workaround) keeps the same message open. Fixed to the default once the
    // thread first has messages: marking mail read on open re-emits the list and
    // must not move the selection, now or after a configuration change.
    var selectedKey by rememberSaveable(threadId) { mutableStateOf<String?>(null) }
    LaunchedEffect(threadId, messages.isNotEmpty()) {
        if (selectedKey == null) selectedKey = defaultMessage(messages)?.threadKey()
    }
    val selected = messages.firstOrNull { it.threadKey() == selectedKey } ?: defaultMessage(messages)
    AppScaffold(
        title = {
            val first = messages.firstOrNull()
            val subjectKey = first?.let { "email-subject-${it.accountEmail}-$threadId" }
            Text(
                first?.subject ?: stringResource(R.string.conversation),
                modifier = if (subjectKey == null) Modifier else Modifier.sharedText(subjectKey),
            )
        },
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        // Detail pane: letterboxed on expanded windows so message cards keep a
        // readable measure on desktop.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            // No outer vertical scroller (#900): the open message fills the pane and
            // its body scrolls itself, so the WebView never has to report a content
            // height to a parent list. Other messages in the thread are picked from
            // a short strip above it, which scrolls on its own.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val stripMaxHeight = maxHeight * THREAD_STRIP_MAX_FRACTION
                Column(Modifier.fillMaxSize()) {
                    if (messages.size > 1 && selected != null) {
                        ThreadMessageStrip(
                            messages = messages,
                            selectedKey = selected.threadKey(),
                            onSelect = { selectedKey = it.threadKey() },
                            modifier = Modifier.fillMaxWidth().heightIn(max = stripMaxHeight),
                        )
                        HorizontalDivider()
                    }
                    if (selected != null) {
                        key(selected.threadKey()) {
                            MessageItem(
                                msg = selected,
                                actions = actions,
                                onBack = onBack,
                                onReply = onReply,
                                onForward = onForward,
                                onCompose = onCompose,
                                onExportEml = { toExport ->
                                    pendingExport = toExport
                                    val fileName = EmlUtils.sanitizeFileName(toExport.subject.ifBlank { "email_${toExport.id}" })
                                    exportLauncher.launch(fileName)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Share of the pane the thread strip may take before it scrolls. */
private const val THREAD_STRIP_MAX_FRACTION = 0.3f

/** Stable identity of a message within a thread (accounts and folders can share UIDs). */
internal fun EmailMessage.threadKey(): String = "$accountEmail|$folderName|$id"

/**
 * The message a thread opens on: the oldest unread one, so a run of new
 * replies is read in order, or else the newest.
 */
internal fun defaultMessage(messages: List<EmailMessage>): EmailMessage? =
    messages.firstOrNull { !it.isRead } ?: messages.lastOrNull()
