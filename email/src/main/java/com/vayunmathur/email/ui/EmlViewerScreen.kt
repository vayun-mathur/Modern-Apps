package com.vayunmathur.email.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.vayunmathur.email.data.accountColor
import com.vayunmathur.email.data.senderDisplayName
import com.vayunmathur.email.platform.EmlAttachment
import com.vayunmathur.email.platform.EmlUtils
import com.vayunmathur.email.platform.ParsedEml
import com.vayunmathur.library.ui.HtmlText
import com.vayunmathur.library.ui.IconForward
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.contentColorOn
import com.vayunmathur.library.ui.appBarScrollBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.compose.ui.res.stringResource
import com.vayunmathur.email.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmlViewerScreen(
    uriString: String,
    onBack: () -> Unit,
    onComposeForward: (subject: String, body: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var parsed by remember(uriString) { mutableStateOf<ParsedEml?>(null) }
    var loading by remember(uriString) { mutableStateOf(true) }
    var error by remember(uriString) { mutableStateOf<String?>(null) }

    LaunchedEffect(uriString) {
        loading = true
        error = null
        parsed = null
        withContext(Dispatchers.IO) {
            runCatching { EmlUtils.parseEml(context, uriString.toUri()) }
                .onSuccess { parsed = it }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
        }
        loading = false
    }

    AppScaffold(
        title = {
            val t = parsed?.message?.subject ?: uriString.substringAfterLast('/').substringAfterLast("%2F")
            Text(t.ifBlank { stringResource(R.string.e_mail_file) }, maxLines = 1)
        },
        onNavigateBack = onBack,
        actions = {
            val msg = parsed?.message
            if (msg != null) {
                IconButton(onClick = { onComposeForward("Fwd: ${msg.subject}", msg.body ?: "") }) {
                    IconForward()
                }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        when {
            loading -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.couldn_t_open_this_eml_file), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: stringResource(R.string.unknown_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = {
                            scope.launch {
                                loading = true; error = null; parsed = null
                                withContext(Dispatchers.IO) {
                                    runCatching { EmlUtils.parseEml(context, uriString.toUri()) }
                                        .onSuccess { parsed = it }
                                        .onFailure { error = it.message ?: it.javaClass.simpleName }
                                }
                                loading = false
                            }
                        }) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
            else -> {
                val msg = parsed!!.message
                val emlAtts = parsed!!.emlAttachments
                val cidMap = parsed!!.inlineCidMap

                // Same layout as the thread screen (#900): header fixed on top, an
                // HTML body that fills the rest and scrolls itself, never a
                // wrap-content WebView inside a scrolling column.
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
                    val attachmentsMaxHeight = maxHeight * EML_ATTACHMENTS_MAX_FRACTION
                    Column(modifier = Modifier.fillMaxSize()) {
                        EmlHeader(msg.from, msg.date, msg.to, msg.cc, msg.subject)
                        if (msg.isHtml && msg.body != null) {
                            val settings = remember(context) { com.vayunmathur.email.data.EmailSettings.get(context) }
                            val loadRemoteByDefault by settings.loadRemoteImages.collectAsStateWithLifecycle()
                            var loadImages by remember(uriString, loadRemoteByDefault) { mutableStateOf(loadRemoteByDefault) }
                            if (!loadImages && cidMap.isEmpty()) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.remote_images_blocked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { loadImages = true }) { Text(stringResource(R.string.load_images)) }
                                }
                            }
                            HtmlText(
                                html = msg.body,
                                blockRemoteImages = !loadImages && cidMap.isEmpty(),
                                cidMap = cidMap,
                                scrollInternally = true,
                                fitContentToWidth = true,
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                            )
                            if (emlAtts.isNotEmpty()) {
                                Column(Modifier.fillMaxWidth().heightIn(max = attachmentsMaxHeight).verticalScroll(rememberScrollState())) {
                                    EmlAttachments(emlAtts)
                                }
                            }
                        } else {
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                                Text(text = msg.body ?: stringResource(R.string.no_content_1), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                                EmlAttachments(emlAtts)
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Share of the pane the attachment list under an HTML body may take before it scrolls. */
private const val EML_ATTACHMENTS_MAX_FRACTION = 0.3f

@Composable
private fun EmlHeader(from: String, date: String, to: String?, cc: String?, subject: String) {
    val senderName = senderDisplayName(from).ifEmpty { from }
    val avatarColor = Color(accountColor(from.ifBlank { "eml-viewer" }))
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = avatarColor, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = senderName.take(1).uppercase(), color = contentColorOn(avatarColor), style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(text = senderName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(text = date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!to.isNullOrBlank()) Text(text = stringResource(R.string.to, to), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!cc.isNullOrBlank()) Text(text = stringResource(R.string.cc_1, cc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(text = subject, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun EmlAttachments(attachments: List<EmlAttachment>) {
    if (attachments.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.attachments_2), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
    attachments.forEach { att -> EmlAttachmentItem(attachment = att) }
}

@Composable
private fun EmlAttachmentItem(attachment: EmlAttachment) {
    val context = LocalContext.current
    val cannotOpenMsg = stringResource(R.string.no_app_can_open_this_file)
    var opening by remember(attachment.fileName) { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = "${attachment.fileName} · ${humanSize(attachment.bytes.size.toLong())}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        if (opening) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.open), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable {
                opening = true
                openEmlAttachment(context, attachment) { success ->
                    opening = false
                    if (!success) AppMessages.show(cannotOpenMsg)
                }
            })
        }
    }
}

private fun openEmlAttachment(context: Context, att: EmlAttachment, onResult: (Boolean) -> Unit = {}) {
    try {
        val dir = File(context.cacheDir, "eml_attachments").also { it.mkdirs() }
        val safeName = att.fileName.replace(Regex("[/\\\\]"), "_").take(80).ifBlank { "attachment" }
        val file = File(dir, safeName)
        FileOutputStream(file).use { it.write(att.bytes) }
        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = att.mimeType.ifBlank { "*/*" }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, null))
            onResult(true)
        } catch (_: Exception) { onResult(false) }
    } catch (ignored: Exception) {
        Log.status("EmlViewer", "open attachment failed: ${ignored.message}")
        onResult(false)
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val locale = java.util.Locale.getDefault()
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(locale, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(locale, "%.1f MB", mb)
}
