package com.vayunmathur.securemail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.HtmlText
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.securemail.data.StoredMessage

/**
 * Message body: the sender's HTML part when there is one (links, lists,
 * formatting), otherwise plain text with clickable URLs. HTML renders with
 * scripts off and every remote fetch blocked until the user opts in, so
 * opening a message can't run sender code or ping a tracker.
 */
@Composable
fun MessageBodyView(msg: StoredMessage) {
    val html = msg.decryptedHtml
    if (html != null) HtmlBody(msg.id, html) else PlainBody(msg)
}

@Composable
private fun HtmlBody(id: String, html: String) {
    var loadRemote by remember(id) { mutableStateOf(false) }
    var showQuotes by remember(id) { mutableStateOf(false) }
    val hasRemote = remember(html) { REMOTE_REF.containsMatchIn(html) }
    val hasQuotes = remember(html) { QUOTE_MARKERS.any { html.contains(it, ignoreCase = true) } }
    Column {
        if (hasRemote && !loadRemote) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Remote content blocked",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { loadRemote = true }) { Text("Load") }
            }
        }
        HtmlText(
            html = html,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            blockRemoteImages = !loadRemote,
            blockRemoteContent = !loadRemote,
            javaScriptEnabled = false,
            hideQuotes = hasQuotes && !showQuotes,
        )
        if (hasQuotes) {
            TextButton(onClick = { showQuotes = !showQuotes }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(if (showQuotes) "Hide quoted text" else "Show quoted text")
            }
        }
    }
}

@Composable
private fun PlainBody(msg: StoredMessage) {
    val body = msg.decryptedBody ?: "(decrypting\u2026)"
    var showQuotes by remember(msg.id) { mutableStateOf(false) }
    val (main, quoted) = remember(body) { splitQuotedText(body) }
    val linkColor = MaterialTheme.colorScheme.primary
    val shown = if (showQuotes || quoted.isEmpty()) body else main
    val annotated = remember(shown, linkColor) { linkify(shown, linkColor) }
    Text(
        annotated,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (quoted.isNotEmpty()) {
        TextButton(onClick = { showQuotes = !showQuotes }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(if (showQuotes) "Hide quoted text" else "Show quoted text")
        }
    }
}

/** Plain text with http(s) URLs and email addresses turned into tappable links. */
private fun linkify(text: String, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val style = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
    URL.findAll(text).forEach { m ->
        val raw = m.value.trimEnd('.', ',', ')', ']', ';', ':', '!', '?')
        val target = if (raw.contains('@') && !raw.startsWith("http", ignoreCase = true)) "mailto:$raw" else raw
        addLink(LinkAnnotation.Url(target, style), m.range.first, m.range.first + raw.length)
    }
}

private fun splitQuotedText(body: String): Pair<String, String> {
    val lines = body.split("\n")
    val onWrote = Regex("^On .+ wrote:\\s*$")
    val origMsg = Regex("^-{2,}\\s*Original Message\\s*-{2,}\\s*$", RegexOption.IGNORE_CASE)
    for (i in lines.indices) {
        val line = lines[i].trim()
        if (i > 0 && (onWrote.matches(line) || origMsg.matches(line) || line.startsWith(">"))) {
            return lines.subList(0, i).joinToString("\n").trimEnd() to lines.subList(i, lines.size).joinToString("\n")
        }
    }
    return body to ""
}

private val URL = Regex("""https?://[^\s<>"]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
private val REMOTE_REF = Regex("""(?i)(src|background|srcset)\s*=\s*["']?\s*https?://|url\(\s*["']?https?://""")
private val QUOTE_MARKERS = listOf("protonmail_quote", "gmail_quote", "yahoo_quoted", "moz-cite-prefix", "<blockquote")
