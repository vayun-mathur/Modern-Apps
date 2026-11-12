package com.vayunmathur.email.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.email.R
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailSettings
import com.vayunmathur.email.data.accountColor
import com.vayunmathur.email.data.senderDisplayName
import com.vayunmathur.email.platform.MessageThreadActions
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HtmlText
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconInbox
import com.vayunmathur.library.ui.IconMarkRead
import com.vayunmathur.library.ui.IconMarkUnread
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.contentColorOn
import com.vayunmathur.library.ui.rememberModalBottomSheetState
import com.vayunmathur.library.util.AppMessages

/** Test tag on the HTML body view of the open message. */
internal const val MESSAGE_HTML_BODY_TAG = "message-html-body"

/** Share of the message pane the footer (quotes, attachments, actions) may take before it scrolls. */
private const val FOOTER_MAX_FRACTION = 0.4f

/**
 * One message, laid out to fill the height it is given: the sender header on
 * top, the body in the middle taking all remaining space, and the footer
 * (quote toggle, attachments, reply/forward, unsubscribe/block) below.
 *
 * The body scrolls itself and nothing around it scrolls on the same axis
 * (#900). An HTML body is a WebView that fills its slot and scrolls
 * internally; it is never wrap-content inside a scrolling list, which needed
 * the WebView to report its full content height and broke portrait scrolling
 * on wide newsletters when it didn't. A plain-text body is ordinary Compose
 * text and scrolls together with its footer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageItem(
    msg: EmailMessage,
    actions: MessageThreadActions,
    onBack: () -> Unit,
    onReply: (String, String, String?) -> Unit,
    onForward: (String, String?) -> Unit,
    onCompose: (String, String) -> Unit,
    onExportEml: (EmailMessage) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var showDetails by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    LaunchedEffect(msg.id) {
        attachments = actions.getAttachments(msg.accountEmail, msg.id)
        if (msg.body == null) actions.fetchBodyIfNeeded(msg)
    }
    val senderName = senderDisplayName(msg.from).ifEmpty { msg.from }
    val senderEmail = msg.from.substringAfter("<").substringBefore(">").trim()
    val avatarColor = Color(accountColor(msg.accountEmail))
    val footer: @Composable ColumnScope.() -> Unit = {
        MessageFooter(msg = msg, attachments = attachments, actions = actions, onBack = onBack, onReply = onReply, onForward = onForward, onCompose = onCompose)
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val footerMaxHeight = maxHeight * FOOTER_MAX_FRACTION
        Column(Modifier.fillMaxSize()) {
            MessageHeader(msg, senderName, avatarColor, actions, onBack, onReply, onExportEml, onShowDetails = { showDetails = true })
            if (msg.isHtml && msg.body != null) {
                HtmlMessageBody(msg, msg.body, actions, footerMaxHeight, footer)
            } else {
                PlainMessageBody(msg, footer)
            }
        }
    }
    if (showDetails) {
        ModalBottomSheet(onDismissRequest = { showDetails = false }, sheetState = sheetState) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text(text = msg.date, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                DetailItem(label = stringResource(R.string.from_label), name = senderName, email = senderEmail, avatarColor = avatarColor)
                DetailItem(label = stringResource(R.string.to_label), name = "me", email = msg.to ?: "", avatarColor = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconInbox(modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(text = msg.folderName, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun MessageHeader(
    msg: EmailMessage,
    senderName: String,
    avatarColor: Color,
    actions: MessageThreadActions,
    onBack: () -> Unit,
    onReply: (String, String, String?) -> Unit,
    onExportEml: (EmailMessage) -> Unit,
    onShowDetails: () -> Unit,
) {
    val resources = LocalResources.current
    var showOverflow by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onShowDetails).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = avatarColor, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(text = senderName.take(1).uppercase(), color = contentColorOn(avatarColor), style = MaterialTheme.typography.titleMedium) }
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = senderName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(text = "  \u2022  ${msg.date.substringBeforeLast(":")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(text = stringResource(R.string.to_me), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            var showSnooze by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { showSnooze = true }) { Text(stringResource(R.string.snooze)) }
                DropdownMenu(expanded = showSnooze, onDismissRequest = { showSnooze = false }) {
                    val snooze = { at: Long ->
                        showSnooze = false
                        actions.snoozeMessage(msg.accountEmail, msg.folderName, msg.id, at)
                        AppMessages.show(resources.getString(R.string.snoozed))
                        onBack()
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.later_today_6_pm)) }, onClick = { snooze(scheduleTime(18, sameDay = true)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.tomorrow_8_am)) }, onClick = { snooze(scheduleTime(8, sameDay = false)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.in_1_week)) }, onClick = { snooze(System.currentTimeMillis() + 7L * 24 * 3600_000) })
                }
            }
            IconButton(onClick = { onReply(msg.from, msg.subject, msg.serverId) }) { IconUndo() }
            IconButton(onClick = { actions.markAsRead(msg.accountEmail, msg.folderName, msg.id, !msg.isRead); if (msg.isRead) onBack() }) {
                if (msg.isRead) IconMarkUnread() else IconMarkRead()
            }
            Box {
                IconButton(onClick = { showOverflow = true }) { IconMoreVert() }
                DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_as_eml)) }, leadingIcon = { IconSave(modifier = Modifier.size(20.dp)) }, onClick = { showOverflow = false; onExportEml(msg) })
                }
            }
        }
    }
}

/**
 * Remote-images banner, then the WebView filling every pixel the header and
 * footer leave, then the footer capped at [footerMaxHeight] (it scrolls on its
 * own past that, so a long attachment list can't squeeze the body away).
 */
