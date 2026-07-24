package com.vayunmathur.email.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.vayunmathur.email.accountColor
import com.vayunmathur.email.senderDisplayName
import com.vayunmathur.email.util.EmlAttachment
import com.vayunmathur.email.util.EmlUtils
import com.vayunmathur.email.util.ParsedEml
import com.vayunmathur.library.ui.HtmlText
import com.vayunmathur.library.ui.IconForward
import com.vayunmathur.library.ui.IconNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val t = parsed?.message?.subject ?: uriString.substringAfterLast('/').substringAfterLast("%2F")
                    Text(t.ifBlank { "E-mail file" }, maxLines = 1)
                },
                navigationIcon = { IconNavigation(onBack) },
                actions = {
                    val msg = parsed?.message
                    if (msg != null) {
                        IconButton(onClick = { onComposeForward("Fwd: ${msg.subject}", msg.body ?: "") }) {
                            IconForward()
                        }
                    }
                }
            )
        }
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
                        Text("Couldn't open this .eml file", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: "Unknown error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                        }) { Text("Retry") }
                    }
                }
            }
            else -> {
                val msg = parsed!!.message
                val emlAtts = parsed!!.emlAttachments
                val cidMap = parsed!!.inlineCidMap

                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                ) {
                    val senderName = senderDisplayName(msg.from).ifEmpty { msg.from }
                    val senderEmail = msg.from.substringAfter("<").substringBefore(">").trim()
                    val initial = senderName.take(1).uppercase()
                    val avatarColor = Color(accountColor(msg.from.ifBlank { "eml-viewer" }))

                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = avatarColor, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = initial, color = Color.White, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(text = senderName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(text = msg.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!msg.to.isNullOrBlank()) Text(text = "To: ${msg.to}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!msg.cc.isNullOrBlank()) Text(text = "Cc: ${msg.cc}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Text(text = msg.subject, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(12.dp))

                    if (msg.isHtml && msg.body != null) {
                        var loadImages by remember(uriString) { mutableStateOf(false) }
                        if (!loadImages && cidMap.isEmpty()) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Remote images blocked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { loadImages = true }) { Text("Load images") }
                            }
                        }
                        HtmlText(
                            html = msg.body,
                            blockRemoteImages = !loadImages && cidMap.isEmpty(),
                            cidMap = cidMap,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        )
                    } else {
                        Text(text = msg.body ?: "(No content)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                    }

                    if (emlAtts.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("Attachments:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
                        emlAtts.forEach { att -> EmlAttachmentItem(attachment = att) }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun EmlAttachmentItem(attachment: EmlAttachment) {
    val context = LocalContext.current
    var opening by remember(attachment.fileName) { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = "${attachment.fileName} · ${humanSize(attachment.bytes.size.toLong())}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        if (opening) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Text("Open", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable {
                opening = true
                openEmlAttachment(context, attachment) { success ->
                    opening = false
                    if (!success) Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
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
    } catch (e: Exception) {
        android.util.Log.w("EmlViewer", "open attachment failed: ${e.message}")
        onResult(false)
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.1f MB", mb)
}
