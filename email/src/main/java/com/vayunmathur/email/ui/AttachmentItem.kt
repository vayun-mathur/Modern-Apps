package com.vayunmathur.email.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.email.R
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.platform.MessageThreadActions
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.AppMessages

@Composable
fun AttachmentItem(attachment: Attachment, actions: MessageThreadActions) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var downloading by remember { mutableStateOf(false) }
    var localPath by remember { mutableStateOf(attachment.localUri) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = attachment.fileName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        if (localPath != null) {
            Text(stringResource(R.string.open), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable {
                val uri = try { localPath?.toUri() } catch (_: Exception) { null }
                if (uri != null) {
                    val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, attachment.mimeType.ifBlank { "*/*" }); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    try { context.startActivity(Intent.createChooser(intent, null)) } catch (_: Exception) { AppMessages.show(resources.getString(R.string.no_app_can_open_this_file)) }
                }
            })
        } else {
            IconButton(onClick = {
                downloading = true
                actions.downloadAttachment(attachment, { path -> downloading = false; localPath = path; AppMessages.show(resources.getString(R.string.saved_to_downloads)) }, { error -> downloading = false; AppMessages.show(resources.getString(R.string.download_failed, error)) })
            }, enabled = !downloading) {
                // Busy affordance is the disabled state above; no spinner icon.
                IconDownload(modifier = Modifier.size(16.dp))
            }
        }
    }
}