@Composable
private fun ColumnScope.HtmlMessageBody(
    msg: EmailMessage,
    body: String,
    actions: MessageThreadActions,
    footerMaxHeight: Dp,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val settings = remember(context) { EmailSettings.get(context) }
    val loadRemoteByDefault by settings.loadRemoteImages.collectAsStateWithLifecycle()
    var loadImages by remember(msg.id, loadRemoteByDefault) { mutableStateOf(loadRemoteByDefault) }
    var showQuotes by remember(msg.id) { mutableStateOf(false) }
    var cidMap by remember(msg.id) { mutableStateOf<Map<String, java.io.File>>(emptyMap()) }
    val bodyHasCid = remember(body) { body.contains("cid:", ignoreCase = true) }
    LaunchedEffect(msg.id, body) { if (bodyHasCid) { val map = actions.loadCidMap(context, msg); if (map.isNotEmpty()) cidMap = map } }
    val hasQuotes = remember(body) { listOf("gmail_quote", "yahoo_quoted", "moz-cite-prefix", "<blockquote").any { body.contains(it, ignoreCase = true) } }
    val remoteBlocked = !loadImages && cidMap.isEmpty() && !bodyHasCid
    if (remoteBlocked) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.remote_images_blocked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { loadImages = true }) { Text(stringResource(R.string.load_images)) }
        }
    }
    HtmlText(
        html = body,
        blockRemoteImages = remoteBlocked,
        hideQuotes = hasQuotes && !showQuotes,
        cidMap = cidMap,
        scrollInternally = true,
        fitContentToWidth = true,
        // 8dp here plus the document's 8px body padding lines text up with the
        // 16dp header while leaving fixed-width mail as much width as possible.
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp).testTag(MESSAGE_HTML_BODY_TAG),
    )
    Column(Modifier.fillMaxWidth().heightIn(max = footerMaxHeight).verticalScroll(rememberScrollState())) {
        if (hasQuotes) {
            TextButton(onClick = { showQuotes = !showQuotes }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (showQuotes) stringResource(R.string.hide_quoted_text) else stringResource(R.string.show_quoted_text)) }
        }
        footer()
    }
}

/** Plain text is ordinary Compose text: it and the footer scroll together below the header. */
@Composable
private fun ColumnScope.PlainMessageBody(msg: EmailMessage, footer: @Composable ColumnScope.() -> Unit) {
    var showQuotes by remember(msg.id) { mutableStateOf(false) }
    val (mainText, quotedText) = remember(msg.body) { splitQuotedText(msg.body ?: "(No Content)") }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(text = if (showQuotes || quotedText.isEmpty()) (msg.body ?: stringResource(R.string.no_content)) else mainText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        if (quotedText.isNotEmpty()) {
            TextButton(onClick = { showQuotes = !showQuotes }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (showQuotes) stringResource(R.string.hide_quoted_text) else stringResource(R.string.show_quoted_text)) }
        }
        footer()
    }
}

private fun splitQuotedText(body: String): Pair<String, String> {
    val lines = body.split("\n")
    val onWrote = Regex("^On .+ wrote:\\s*$")
    val origMsg = Regex("^-{2,}\\s*Original Message\\s*-{2,}\\s*$", RegexOption.IGNORE_CASE)
    for (i in lines.indices) {
        val line = lines[i].trim()
        val isBoundary = onWrote.matches(line) || origMsg.matches(line) || line.startsWith(">")
        if (isBoundary && i > 0) {
            val main = lines.subList(0, i).joinToString("\n").trimEnd()
            val quoted = lines.subList(i, lines.size).joinToString("\n")
            return main to quoted
        }
    }
    return body to ""
}
