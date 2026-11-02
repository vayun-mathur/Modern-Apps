package com.vayunmathur.securemail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.IconShield
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberModalBottomSheetState
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.StoredMessage
import com.vayunmathur.securemail.platform.MailUiState
import java.text.DateFormat
import java.util.Date

/**
 * Reader, laid out like the email app's thread view: subject in the app bar,
 * one compact sender row (details in a bottom sheet), trust as a single line,
 * and the body unboxed with quoted history collapsed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: MailUiState,
    onReply: (StoredMessage) -> Unit,
    onBack: () -> Unit,
    message: StoredMessage? = null,
) {
    val msg = message
    AppScaffold(
        title = msg?.decryptedSubject?.ifBlank { "(no subject)" } ?: "Message",
        onNavigateBack = onBack,
        actions = {
            if (msg != null) {
                IconButton(onClick = { onReply(msg) }) { IconUndo() }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (msg == null) {
                    Text(
                        "Message not found.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    MessageBody(msg, ownAddress = state.address, onReply = { onReply(msg) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageBody(msg: StoredMessage, ownAddress: String, onReply: () -> Unit) {
    var showDetails by remember { mutableStateOf(false) }
    val sent = msg.folder == MailFolder.SENT
    val counterpart = if (sent) msg.toJson.ifBlank { "(unknown)" } else msg.from.ifBlank { "(encrypted sender)" }
    val date = remember(msg.dateMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(msg.dateMillis))
    }
    val avatarColor = MaterialTheme.colorScheme.primaryContainer

    Row(
        modifier = Modifier.fillMaxWidth().clickable { showDetails = true }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(counterpart, avatarColor, MaterialTheme.colorScheme.onPrimaryContainer, 40)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    counterpart,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    "  \u2022  $date",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                if (sent) "from me" else "to me",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TrustLine(msg)
        }
    }

    BodyText(msg)

    Row(modifier = Modifier.padding(16.dp)) {
        OutlinedButton(onClick = onReply, modifier = Modifier.weight(1f)) {
            IconUndo(modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Reply")
        }
    }
    HorizontalDivider()

    if (showDetails) {
        ModalBottomSheet(
            onDismissRequest = { showDetails = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text(date, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                DetailRow("From", if (sent) ownAddress else msg.from.ifBlank { "(encrypted sender)" }, avatarColor)
                DetailRow("To", if (sent) msg.toJson.ifBlank { "(unknown)" } else ownAddress, MaterialTheme.colorScheme.surfaceVariant)
                if (msg.verifiedSigner != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Signer fingerprint", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        msg.verifiedSigner.chunked(4).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TrustLine(msg: StoredMessage) {
    val colors = MaterialTheme.colorScheme
    val sent = msg.folder == MailFolder.SENT
    val (label, color) = when {
        sent && msg.unencrypted -> "Sent unencrypted \u00b7 no key for a recipient" to colors.error
        sent -> "Sent encrypted" to colors.primary
        msg.unencrypted -> "Not encrypted" to colors.error
        msg.verifiedSigner != null -> "Encrypted \u00b7 signature verified" to colors.primary
        msg.decryptedBody != null -> "Encrypted \u00b7 signature not verified" to colors.onSurfaceVariant
        else -> "Locked \u00b7 decrypting\u2026" to colors.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        val iconModifier = Modifier.size(14.dp)
        when {
            msg.unencrypted -> IconWarning(modifier = iconModifier, tint = color)
            msg.verifiedSigner != null -> IconShield(modifier = iconModifier, tint = color)
            else -> IconLock(modifier = iconModifier, tint = color)
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun BodyText(msg: StoredMessage) {
    val body = msg.decryptedBody ?: "(decrypting\u2026)"
    var showQuotes by remember(msg.id) { mutableStateOf(false) }
    val (main, quoted) = remember(body) { splitQuotedText(body) }
    Text(
        if (showQuotes || quoted.isEmpty()) body else main,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (quoted.isNotEmpty()) {
        TextButton(onClick = { showQuotes = !showQuotes }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(if (showQuotes) "Hide quoted text" else "Show quoted text")
        }
    }
}

@Composable
private fun DetailRow(label: String, address: String, avatarColor: Color) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(address, avatarColor, MaterialTheme.colorScheme.onSurface, 32)
            Text(address, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun Avatar(address: String, color: Color, contentColor: Color, sizeDp: Int) {
    Surface(shape = CircleShape, color = color, modifier = Modifier.size(sizeDp.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(address.take(1).uppercase(), color = contentColor, style = MaterialTheme.typography.titleMedium)
        }
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
